/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class CommandCodec {

    public static final int MAX_FIELD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ENTRIES = 1_000_000;

    private static final byte PUT = 1;
    private static final byte DELETE = 2;
    private static final byte COMPARE_AND_SWAP = 3;
    private static final byte GET = 4;
    private static final byte SCAN = 5;

    private static final byte VALUE = 1;
    private static final byte SWAPPED = 2;
    private static final byte ENTRIES = 3;

    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    private CommandCodec() {}

    public static Bytes encode(Command command) {
        Objects.requireNonNull(command, "command");
        ByteBuffer buffer = ByteBuffer.allocate(sizeOf(command)).order(ByteOrder.BIG_ENDIAN);
        switch (command) {
            case Command.Put put -> {
                buffer.put(PUT);
                putField(buffer, put.key());
                putField(buffer, put.value());
            }
            case Command.Delete delete -> {
                buffer.put(DELETE);
                putField(buffer, delete.key());
            }
            case Command.CompareAndSwap swap -> {
                buffer.put(COMPARE_AND_SWAP);
                putField(buffer, swap.key());
                putOptional(buffer, swap.expected());
                putOptional(buffer, swap.value());
            }
            case Command.Get get -> {
                buffer.put(GET);
                putField(buffer, get.key());
            }
            case Command.Scan scan -> {
                buffer.put(SCAN);
                putField(buffer, scan.fromInclusive());
                putField(buffer, scan.toExclusive());
                buffer.putInt(scan.limit());
            }
        }
        return Bytes.wrap(buffer.array());
    }

    public static Command decodeCommand(Bytes encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteBuffer buffer = reader(encoded);
        try {
            byte type = buffer.get();
            Command command =
                    switch (type) {
                        case PUT -> new Command.Put(field(buffer), field(buffer));
                        case DELETE -> new Command.Delete(field(buffer));
                        case COMPARE_AND_SWAP ->
                            new Command.CompareAndSwap(field(buffer), optionalField(buffer), optionalField(buffer));
                        case GET -> new Command.Get(field(buffer));
                        case SCAN -> new Command.Scan(field(buffer), field(buffer), limit(buffer.getInt()));
                        default -> throw new MalformedCommandException("Unknown command type " + type);
                    };
            requireFullyConsumed(buffer);
            return command;
        } catch (BufferUnderflowException truncated) {
            throw new MalformedCommandException("Command ends in the middle of a field", truncated);
        }
    }

    public static Bytes encode(KvResponse response) {
        Objects.requireNonNull(response, "response");
        ByteBuffer buffer = ByteBuffer.allocate(sizeOf(response)).order(ByteOrder.BIG_ENDIAN);
        switch (response) {
            case KvResponse.Value value -> {
                buffer.put(VALUE);
                putOptional(buffer, value.value());
            }
            case KvResponse.Swapped swapped -> {
                buffer.put(SWAPPED);
                buffer.put(swapped.swapped() ? PRESENT : ABSENT);
            }
            case KvResponse.Entries entries -> {
                buffer.put(ENTRIES);
                buffer.putInt(entries.entries().size());
                for (KeyValue entry : entries.entries()) {
                    putField(buffer, entry.key());
                    putField(buffer, entry.value());
                }
            }
        }
        return Bytes.wrap(buffer.array());
    }

    public static KvResponse decodeResponse(Bytes encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteBuffer buffer = reader(encoded);
        try {
            byte type = buffer.get();
            KvResponse response =
                    switch (type) {
                        case VALUE -> KvResponse.of(optionalField(buffer));
                        case SWAPPED -> new KvResponse.Swapped(flag(buffer.get()));
                        case ENTRIES -> new KvResponse.Entries(entries(buffer));
                        default -> throw new MalformedCommandException("Unknown response type " + type);
                    };
            requireFullyConsumed(buffer);
            return response;
        } catch (BufferUnderflowException truncated) {
            throw new MalformedCommandException("Response ends in the middle of a field", truncated);
        }
    }

    private static List<KeyValue> entries(ByteBuffer buffer) {
        int count = buffer.getInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new MalformedCommandException("Entry count " + count + " is outside 0.." + MAX_ENTRIES);
        }
        List<KeyValue> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            entries.add(new KeyValue(field(buffer), field(buffer)));
        }
        return entries;
    }

    private static ByteBuffer reader(Bytes encoded) {
        if (encoded.isEmpty()) {
            throw new MalformedCommandException("An encoded command is never empty");
        }
        return ByteBuffer.wrap(encoded.toByteArray()).order(ByteOrder.BIG_ENDIAN);
    }

    private static void requireFullyConsumed(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            throw new MalformedCommandException(buffer.remaining() + " trailing bytes after a complete value");
        }
    }

    private static void putField(ByteBuffer buffer, Bytes field) {
        buffer.putInt(field.size());
        buffer.put(field.toByteArray());
    }

    private static void putOptional(ByteBuffer buffer, @Nullable Bytes field) {
        if (field == null) {
            buffer.put(ABSENT);
            return;
        }
        buffer.put(PRESENT);
        putField(buffer, field);
    }

    private static Bytes field(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > MAX_FIELD_BYTES) {
            throw new MalformedCommandException("Field length " + length + " is outside 0.." + MAX_FIELD_BYTES);
        }
        if (buffer.remaining() < length) {
            throw new MalformedCommandException(
                    "Field declares " + length + " bytes but only " + buffer.remaining() + " remain");
        }
        byte[] field = new byte[length];
        buffer.get(field);
        return Bytes.wrap(field);
    }

    @Nullable
    private static Bytes optionalField(ByteBuffer buffer) {
        return flag(buffer.get()) ? field(buffer) : null;
    }

    private static boolean flag(byte value) {
        return switch (value) {
            case ABSENT -> false;
            case PRESENT -> true;
            default -> throw new MalformedCommandException("Flag byte must be 0 or 1, was " + value);
        };
    }

    private static int limit(int value) {
        if (value < 0) {
            throw new MalformedCommandException("Scan limit must not be negative, was " + value);
        }
        return value;
    }

    private static int sizeOf(Command command) {
        return 1
                + switch (command) {
                    case Command.Put put -> field(put.key()) + field(put.value());
                    case Command.Delete delete -> field(delete.key());
                    case Command.CompareAndSwap swap ->
                        field(swap.key()) + optional(swap.expected()) + optional(swap.value());
                    case Command.Get get -> field(get.key());
                    case Command.Scan scan -> field(scan.fromInclusive()) + field(scan.toExclusive()) + Integer.BYTES;
                };
    }

    private static int sizeOf(KvResponse response) {
        return 1
                + switch (response) {
                    case KvResponse.Value value -> optional(value.value());
                    case KvResponse.Swapped ignored -> 1;
                    case KvResponse.Entries entries -> {
                        int size = Integer.BYTES;
                        for (KeyValue entry : entries.entries()) {
                            size += field(entry.key()) + field(entry.value());
                        }
                        yield size;
                    }
                };
    }

    private static int field(Bytes value) {
        return Integer.BYTES + value.size();
    }

    private static int optional(@Nullable Bytes value) {
        return value == null ? 1 : 1 + field(value);
    }
}
