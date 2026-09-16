/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.AppendEntriesResponse;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.core.message.InstallSnapshotResponse;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.ReadIndexRequest;
import dev.flotilla.core.message.ReadIndexResponse;
import dev.flotilla.core.message.RequestVoteRequest;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.message.TimeoutNowRequest;
import dev.flotilla.testing.SeededInputs;
import dev.flotilla.wire.v1.DeliverRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    private static final NodeId A = NodeId.of("a");
    private static final NodeId B = NodeId.of("b");

    private static List<RaftMessage> everyMessageShape() {
        return List.of(
                new AppendEntriesRequest(
                        A,
                        B,
                        3,
                        7,
                        2,
                        List.of(
                                LogEntry.normal(3, 8, Bytes.ofUtf8("x")),
                                LogEntry.noOp(3, 9),
                                LogEntry.configuration(3, 10, Bytes.ofUtf8("cfg"))),
                        6),
                new AppendEntriesRequest(A, B, 1, 0, 0, List.of(), 0),
                new AppendEntriesResponse(B, A, 3, false, 0, 5, 2),
                new AppendEntriesResponse(B, A, 3, true, 10, 0, 0),
                new RequestVoteRequest(A, B, 4, 10, 3, true),
                new RequestVoteResponse(B, A, 4, true, false),
                new InstallSnapshotRequest(
                        A, B, 5, new Snapshot(100, 4, ClusterConfig.ofVoters(A, B), Bytes.ofUtf8("state"))),
                new InstallSnapshotRequest(
                        A,
                        B,
                        5,
                        new Snapshot(
                                100,
                                4,
                                new ClusterConfig(new TreeSet<>(Set.of(A)), new TreeSet<>(Set.of(B))),
                                Bytes.EMPTY)),
                new InstallSnapshotResponse(B, A, 5, 5, true),
                new ReadIndexRequest(A, B, 5, Bytes.ofUtf8("r1")),
                new ReadIndexResponse(B, A, 5, Bytes.ofUtf8("r1"), 42),
                new TimeoutNowRequest(A, B, 6));
    }

    private static RaftMessage throughTheWire(RaftMessage message) throws Exception {
        byte[] bytes = MessageCodec.encode(message).toByteArray();
        return MessageCodec.decode(DeliverRequest.parseFrom(bytes));
    }

    @Test
    void everyMessageShapeSurvivesTheActualByteEncoding() throws Exception {
        for (RaftMessage message : everyMessageShape()) {
            assertThat(throughTheWire(message)).as("round trip of %s", message).isEqualTo(message);
        }
    }

    @Test
    @DisplayName("every message type the core can produce has a wire form, so adding one fails here first")
    void everyMessageTypeIsCovered() {
        Set<Class<?>> covered =
                everyMessageShape().stream().map(Object::getClass).collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrder(RaftMessage.class.getPermittedSubclasses());
    }

    @Test
    void anEnvelopeWithoutAMessageIsRefused() {
        DeliverRequest empty =
                DeliverRequest.newBuilder().setFrom("a").setTo("b").setTerm(1).build();

        assertThatThrownBy(() -> MessageCodec.decode(empty))
                .isInstanceOf(WireFormatException.class)
                .hasMessageContaining("carries no message");
    }

    @Test
    void anUnknownEntryTypeIsRefused() {
        DeliverRequest request = DeliverRequest.newBuilder()
                .setFrom("a")
                .setTo("b")
                .setTerm(1)
                .setAppendEntriesRequest(dev.flotilla.wire.v1.AppendEntriesRequest.newBuilder()
                        .addEntries(dev.flotilla.wire.v1.LogEntry.newBuilder()
                                .setTerm(1)
                                .setIndex(1)
                                .setType(dev.flotilla.wire.v1.EntryType.ENTRY_TYPE_UNSPECIFIED)))
                .build();

        assertThatThrownBy(() -> MessageCodec.decode(request))
                .isInstanceOf(WireFormatException.class)
                .hasMessageContaining("Unknown entry type");
    }

    @Test
    @DisplayName("a value the domain would reject is reported as a wire error, not as a crash")
    void invalidDomainValuesBecomeWireErrors() {
        DeliverRequest request = DeliverRequest.newBuilder()
                .setFrom("a")
                .setTo("b")
                .setTerm(1)
                .setAppendEntriesRequest(
                        dev.flotilla.wire.v1.AppendEntriesRequest.newBuilder().setPrevLogIndex(-1))
                .build();

        assertThatThrownBy(() -> MessageCodec.decode(request)).isInstanceOf(WireFormatException.class);
    }

    @Test
    void anEmptySenderIsRefused() {
        DeliverRequest request = DeliverRequest.newBuilder()
                .setFrom("")
                .setTo("b")
                .setTerm(1)
                .setTimeoutNowRequest(dev.flotilla.wire.v1.TimeoutNowRequest.getDefaultInstance())
                .build();

        assertThatThrownBy(() -> MessageCodec.decode(request)).isInstanceOf(WireFormatException.class);
    }

    @Test
    @DisplayName("an append of any shape comes back identical after real serialization")
    void appendRequestsRoundTripThroughBytes() throws Exception {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < 500; attempt++) {
            long term = SeededInputs.between(random, 1, 1_000_000);
            long prevIndex = SeededInputs.between(random, 0, 1_000_000);
            int count = random.nextInt(0, 21);
            List<LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entries.add(LogEntry.normal(term, prevIndex + 1 + i, Bytes.copyOf(SeededInputs.bytes(random, 64))));
            }
            AppendEntriesRequest original = new AppendEntriesRequest(A, B, term, prevIndex, term, entries, prevIndex);

            assertThat(throughTheWire(original))
                    .as("seed %d, attempt %d", SeededInputs.SEED, attempt)
                    .isEqualTo(original);
        }
    }
}
