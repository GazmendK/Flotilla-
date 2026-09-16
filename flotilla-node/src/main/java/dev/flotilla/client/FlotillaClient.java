/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import dev.flotilla.core.Bytes;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KeyValue;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvResponse;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClientEndpoint;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.ReadConsistency;
import dev.flotilla.transport.grpc.GrpcClientEndpoint;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

public final class FlotillaClient implements AutoCloseable {

    private final ClientEndpoint endpoint;
    private final List<InetSocketAddress> seeds;
    private final ClientConfig config;
    private final RandomGenerator random;
    private final Sleeper sleeper;
    private final HistoryRecorder history;
    private final long process;

    private InetSocketAddress target;
    private int cursor;
    private long clientId = KvRequest.ANONYMOUS;
    private long sequence;

    public FlotillaClient(
            ClientEndpoint endpoint,
            List<InetSocketAddress> seeds,
            ClientConfig config,
            RandomGenerator random,
            Sleeper sleeper,
            HistoryRecorder history) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.history = Objects.requireNonNull(history, "history");
        if (this.seeds.isEmpty()) {
            throw new IllegalArgumentException("A client needs at least one node to start from");
        }
        this.target = this.seeds.getFirst();
        this.process = history.newProcess();
    }

    public static FlotillaClient connect(List<InetSocketAddress> seeds, ClientConfig config) {
        return connect(seeds, config, new HistoryRecorder(System::nanoTime));
    }

    public static FlotillaClient connect(List<InetSocketAddress> seeds, ClientConfig config, HistoryRecorder history) {
        return new FlotillaClient(
                new GrpcClientEndpoint(config.maxMessageBytes()),
                seeds,
                config,
                RandomGenerator.getDefault(),
                Sleeper.system(),
                history);
    }

    public Optional<Bytes> get(Bytes key) {
        return get(key, ReadConsistency.LINEARIZABLE);
    }

    public Optional<Bytes> get(Bytes key, ReadConsistency consistency) {
        return value(query(new Command.Get(key), consistency));
    }

    public Optional<Bytes> put(Bytes key, Bytes value) {
        return value(execute(new Command.Put(key, value)));
    }

    public Optional<Bytes> delete(Bytes key) {
        return value(execute(new Command.Delete(key)));
    }

    public boolean compareAndSwap(Bytes key, @Nullable Bytes expected, @Nullable Bytes value) {
        KvResponse response = execute(new Command.CompareAndSwap(key, expected, value));
        if (response instanceof KvResponse.Swapped swapped) {
            return swapped.swapped();
        }
        throw unexpected(response);
    }

    public List<KeyValue> scan(Bytes fromInclusive, Bytes toExclusive, int limit) {
        return scan(fromInclusive, toExclusive, limit, ReadConsistency.LINEARIZABLE);
    }

    public List<KeyValue> scan(Bytes fromInclusive, Bytes toExclusive, int limit, ReadConsistency consistency) {
        KvResponse response = query(new Command.Scan(fromInclusive, toExclusive, limit), consistency);
        if (response instanceof KvResponse.Entries entries) {
            return entries.entries();
        }
        throw unexpected(response);
    }

    public synchronized KvResponse execute(Command command) {
        Objects.requireNonNull(command, "command");
        openSessionIfNeeded();
        long operation = history.invoke(process, command);
        long attemptSequence = ++sequence;
        boolean mayHaveExecuted = false;
        @Nullable CallFailure last = null;

        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            Executed executed;
            try {
                executed = call(CommandCodec.encode(KvRequest.of(clientId, attemptSequence, command)));
            } catch (CallFailure failure) {
                last = failure;
                if (failure.kind() == CallFailure.Kind.INVALID) {
                    history.fail(operation, process, command, null);
                    throw failure;
                }
                mayHaveExecuted |= failure.kind() != CallFailure.Kind.OVERLOADED;
                recover(failure, attempt);
                continue;
            }

            KvResponse response = CommandCodec.decodeResponse(executed.result());
            if (response instanceof KvResponse.Rejected rejected) {
                if (rejected.reason() == KvResponse.Reason.STALE_SEQUENCE) {
                    history.fail(operation, process, command, response);
                    throw new IllegalStateException("Sequence " + attemptSequence + " was refused as stale; "
                            + "requests on one session must not overtake each other");
                }
                clientId = KvRequest.ANONYMOUS;
                if (mayHaveExecuted) {
                    history.info(operation, process, command);
                    throw new IndeterminateResultException(
                            "The session expired while a retry was pending, so " + command
                                    + " may or may not have taken effect",
                            last);
                }
                openSessionIfNeeded();
                attemptSequence = ++sequence;
                continue;
            }

            history.ok(operation, process, command, response);
            return response;
        }

        history.info(operation, process, command);
        throw new IndeterminateResultException(
                "Gave up after " + config.maxAttempts() + " attempts, so " + command
                        + " may or may not have taken effect",
                last);
    }

    public synchronized KvResponse query(Command command, ReadConsistency consistency) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(consistency, "consistency");
        long operation = history.invoke(process, command);
        Bytes encoded = CommandCodec.encode(command);
        @Nullable CallFailure last = null;
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            try {
                Executed answered = endpoint.query(target, encoded, consistency, config.attemptDeadline())
                        .join();
                KvResponse response = CommandCodec.decodeResponse(answered.result());
                history.ok(operation, process, command, response);
                return response;
            } catch (CompletionException | CancellationException failed) {
                CallFailure failure = CallFailure.from(failed);
                if (failure.kind() == CallFailure.Kind.INVALID) {
                    history.fail(operation, process, command, null);
                    throw failure;
                }
                last = failure;
                recover(failure, attempt);
            }
        }
        history.fail(operation, process, command, null);
        throw new FlotillaClientException(
                "Gave up reading after " + config.maxAttempts() + " attempts; a read has no effect, so nothing changed",
                last);
    }

    public synchronized long clientId() {
        return clientId;
    }

    public HistoryRecorder history() {
        return history;
    }

    @Override
    public void close() {
        endpoint.close();
    }

    static Duration backoff(ClientConfig config, RandomGenerator random, int attempt) {
        long cap = config.backoffMax().toNanos();
        long ceiling = Math.min(cap, config.backoffBase().toNanos());
        for (int i = 0; i < attempt && ceiling < cap; i++) {
            ceiling = ceiling > cap / 2 ? cap : ceiling * 2;
        }
        return Duration.ofNanos(random.nextLong(ceiling + 1));
    }

    private void openSessionIfNeeded() {
        if (clientId != KvRequest.ANONYMOUS) {
            return;
        }
        Bytes register = CommandCodec.encode(KvRequest.register());
        @Nullable CallFailure last = null;
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            KvResponse response;
            try {
                response = CommandCodec.decodeResponse(call(register).result());
            } catch (CallFailure failure) {
                if (failure.kind() == CallFailure.Kind.INVALID) {
                    throw failure;
                }
                last = failure;
                recover(failure, attempt);
                continue;
            }
            if (response instanceof KvResponse.Opened opened) {
                clientId = opened.clientId();
                sequence = 0;
                return;
            }
            throw unexpected(response);
        }
        throw new FlotillaClientException("Could not open a session after " + config.maxAttempts() + " attempts", last);
    }

    private Executed call(Bytes payload) {
        try {
            return endpoint.execute(target, payload, config.attemptDeadline()).join();
        } catch (CompletionException | CancellationException failure) {
            throw CallFailure.from(failure);
        }
    }

    private void recover(CallFailure failure, int attempt) {
        switch (failure.kind()) {
            case NOT_LEADER -> {
                Optional<InetSocketAddress> hint = failure.leaderAddress();
                if (hint.isPresent() && !hint.get().equals(target)) {
                    target = hint.get();
                    return;
                }
                rotate();
            }
            case UNAVAILABLE, TIMED_OUT -> rotate();
            case OVERLOADED, INVALID -> {}
        }
        sleeper.sleep(backoff(config, random, attempt));
    }

    private void rotate() {
        cursor = (cursor + 1) % seeds.size();
        target = seeds.get(cursor);
    }

    private static Optional<Bytes> value(KvResponse response) {
        if (response instanceof KvResponse.Value value) {
            return value.asOptional();
        }
        throw unexpected(response);
    }

    private static IllegalStateException unexpected(KvResponse response) {
        return new IllegalStateException("The server answered with an unexpected response: " + response);
    }
}
