/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class KvStateMachine implements StateMachine {

    public static final long DEFAULT_SESSION_TIMEOUT_ENTRIES = 10_000;

    private final LayeredMap<Bytes, Bytes> data = new LayeredMap<>();
    private final SessionRegistry sessions = new SessionRegistry();
    private final long sessionTimeoutEntries;

    private long lastAppliedIndex;
    private long expiredSessions;

    public KvStateMachine() {
        this(DEFAULT_SESSION_TIMEOUT_ENTRIES);
    }

    public KvStateMachine(long sessionTimeoutEntries) {
        if (sessionTimeoutEntries < 1) {
            throw new IllegalArgumentException(
                    "sessionTimeoutEntries must be at least 1, was " + sessionTimeoutEntries);
        }
        this.sessionTimeoutEntries = sessionTimeoutEntries;
    }

    @Override
    public Bytes apply(long index, Bytes payload) {
        Objects.requireNonNull(payload, "payload");
        KvRequest request = CommandCodec.decodeRequest(payload);
        expiredSessions += sessions.expire(index, sessionTimeoutEntries);
        Bytes response = CommandCodec.encode(dispatch(index, request));
        lastAppliedIndex = index;
        return response;
    }

    @Override
    public void validate(Bytes command) {
        CommandCodec.decodeRequest(Objects.requireNonNull(command, "command"));
    }

    public KvResponse execute(Command command) {
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case Command.Put put -> KvResponse.of(data.put(put.key(), put.value()));
            case Command.Delete delete -> KvResponse.of(data.remove(delete.key()));
            case Command.CompareAndSwap swap -> new KvResponse.Swapped(compareAndSwap(swap));
            case Command.Get get -> KvResponse.of(data.get(get.key()));
            case Command.Scan scan -> new KvResponse.Entries(scan(scan));
        };
    }

    @Override
    public Bytes snapshot() {
        return KvSnapshotCodec.encode(lastAppliedIndex, data.view(), sessions.all());
    }

    @Override
    public StateCapture capture() {
        LayeredMap.Frozen<Bytes, Bytes> frozen = data.freeze();
        NavigableMap<Long, Session> frozenSessions = new TreeMap<>(sessions.all());
        long index = lastAppliedIndex;
        return new StateCapture() {
            @Override
            public Bytes serialize() {
                return KvSnapshotCodec.encode(index, frozen.contents(), frozenSessions);
            }

            @Override
            public void close() {
                frozen.release();
            }
        };
    }

    @Override
    public void restore(Bytes snapshot) {
        KvSnapshotCodec.Snapshot restored = KvSnapshotCodec.decode(snapshot);
        data.replaceWith(restored.data());
        sessions.replaceWith(restored.sessions());
        lastAppliedIndex = restored.lastAppliedIndex();
    }

    @Override
    public long lastAppliedIndex() {
        return lastAppliedIndex;
    }

    public Optional<Bytes> get(Bytes key) {
        return Optional.ofNullable(data.get(Objects.requireNonNull(key, "key")));
    }

    public int size() {
        return data.size();
    }

    public int openSessions() {
        return sessions.size();
    }

    public long expiredSessions() {
        return expiredSessions;
    }

    public List<KeyValue> entries() {
        List<KeyValue> entries = new ArrayList<>(data.size());
        data.forEach((key, value) -> entries.add(new KeyValue(key, value)));
        return entries;
    }

    private KvResponse dispatch(long index, KvRequest request) {
        return switch (request) {
            case KvRequest.Register ignored -> new KvResponse.Opened(sessions.open(index));
            case KvRequest.Invoke invoke -> invoke.isAnonymous() ? execute(invoke.command()) : sessioned(index, invoke);
        };
    }

    private KvResponse sessioned(long index, KvRequest.Invoke invoke) {
        Optional<Session> known = sessions.find(invoke.clientId());
        if (known.isEmpty()) {
            return new KvResponse.Rejected(KvResponse.Reason.UNKNOWN_SESSION);
        }
        Session session = known.get();
        if (invoke.sequence() == session.lastSequence()) {
            sessions.touched(invoke.clientId(), index);
            return CommandCodec.decodeResponse(session.lastResponse());
        }
        if (invoke.sequence() < session.lastSequence()) {
            sessions.touched(invoke.clientId(), index);
            return new KvResponse.Rejected(KvResponse.Reason.STALE_SEQUENCE);
        }
        KvResponse response = execute(invoke.command());
        sessions.answered(invoke.clientId(), invoke.sequence(), CommandCodec.encode(response), index);
        return response;
    }

    private boolean compareAndSwap(Command.CompareAndSwap swap) {
        @Nullable Bytes current = data.get(swap.key());
        if (!Objects.equals(current, swap.expected())) {
            return false;
        }
        if (swap.value() == null) {
            data.remove(swap.key());
        } else {
            data.put(swap.key(), swap.value());
        }
        return true;
    }

    private List<KeyValue> scan(Command.Scan scan) {
        if (scan.limit() == 0 || scan.fromInclusive().compareTo(scan.toExclusive()) >= 0) {
            return List.of();
        }
        List<KeyValue> found = new ArrayList<>();
        data.forEachInRange(scan.fromInclusive(), scan.toExclusive(), (key, value) -> {
            found.add(new KeyValue(key, value));
            return found.size() < scan.limit();
        });
        return found;
    }
}
