/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.testing.SeededInputs;
import dev.flotilla.wire.v1.DeliverRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotChunkingTest {

    private static final NodeId A = NodeId.of("a");
    private static final NodeId B = NodeId.of("b");
    private static final ClusterConfig CLUSTER = ClusterConfig.ofVoters(A, B);
    private static final int CHUNK = 4096;
    private static final int LIMIT = 1 << 20;

    private static InstallSnapshotRequest snapshotOf(int payloadBytes) {
        SplittableRandom random = SeededInputs.random();
        byte[] payload = new byte[payloadBytes];
        random.nextBytes(payload);
        return new InstallSnapshotRequest(A, B, 7, new Snapshot(900, 5, CLUSTER, Bytes.wrap(payload)));
    }

    private static Optional<InstallSnapshotRequest> feed(SnapshotAssembler assembler, List<DeliverRequest> chunks) {
        Optional<InstallSnapshotRequest> assembled = Optional.empty();
        for (DeliverRequest chunk : chunks) {
            Optional<InstallSnapshotRequest> result = assembler.accept(chunk);
            if (result.isPresent()) {
                assembled = result;
            }
        }
        return assembled;
    }

    @Test
    @DisplayName("a snapshot larger than one chunk is split and comes back byte for byte")
    void aLargeSnapshotSurvivesBeingSplit() {
        InstallSnapshotRequest original = snapshotOf(10 * CHUNK + 17);

        List<DeliverRequest> chunks = SnapshotChunks.split(original, CHUNK);

        assertThat(chunks).hasSize(11);
        assertThat(feed(new SnapshotAssembler(LIMIT), chunks)).contains(original);
    }

    @Test
    @DisplayName("a snapshot that fits in one chunk is sent as one message, not wrapped in a transfer")
    void aSmallSnapshotIsNotSplit() {
        InstallSnapshotRequest original = snapshotOf(CHUNK / 2);

        List<DeliverRequest> chunks = SnapshotChunks.split(original, CHUNK);

        assertThat(chunks).hasSize(1);
        assertThat(MessageCodec.decode(chunks.getFirst())).isEqualTo(original);
    }

    @Test
    @DisplayName("chunks that arrive out of order still reassemble, because they carry their offsets")
    void chunksReassembleOutOfOrder() {
        InstallSnapshotRequest original = snapshotOf(8 * CHUNK);
        List<DeliverRequest> chunks = new ArrayList<>(SnapshotChunks.split(original, CHUNK));
        Collections.reverse(chunks);

        assertThat(feed(new SnapshotAssembler(LIMIT), chunks)).contains(original);
    }

    @Test
    @DisplayName("a duplicated chunk changes nothing, because the network is allowed to duplicate")
    void aDuplicatedChunkIsHarmless() {
        InstallSnapshotRequest original = snapshotOf(5 * CHUNK);
        List<DeliverRequest> chunks = new ArrayList<>(SnapshotChunks.split(original, CHUNK));
        chunks.add(1, chunks.get(1));

        assertThat(feed(new SnapshotAssembler(LIMIT), chunks)).contains(original);
    }

    @Test
    @DisplayName("a transfer with a hole in it never completes, so the leader has to send it again")
    void aMissingChunkNeverCompletes() {
        InstallSnapshotRequest original = snapshotOf(6 * CHUNK);
        List<DeliverRequest> chunks = new ArrayList<>(SnapshotChunks.split(original, CHUNK));
        chunks.remove(2);

        SnapshotAssembler assembler = new SnapshotAssembler(LIMIT);

        assertThat(feed(assembler, chunks)).isEmpty();
        assertThat(assembler.transfersInProgress())
                .as("an incomplete transfer is held until a newer one replaces it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a newer snapshot abandons a half received older one instead of mixing the two")
    void aNewerTransferSupersedesAnOlderOne() {
        InstallSnapshotRequest older = snapshotOf(6 * CHUNK);
        InstallSnapshotRequest newer = new InstallSnapshotRequest(
                A, B, 8, new Snapshot(1200, 6, CLUSTER, Bytes.ofUtf8("n".repeat(6 * CHUNK))));
        SnapshotAssembler assembler = new SnapshotAssembler(LIMIT);

        assertThat(feed(assembler, SnapshotChunks.split(older, CHUNK).subList(0, 3)))
                .isEmpty();
        Optional<InstallSnapshotRequest> assembled = feed(assembler, SnapshotChunks.split(newer, CHUNK));

        assertThat(assembled).contains(newer);
        assertThat(assembler.abandonedTransfers()).isEqualTo(1);
        assertThat(assembler.transfersInProgress()).isZero();
    }

    @Test
    @DisplayName("a transfer larger than the configured limit is refused rather than buffered")
    void anOversizedTransferIsRefused() {
        InstallSnapshotRequest original = snapshotOf(20 * CHUNK);
        List<DeliverRequest> chunks = SnapshotChunks.split(original, CHUNK);
        SnapshotAssembler assembler = new SnapshotAssembler(4 * CHUNK);

        assertThatThrownBy(() -> feed(assembler, chunks))
                .isInstanceOf(WireFormatException.class)
                .hasMessageContaining("exceeded the configured limit");
    }

    @Test
    @DisplayName("a single chunk is not a message on its own, and the codec says so instead of guessing")
    void aPartialChunkIsNotAMessage() {
        List<DeliverRequest> chunks = SnapshotChunks.split(snapshotOf(4 * CHUNK), CHUNK);

        assertThatThrownBy(() -> MessageCodec.decode(chunks.getFirst()))
                .isInstanceOf(WireFormatException.class)
                .hasMessageContaining("has to be reassembled");
    }
}
