/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.AdminEndpoint;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.transport.grpc.GrpcAdminEndpoint;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.random.RandomGenerator;

public final class FlotillaAdmin implements AutoCloseable {

    private final AdminEndpoint endpoint;
    private final List<InetSocketAddress> known;
    private final ClientConfig config;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    private InetSocketAddress target;
    private int cursor;

    public FlotillaAdmin(
            AdminEndpoint endpoint,
            List<InetSocketAddress> seeds,
            ClientConfig config,
            RandomGenerator random,
            Sleeper sleeper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.known = new ArrayList<>(Objects.requireNonNull(seeds, "seeds"));
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (known.isEmpty()) {
            throw new IllegalArgumentException("An admin client needs at least one node to start from");
        }
        this.target = known.getFirst();
    }

    public static FlotillaAdmin connect(List<InetSocketAddress> seeds, ClientConfig config) {
        return new FlotillaAdmin(
                new GrpcAdminEndpoint(), seeds, config, RandomGenerator.getDefault(), Sleeper.system());
    }

    public ClusterConfig addLearner(NodeId node) {
        return change(new ConfChange.AddLearner(node));
    }

    public ClusterConfig promote(NodeId learner) {
        return change(new ConfChange.Promote(learner));
    }

    public ClusterConfig remove(NodeId node) {
        return change(new ConfChange.Remove(node));
    }

    public ClusterConfig change(ConfChange change) {
        Objects.requireNonNull(change, "change");
        return onLeader(address -> endpoint.changeMembership(address, change, config.attemptDeadline()), change);
    }

    public NodeId transferLeadership(NodeId to) {
        Objects.requireNonNull(to, "to");
        return onLeader(
                address -> endpoint.transferLeadership(address, to, config.attemptDeadline()),
                "leadership transfer to " + to);
    }

    public ClusterStatus describe() {
        return onLeader(address -> endpoint.describeCluster(address, true, config.attemptDeadline()), "describe");
    }

    public ClusterStatus describe(InetSocketAddress node) {
        return await(endpoint.describeCluster(node, false, config.attemptDeadline()));
    }

    public CatchUpReport awaitCaughtUp(NodeId learner, Duration patience) {
        Objects.requireNonNull(learner, "learner");
        long deadline = System.nanoTime() + patience.toNanos();
        ClusterStatus status = describe();
        while (true) {
            if (status.catchUp().containsKey(learner)
                    && status.catchUp().get(learner).caughtUp()) {
                return new CatchUpReport(learner, true, status);
            }
            if (System.nanoTime() >= deadline) {
                return new CatchUpReport(learner, false, status);
            }
            sleeper.sleep(config.backoffBase());
            status = describe();
        }
    }

    public record CatchUpReport(NodeId learner, boolean caughtUp, ClusterStatus status) {}

    private <T> T onLeader(Function<InetSocketAddress, CompletableFuture<T>> call, Object what) {
        CallFailure last = null;
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            try {
                return await(call.apply(target));
            } catch (CallFailure failure) {
                last = failure;
                switch (failure.kind()) {
                    case NOT_LEADER -> follow(failure.leaderAddress());
                    case OVERLOADED -> {}
                    case UNAVAILABLE -> rotate();
                    case TIMED_OUT ->
                        throw new IndeterminateResultException(
                                "The " + what + " timed out at " + target
                                        + "; it may or may not have taken effect. Describe the cluster to find out.",
                                failure);
                    case INVALID, REJECTED ->
                        throw new FlotillaClientException(String.valueOf(failure.getMessage()), failure);
                }
            }
            sleeper.sleep(FlotillaClient.backoff(config, random, attempt));
        }
        throw new FlotillaClientException(
                "Could not reach the leader for the " + what + " after " + config.maxAttempts() + " attempts", last);
    }

    private void follow(Optional<InetSocketAddress> hint) {
        if (hint.isPresent() && !hint.get().equals(target)) {
            target = hint.get();
            if (!known.contains(target)) {
                known.add(target);
            }
            return;
        }
        rotate();
    }

    private void rotate() {
        cursor = (cursor + 1) % known.size();
        target = known.get(cursor);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException | CancellationException failure) {
            throw CallFailure.from(failure);
        }
    }

    @Override
    public void close() {
        endpoint.close();
    }
}
