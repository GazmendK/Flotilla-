/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.NodeId;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

public final class CallFailure extends RuntimeException {

    public enum Kind {
        NOT_LEADER,
        OVERLOADED,
        UNAVAILABLE,
        TIMED_OUT,
        INVALID,
        REJECTED
    }

    private final Kind kind;

    @Nullable
    private final NodeId leader;

    @Nullable
    private final InetSocketAddress leaderAddress;

    private CallFailure(Kind kind, String message, @Nullable NodeId leader, @Nullable InetSocketAddress leaderAddress) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.leader = leader;
        this.leaderAddress = leaderAddress;
    }

    public static CallFailure notLeader(@Nullable NodeId leader, @Nullable InetSocketAddress leaderAddress) {
        String message = leader == null
                ? "Not the leader, and no leader is known"
                : "Not the leader; the leader is " + leader + (leaderAddress == null ? "" : " at " + leaderAddress);
        return new CallFailure(Kind.NOT_LEADER, message, leader, leaderAddress);
    }

    public static CallFailure of(Kind kind, String message) {
        return new CallFailure(kind, message, null, null);
    }

    public static CallFailure from(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof CallFailure callFailure
                ? callFailure
                : of(Kind.UNAVAILABLE, String.valueOf(cause.getMessage()));
    }

    public Kind kind() {
        return kind;
    }

    public Optional<NodeId> leader() {
        return Optional.ofNullable(leader);
    }

    public Optional<InetSocketAddress> leaderAddress() {
        return Optional.ofNullable(leaderAddress);
    }
}
