/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.EntryType;
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
import dev.flotilla.wire.v1.DeliverRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public final class MessageCodec {

    private MessageCodec() {}

    public static DeliverRequest encode(RaftMessage message) {
        Objects.requireNonNull(message, "message");
        DeliverRequest.Builder envelope = DeliverRequest.newBuilder()
                .setFrom(message.from().value())
                .setTo(message.to().value())
                .setTerm(message.term());
        switch (message) {
            case AppendEntriesRequest request -> {
                dev.flotilla.wire.v1.AppendEntriesRequest.Builder body =
                        dev.flotilla.wire.v1.AppendEntriesRequest.newBuilder()
                                .setPrevLogIndex(request.prevLogIndex())
                                .setPrevLogTerm(request.prevLogTerm())
                                .setLeaderCommit(request.leaderCommit());
                for (LogEntry entry : request.entries()) {
                    body.addEntries(encode(entry));
                }
                envelope.setAppendEntriesRequest(body);
            }
            case AppendEntriesResponse response ->
                envelope.setAppendEntriesResponse(dev.flotilla.wire.v1.AppendEntriesResponse.newBuilder()
                        .setSuccess(response.success())
                        .setMatchIndex(response.matchIndex())
                        .setConflictIndex(response.conflictIndex())
                        .setConflictTerm(response.conflictTerm()));
            case RequestVoteRequest request ->
                envelope.setRequestVoteRequest(dev.flotilla.wire.v1.RequestVoteRequest.newBuilder()
                        .setLastLogIndex(request.lastLogIndex())
                        .setLastLogTerm(request.lastLogTerm())
                        .setPreVote(request.preVote()));
            case RequestVoteResponse response ->
                envelope.setRequestVoteResponse(dev.flotilla.wire.v1.RequestVoteResponse.newBuilder()
                        .setVoteGranted(response.voteGranted())
                        .setPreVote(response.preVote()));
            case InstallSnapshotRequest request -> envelope.setInstallSnapshotRequest(toWire(request.snapshot()));
            case InstallSnapshotResponse response ->
                envelope.setInstallSnapshotResponse(dev.flotilla.wire.v1.InstallSnapshotResponse.newBuilder()
                        .setMatchIndex(response.matchIndex())
                        .setInstalled(response.installed()));
            case ReadIndexRequest request ->
                envelope.setReadIndexRequest(
                        dev.flotilla.wire.v1.ReadIndexRequest.newBuilder().setRequestId(toWire(request.requestId())));
            case ReadIndexResponse response ->
                envelope.setReadIndexResponse(dev.flotilla.wire.v1.ReadIndexResponse.newBuilder()
                        .setRequestId(toWire(response.requestId()))
                        .setReadIndex(response.readIndex()));
            case TimeoutNowRequest ignored ->
                envelope.setTimeoutNowRequest(dev.flotilla.wire.v1.TimeoutNowRequest.getDefaultInstance());
        }
        return envelope.build();
    }

    public static RaftMessage decode(DeliverRequest envelope) {
        Objects.requireNonNull(envelope, "envelope");
        try {
            NodeId from = NodeId.of(envelope.getFrom());
            NodeId to = NodeId.of(envelope.getTo());
            long term = envelope.getTerm();
            return switch (envelope.getBodyCase()) {
                case APPEND_ENTRIES_REQUEST -> {
                    dev.flotilla.wire.v1.AppendEntriesRequest body = envelope.getAppendEntriesRequest();
                    List<LogEntry> entries = new ArrayList<>(body.getEntriesCount());
                    for (dev.flotilla.wire.v1.LogEntry entry : body.getEntriesList()) {
                        entries.add(decode(entry));
                    }
                    yield new AppendEntriesRequest(
                            from,
                            to,
                            term,
                            body.getPrevLogIndex(),
                            body.getPrevLogTerm(),
                            entries,
                            body.getLeaderCommit());
                }
                case APPEND_ENTRIES_RESPONSE -> {
                    dev.flotilla.wire.v1.AppendEntriesResponse body = envelope.getAppendEntriesResponse();
                    yield new AppendEntriesResponse(
                            from,
                            to,
                            term,
                            body.getSuccess(),
                            body.getMatchIndex(),
                            body.getConflictIndex(),
                            body.getConflictTerm());
                }
                case REQUEST_VOTE_REQUEST -> {
                    dev.flotilla.wire.v1.RequestVoteRequest body = envelope.getRequestVoteRequest();
                    yield new RequestVoteRequest(
                            from, to, term, body.getLastLogIndex(), body.getLastLogTerm(), body.getPreVote());
                }
                case REQUEST_VOTE_RESPONSE -> {
                    dev.flotilla.wire.v1.RequestVoteResponse body = envelope.getRequestVoteResponse();
                    yield new RequestVoteResponse(from, to, term, body.getVoteGranted(), body.getPreVote());
                }
                case INSTALL_SNAPSHOT_REQUEST ->
                    new InstallSnapshotRequest(from, to, term, fromWire(envelope.getInstallSnapshotRequest()));
                case INSTALL_SNAPSHOT_RESPONSE -> {
                    dev.flotilla.wire.v1.InstallSnapshotResponse body = envelope.getInstallSnapshotResponse();
                    yield new InstallSnapshotResponse(from, to, term, body.getMatchIndex(), body.getInstalled());
                }
                case READ_INDEX_REQUEST ->
                    new ReadIndexRequest(
                            from,
                            to,
                            term,
                            fromWire(envelope.getReadIndexRequest().getRequestId()));
                case READ_INDEX_RESPONSE -> {
                    dev.flotilla.wire.v1.ReadIndexResponse body = envelope.getReadIndexResponse();
                    yield new ReadIndexResponse(from, to, term, fromWire(body.getRequestId()), body.getReadIndex());
                }
                case TIMEOUT_NOW_REQUEST -> new TimeoutNowRequest(from, to, term);
                case BODY_NOT_SET -> throw new WireFormatException("The envelope from " + from + " carries no message");
            };
        } catch (IllegalArgumentException invalid) {
            throw new WireFormatException(
                    "The envelope does not describe a valid message: " + invalid.getMessage(), invalid);
        }
    }

    private static dev.flotilla.wire.v1.LogEntry encode(LogEntry entry) {
        return dev.flotilla.wire.v1.LogEntry.newBuilder()
                .setTerm(entry.term())
                .setIndex(entry.index())
                .setType(encode(entry.type()))
                .setData(toWire(entry.data()))
                .build();
    }

    private static LogEntry decode(dev.flotilla.wire.v1.LogEntry entry) {
        return new LogEntry(entry.getTerm(), entry.getIndex(), decode(entry.getType()), fromWire(entry.getData()));
    }

    private static dev.flotilla.wire.v1.EntryType encode(EntryType type) {
        return switch (type) {
            case NORMAL -> dev.flotilla.wire.v1.EntryType.ENTRY_TYPE_NORMAL;
            case NOOP -> dev.flotilla.wire.v1.EntryType.ENTRY_TYPE_NOOP;
            case CONFIGURATION -> dev.flotilla.wire.v1.EntryType.ENTRY_TYPE_CONFIGURATION;
        };
    }

    private static EntryType decode(dev.flotilla.wire.v1.EntryType type) {
        return switch (type) {
            case ENTRY_TYPE_NORMAL -> EntryType.NORMAL;
            case ENTRY_TYPE_NOOP -> EntryType.NOOP;
            case ENTRY_TYPE_CONFIGURATION -> EntryType.CONFIGURATION;
            case ENTRY_TYPE_UNSPECIFIED, UNRECOGNIZED -> throw new WireFormatException("Unknown entry type " + type);
        };
    }

    private static dev.flotilla.wire.v1.InstallSnapshotRequest.Builder toWire(Snapshot snapshot) {
        dev.flotilla.wire.v1.InstallSnapshotRequest.Builder body =
                dev.flotilla.wire.v1.InstallSnapshotRequest.newBuilder()
                        .setLastIncludedIndex(snapshot.lastIncludedIndex())
                        .setLastIncludedTerm(snapshot.lastIncludedTerm())
                        .setOffset(0)
                        .setDone(true)
                        .setData(toWire(snapshot.data()));
        for (NodeId voter : snapshot.cluster().voters()) {
            body.addVoters(voter.value());
        }
        for (NodeId learner : snapshot.cluster().learners()) {
            body.addLearners(learner.value());
        }
        return body;
    }

    private static Snapshot fromWire(dev.flotilla.wire.v1.InstallSnapshotRequest body) {
        SortedSet<NodeId> voters = new TreeSet<>();
        body.getVotersList().forEach(voter -> voters.add(NodeId.of(voter)));
        SortedSet<NodeId> learners = new TreeSet<>();
        body.getLearnersList().forEach(learner -> learners.add(NodeId.of(learner)));
        return new Snapshot(
                body.getLastIncludedIndex(),
                body.getLastIncludedTerm(),
                new ClusterConfig(voters, learners),
                fromWire(body.getData()));
    }

    private static ByteString toWire(Bytes value) {
        return UnsafeByteOperations.unsafeWrap(value.toByteArray());
    }

    private static Bytes fromWire(ByteString value) {
        return Bytes.wrap(value.toByteArray());
    }
}
