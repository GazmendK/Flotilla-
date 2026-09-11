/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

final class SessionRegistry {

    private final NavigableMap<Long, Session> sessions = new TreeMap<>();

    long open(long index) {
        sessions.put(index, Session.opened(index));
        return index;
    }

    Optional<Session> find(long clientId) {
        return Optional.ofNullable(sessions.get(clientId));
    }

    void answered(long clientId, long sequence, Bytes response, long index) {
        Session session = sessions.get(clientId);
        if (session != null) {
            sessions.put(clientId, session.answered(sequence, response, index));
        }
    }

    void touched(long clientId, long index) {
        Session session = sessions.get(clientId);
        if (session != null) {
            sessions.put(clientId, session.touched(index));
        }
    }

    int expire(long currentIndex, long timeoutEntries) {
        int removed = 0;
        Iterator<Map.Entry<Long, Session>> candidates = sessions.entrySet().iterator();
        while (candidates.hasNext()) {
            Map.Entry<Long, Session> candidate = candidates.next();
            if (currentIndex - candidate.getValue().lastActiveIndex() > timeoutEntries) {
                candidates.remove();
                removed++;
            }
        }
        return removed;
    }

    NavigableMap<Long, Session> all() {
        return sessions;
    }

    void replaceWith(NavigableMap<Long, Session> restored) {
        Objects.requireNonNull(restored, "restored");
        sessions.clear();
        sessions.putAll(restored);
    }

    int size() {
        return sessions.size();
    }
}
