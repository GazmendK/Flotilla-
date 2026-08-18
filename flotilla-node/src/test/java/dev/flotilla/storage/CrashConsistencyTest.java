/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.HardState;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.storage.io.FaultInjectingFileIo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrashConsistencyTest {

    private static final Path DIRECTORY = Path.of("data");
    private static final int BATCHES = 8;
    private static final int BATCH_SIZE = 3;

    private static StorageConfig logConfig() {
        return StorageConfig.of(DIRECTORY).withFsyncPolicy(FsyncPolicy.NEVER).withMaxSegmentBytes(1024);
    }

    private static StorageConfig stateConfig() {
        return StorageConfig.of(DIRECTORY).withFsyncPolicy(FsyncPolicy.ALWAYS);
    }

    private static List<List<LogEntry>> batches() {
        List<List<LogEntry>> batches = new ArrayList<>();
        long index = 1;
        for (int batch = 0; batch < BATCHES; batch++) {
            List<LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < BATCH_SIZE; i++) {
                entries.add(LogEntry.normal(1, index, Bytes.ofUtf8("value-" + index)));
                index++;
            }
            batches.add(List.copyOf(entries));
        }
        return List.copyOf(batches);
    }

    private static List<LogEntry> intendedLog() {
        return batches().stream().flatMap(List::stream).toList();
    }

    private static List<HardState> intendedStates() {
        return IntStream.rangeClosed(1, 12)
                .mapToObj(term -> new HardState(term, NodeId.of("n" + (term % 3 + 1)), term / 2))
                .toList();
    }

    private static long appendUntilCrash(FaultInjectingFileIo io) {
        long acknowledged = 0;
        try {
            StorageDirectory storage = StorageDirectory.open(io, DIRECTORY);
            try (SegmentedLogStore store = SegmentedLogStore.open(storage, logConfig())) {
                for (List<LogEntry> batch : batches()) {
                    store.append(batch);
                    store.sync();
                    acknowledged = batch.getLast().index();
                }
            }
        } catch (FaultInjectingFileIo.SimulatedCrash | FaultInjectingFileIo.OutOfSpace expected) {
            return acknowledged;
        }
        return acknowledged;
    }

    private static void assertRecoversToAValidPrefix(FaultInjectingFileIo io, long acknowledged, long faultPoint) {
        io.clearFaults();
        List<LogEntry> intended = intendedLog();

        try (SegmentedLogStore recovered = SegmentedLogStore.open(StorageDirectory.open(io, DIRECTORY), logConfig())) {
            assertThat(recovered.lastIndex())
                    .as("fault at write %d lost an entry that had already been synced", faultPoint)
                    .isGreaterThanOrEqualTo(acknowledged);
            assertThat(recovered.lastIndex())
                    .as("fault at write %d produced entries that were never written", faultPoint)
                    .isLessThanOrEqualTo(intended.size());

            for (long index = 1; index <= recovered.lastIndex(); index++) {
                assertThat(recovered.entryAt(index))
                        .as("fault at write %d corrupted index %d", faultPoint, index)
                        .contains(intended.get((int) index - 1));
            }
        }
    }

    private static long totalWritesForLog() {
        FaultInjectingFileIo io = new FaultInjectingFileIo();
        appendUntilCrash(io);
        return io.writeCount();
    }

    @Test
    @DisplayName("crashing at every single physical write still recovers a valid prefix")
    void everyCrashPointRecoversToAValidPrefix() {
        long total = totalWritesForLog();
        assertThat(total).isGreaterThan(20);

        for (long crashPoint = 1; crashPoint <= total; crashPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.crashAtWrite(crashPoint);
            long acknowledged = appendUntilCrash(io);
            assertRecoversToAValidPrefix(io, acknowledged, crashPoint);
        }
    }

    @Test
    @DisplayName("a write that only half lands is discarded, not misread")
    void everyTornWriteRecoversToAValidPrefix() {
        long total = totalWritesForLog();

        for (long tearPoint = 1; tearPoint <= total; tearPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.tearAtWrite(tearPoint);
            long acknowledged = appendUntilCrash(io);
            assertRecoversToAValidPrefix(io, acknowledged, tearPoint);
        }
    }

    @Test
    @DisplayName("recovering twice changes nothing, so a crash loop cannot eat the log")
    void recoveryIsIdempotent() {
        long total = totalWritesForLog();

        for (long crashPoint = 1; crashPoint <= total; crashPoint += 3) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.crashAtWrite(crashPoint);
            appendUntilCrash(io);
            io.clearFaults();

            long firstRecovery;
            try (SegmentedLogStore store = SegmentedLogStore.open(StorageDirectory.open(io, DIRECTORY), logConfig())) {
                firstRecovery = store.lastIndex();
            }
            try (SegmentedLogStore store = SegmentedLogStore.open(StorageDirectory.open(io, DIRECTORY), logConfig())) {
                assertThat(store.lastIndex())
                        .as("second recovery after a crash at write %d changed the log", crashPoint)
                        .isEqualTo(firstRecovery);
                assertThat(store.discardedBytesOnRecovery())
                        .as("the first recovery should already have truncated the tail")
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("a full disk fails loudly and leaves everything already written readable")
    void aFullDiskLeavesTheLogReadable() {
        long total = totalWritesForLog();

        for (long failPoint = 1; failPoint <= total; failPoint += 2) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.failAtWrite(failPoint);
            long acknowledged = appendUntilCrash(io);
            assertRecoversToAValidPrefix(io, acknowledged, failPoint);
        }
    }

    private static HardState persistUntilCrash(FaultInjectingFileIo io) {
        HardState acknowledged = HardState.INITIAL;
        try {
            StorageDirectory storage = StorageDirectory.open(io, DIRECTORY);
            try (FileStableStore store = FileStableStore.open(storage, stateConfig())) {
                for (HardState state : intendedStates()) {
                    store.persist(state);
                    acknowledged = state;
                }
            }
        } catch (FaultInjectingFileIo.SimulatedCrash expected) {
            return acknowledged;
        }
        return acknowledged;
    }

    @Test
    @DisplayName("a vote that was acknowledged is never forgotten, whatever write the crash lands on")
    void theHardStateSurvivesACrashAtEveryWrite() {
        FaultInjectingFileIo counting = new FaultInjectingFileIo();
        persistUntilCrash(counting);
        long total = counting.writeCount();
        assertThat(total).isGreaterThan(10);

        for (long crashPoint = 1; crashPoint <= total; crashPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.crashAtWrite(crashPoint);
            HardState acknowledged = persistUntilCrash(io);

            io.clearFaults();
            try (FileStableStore recovered =
                    FileStableStore.open(StorageDirectory.open(io, DIRECTORY), stateConfig())) {
                Optional<HardState> state = recovered.load();
                if (acknowledged.equals(HardState.INITIAL)) {
                    continue;
                }
                assertThat(state)
                        .as("crash at write %d lost the vote that had already been acknowledged", crashPoint)
                        .contains(acknowledged);
            }
        }
    }

    @Test
    @DisplayName("a torn hard-state write falls back to the previous slot rather than to nonsense")
    void aTornHardStateWriteFallsBack() {
        FaultInjectingFileIo counting = new FaultInjectingFileIo();
        persistUntilCrash(counting);
        long total = counting.writeCount();

        for (long tearPoint = 1; tearPoint <= total; tearPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            io.tearAtWrite(tearPoint);
            persistUntilCrash(io);

            io.clearFaults();
            try (FileStableStore recovered =
                    FileStableStore.open(StorageDirectory.open(io, DIRECTORY), stateConfig())) {
                Optional<HardState> state = recovered.load();
                if (state.isPresent()) {
                    assertThat(intendedStates())
                            .as("a torn write at %d produced a hard state nobody ever asked for", tearPoint)
                            .contains(state.get());
                }
            }
        }
    }
}
