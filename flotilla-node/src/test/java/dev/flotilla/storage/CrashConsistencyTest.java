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

    private static final long TRUNCATE_FROM = 5;

    private static StorageConfig durableLogConfig() {
        return StorageConfig.of(DIRECTORY).withFsyncPolicy(FsyncPolicy.ALWAYS).withMaxSegmentBytes(1024);
    }

    private static List<LogEntry> largeEntries() {
        List<LogEntry> entries = new ArrayList<>();
        for (long index = 1; index <= BATCHES * BATCH_SIZE; index++) {
            entries.add(LogEntry.normal(1, index, Bytes.ofUtf8("value-" + index + "-" + "x".repeat(300))));
        }
        return List.copyOf(entries);
    }

    private static SegmentedLogStore openDurable(FaultInjectingFileIo io) {
        return SegmentedLogStore.open(StorageDirectory.open(io, DIRECTORY), durableLogConfig());
    }

    private static boolean truncateCrashingAt(FaultInjectingFileIo io, long crashPoint) {
        try (SegmentedLogStore store = openDurable(io)) {
            store.append(largeEntries());
            store.sync();
            assertThat(store.segmentCount())
                    .as("the truncation must cut across several segments to mean anything")
                    .isGreaterThan(3);
            io.resetWriteCount();
            io.crashAtWrite(crashPoint);
            store.truncateSuffixFrom(TRUNCATE_FROM);
            return false;
        } catch (FaultInjectingFileIo.SimulatedCrash crashed) {
            return true;
        }
    }

    @Test
    @DisplayName(
            "a crash at any durability operation inside a truncation across segments still opens to a valid prefix")
    void everyCrashDuringTruncationRecovers() {
        FaultInjectingFileIo counting = new FaultInjectingFileIo();
        assertThat(truncateCrashingAt(counting, -1)).isFalse();
        long total = counting.writeCount();
        assertThat(total)
                .as("a truncation across segments involves several durability operations")
                .isGreaterThan(3);

        List<LogEntry> intended = largeEntries();
        for (long crashPoint = 1; crashPoint <= total; crashPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            assertThat(truncateCrashingAt(io, crashPoint)).isTrue();
            io.clearFaults();

            try (SegmentedLogStore recovered = openDurable(io)) {
                assertThat(recovered.lastIndex())
                        .as("crash at operation %d of %d lost entries below the truncation point", crashPoint, total)
                        .isGreaterThanOrEqualTo(TRUNCATE_FROM - 1);
                for (long index = 1; index <= recovered.lastIndex(); index++) {
                    assertThat(recovered.entryAt(index))
                            .as("crash at operation %d of %d corrupted index %d", crashPoint, total, index)
                            .contains(intended.get((int) index - 1));
                }
            }
        }
    }

    private static final long COMPACT_TO = 14;
    private static final long RESET_TO = 10;
    private static final long RESET_TERM = 5;

    private static boolean compactCrashingAt(FaultInjectingFileIo io, long crashPoint) {
        try (SegmentedLogStore store = openDurable(io)) {
            store.append(largeEntries());
            store.sync();
            io.resetWriteCount();
            io.crashAtWrite(crashPoint);
            store.compactTo(COMPACT_TO);
            return false;
        } catch (FaultInjectingFileIo.SimulatedCrash crashed) {
            return true;
        }
    }

    @Test
    @DisplayName(
            "a crash at any durability operation inside a compaction leaves either the old log or the compacted one")
    void everyCrashDuringCompactionRecovers() {
        FaultInjectingFileIo counting = new FaultInjectingFileIo();
        assertThat(compactCrashingAt(counting, -1)).isFalse();
        long total = counting.writeCount();
        assertThat(total).isGreaterThan(2);

        List<LogEntry> intended = largeEntries();
        for (long crashPoint = 1; crashPoint <= total; crashPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            assertThat(compactCrashingAt(io, crashPoint)).isTrue();
            io.clearFaults();

            try (SegmentedLogStore recovered = openDurable(io)) {
                long first = recovered.firstIndex();
                assertThat(first)
                        .as("crash at operation %d of %d left a base that was never chosen", crashPoint, total)
                        .isIn(1L, COMPACT_TO + 1);
                assertThat(recovered.lastIndex())
                        .as(
                                "crash at operation %d of %d lost entries that were synced before compacting",
                                crashPoint, total)
                        .isEqualTo(intended.size());
                if (first == COMPACT_TO + 1) {
                    assertThat(recovered.termAt(COMPACT_TO))
                            .isEqualTo(intended.get((int) COMPACT_TO - 1).term());
                }
                for (long index = first; index <= recovered.lastIndex(); index++) {
                    assertThat(recovered.entryAt(index))
                            .as("crash at operation %d of %d corrupted index %d", crashPoint, total, index)
                            .contains(intended.get((int) index - 1));
                }
            }
        }
    }

    private static List<LogEntry> entriesAfterReset() {
        List<LogEntry> entries = new ArrayList<>();
        for (long index = RESET_TO + 1; index <= RESET_TO + 3; index++) {
            entries.add(LogEntry.normal(RESET_TERM, index, Bytes.ofUtf8("after-reset-" + index)));
        }
        return List.copyOf(entries);
    }

    private record ResetOutcome(boolean resetReturned, long acknowledged) {}

    private static ResetOutcome resetCrashingAt(FaultInjectingFileIo io, long crashPoint) {
        boolean resetReturned = false;
        long acknowledged = 0;
        try (SegmentedLogStore store = openDurable(io)) {
            store.append(largeEntries());
            store.sync();
            io.resetWriteCount();
            io.crashAtWrite(crashPoint);
            store.resetTo(RESET_TO, RESET_TERM);
            resetReturned = true;
            store.append(entriesAfterReset());
            store.sync();
            acknowledged = RESET_TO + 3;
        } catch (FaultInjectingFileIo.SimulatedCrash crashed) {
            return new ResetOutcome(resetReturned, acknowledged);
        }
        return new ResetOutcome(resetReturned, acknowledged);
    }

    @Test
    @DisplayName("a crash at any durability operation during a reset never brings the discarded log back")
    void everyCrashDuringResetNeverResurrectsTheOldLog() {
        FaultInjectingFileIo counting = new FaultInjectingFileIo();
        assertThat(resetCrashingAt(counting, -1).acknowledged()).isEqualTo(RESET_TO + 3);
        long total = counting.writeCount();
        assertThat(total).isGreaterThan(4);

        List<LogEntry> oldLog = largeEntries();
        List<LogEntry> newLog = entriesAfterReset();
        for (long crashPoint = 1; crashPoint <= total; crashPoint++) {
            FaultInjectingFileIo io = new FaultInjectingFileIo();
            ResetOutcome outcome = resetCrashingAt(io, crashPoint);
            io.clearFaults();

            try (SegmentedLogStore recovered = openDurable(io)) {
                if (recovered.firstIndex() == 1) {
                    assertThat(outcome.resetReturned())
                            .as(
                                    "crash at operation %d of %d: the reset had returned, yet the old log came back",
                                    crashPoint, total)
                            .isFalse();
                    for (long index = 1; index <= recovered.lastIndex(); index++) {
                        assertThat(recovered.entryAt(index))
                                .as("crash at operation %d of %d corrupted old index %d", crashPoint, total, index)
                                .contains(oldLog.get((int) index - 1));
                    }
                    continue;
                }

                assertThat(recovered.firstIndex())
                        .as("crash at operation %d of %d produced a base that was never chosen", crashPoint, total)
                        .isEqualTo(RESET_TO + 1);
                assertThat(recovered.termAt(RESET_TO)).isEqualTo(RESET_TERM);
                assertThat(recovered.lastIndex())
                        .as("crash at operation %d of %d lost an acknowledged entry after the reset", crashPoint, total)
                        .isGreaterThanOrEqualTo(Math.max(RESET_TO, outcome.acknowledged()));
                for (long index = RESET_TO + 1; index <= recovered.lastIndex(); index++) {
                    assertThat(recovered.entryAt(index))
                            .as(
                                    "crash at operation %d of %d resurrected or corrupted index %d",
                                    crashPoint, total, index)
                            .contains(newLog.get((int) (index - RESET_TO - 1)));
                }
            }
        }
    }
}
