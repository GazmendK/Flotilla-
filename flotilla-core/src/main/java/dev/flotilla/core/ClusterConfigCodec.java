/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ClusterConfigCodec {

    public static final byte FORMAT_VERSION = 1;

    private static final int MAX_MEMBERS = 4096;
    private static final int MAX_ID_BYTES = 256;

    private ClusterConfigCodec() {}

    public static Bytes encode(ClusterConfig config) {
        int size = 1 + 2 * Integer.BYTES;
        for (NodeId member : config.allMembers()) {
            size += Integer.BYTES + utf8(member).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(FORMAT_VERSION);
        putMembers(buffer, config.voters());
        putMembers(buffer, config.learners());
        return Bytes.wrap(buffer.array());
    }

    public static ClusterConfig decode(Bytes encoded) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encoded.toByteArray());
            byte version = buffer.get();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                        "Unknown cluster configuration format " + version + "; this build reads " + FORMAT_VERSION);
            }
            SortedSet<NodeId> voters = members(buffer);
            SortedSet<NodeId> learners = members(buffer);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException(
                        "A cluster configuration has " + buffer.remaining() + " bytes left over after decoding");
            }
            return new ClusterConfig(voters, learners);
        } catch (BufferUnderflowException truncated) {
            throw new IllegalArgumentException("A cluster configuration ended before it was complete", truncated);
        }
    }

    private static void putMembers(ByteBuffer buffer, SortedSet<NodeId> members) {
        buffer.putInt(members.size());
        for (NodeId member : members) {
            byte[] id = utf8(member);
            buffer.putInt(id.length).put(id);
        }
    }

    private static SortedSet<NodeId> members(ByteBuffer buffer) {
        int count = buffer.getInt();
        if (count < 0 || count > MAX_MEMBERS) {
            throw new IllegalArgumentException("A cluster configuration declares " + count + " members");
        }
        SortedSet<NodeId> members = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            if (length < 1 || length > MAX_ID_BYTES || length > buffer.remaining()) {
                throw new IllegalArgumentException(
                        "A cluster configuration declares a node id of " + length + " bytes");
            }
            byte[] id = new byte[length];
            buffer.get(id);
            members.add(NodeId.of(new String(id, StandardCharsets.UTF_8)));
        }
        return members;
    }

    private static byte[] utf8(NodeId member) {
        return member.value().getBytes(StandardCharsets.UTF_8);
    }
}
