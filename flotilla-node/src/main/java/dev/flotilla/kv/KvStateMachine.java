/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class KvStateMachine implements StateMachine {

    public static final long DEFAULT_SESSION_TIMEOUT_ENTRIES = 10_000;

    private final NavigableMap<Bytes, Bytes> data = new TreeMap<>();
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
        return KvSnapshotCodec.encode(lastAppliedIndex, data, sessions.all());
    }

    @Override
    public void restore(Bytes snapshot) {
        KvSnapshotCodec.Snapshot restored = KvSnapshotCodec.decode(snapshot);
        data.clear();
        data.putAll(restored.data());
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
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            entries.add(new KeyValue(entry.getKey(), entry.getValue()));
        }
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
        for (Map.Entry<Bytes, Bytes> entry : data.subMap(scan.fromInclusive(), true, scan.toExclusive(), false)
                .entrySet()) {
            if (found.size() == scan.limit()) {
                break;
            }
            found.add(new KeyValue(entry.getKey(), entry.getValue()));
        }
        return found;
    }
}
