/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.Snapshot;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.CRC32C;

public final class SnapshotCodec {

    public static final int HEADER_BYTES = 16;
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BODY_BYTES = 512 * 1024 * 1024;

    private static final int MAGIC = 0x464C5350;
    private static final int MAX_MEMBERS = 4096;
    private static final int MAX_NODE_ID_BYTES = 256;

    private SnapshotCodec() {}

    public static byte[] encode(Snapshot snapshot) {
        byte[] body = encodeBody(snapshot);
        byte[] file = new byte[HEADER_BYTES + body.length];
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.putInt(MAGIC).putInt(FORMAT_VERSION).putInt(body.length).putInt(checksum(body));
        buffer.put(body);
        return file;
    }

    public static int declaredBodyLength(byte[] header) {
        if (header.length < HEADER_BYTES) {
            return -1;
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        if (buffer.getInt() != MAGIC || buffer.getInt() != FORMAT_VERSION) {
            return -1;
        }
        int length = buffer.getInt();
        return length > 0 && length <= MAX_BODY_BYTES ? length : -1;
    }

    public static Snapshot decode(byte[] header, byte[] body, String source) {
        int declared = declaredBodyLength(header);
        if (declared != body.length) {
            throw new CorruptionException(source + " declares a body of " + declared + " bytes but holds " + body.length
                    + "; the snapshot is incomplete or not a snapshot at all.");
        }
        int expected = ByteBuffer.wrap(header).getInt(3 * Integer.BYTES);
        if (expected != checksum(body)) {
            throw new CorruptionException(source + " fails its checksum; the snapshot is corrupt.");
        }
        return decodeBody(body, source);
    }

    private static byte[] encodeBody(Snapshot snapshot) {
        byte[] payload = snapshot.data().toByteArray();
        SortedSet<NodeId> voters = snapshot.cluster().voters();
        SortedSet<NodeId> learners = snapshot.cluster().learners();

        int size = 2 * Long.BYTES + 2 * Integer.BYTES + Integer.BYTES + payload.length;
        for (NodeId member : voters) {
            size += Integer.BYTES + utf8(member).length;
        }
        for (NodeId member : learners) {
            size += Integer.BYTES + utf8(member).length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putLong(snapshot.lastIncludedIndex()).putLong(snapshot.lastIncludedTerm());
        putMembers(buffer, voters);
        putMembers(buffer, learners);
        buffer.putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static void putMembers(ByteBuffer buffer, SortedSet<NodeId> members) {
        buffer.putInt(members.size());
        for (NodeId member : members) {
            byte[] encoded = utf8(member);
            buffer.putInt(encoded.length).put(encoded);
        }
    }

    private static Snapshot decodeBody(byte[] body, String source) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            long index = buffer.getLong();
            long term = buffer.getLong();
            SortedSet<NodeId> voters = readMembers(buffer, source);
            SortedSet<NodeId> learners = readMembers(buffer, source);
            int payloadLength = buffer.getInt();
            if (payloadLength < 0 || payloadLength > buffer.remaining()) {
                throw new CorruptionException(
                        source + " declares a payload of " + payloadLength + " bytes but holds " + buffer.remaining());
            }
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);
            return new Snapshot(index, term, new ClusterConfig(voters, learners), Bytes.wrap(payload));
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            throw new CorruptionException(source + " is not a readable snapshot: " + malformed.getMessage(), malformed);
        }
    }

    private static SortedSet<NodeId> readMembers(ByteBuffer buffer, String source) {
        int count = buffer.getInt();
        if (count < 0 || count > MAX_MEMBERS) {
            throw new CorruptionException(source + " declares " + count + " cluster members");
        }
        SortedSet<NodeId> members = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            if (length < 1 || length > MAX_NODE_ID_BYTES || length > buffer.remaining()) {
                throw new CorruptionException(source + " declares a node id of " + length + " bytes");
            }
            byte[] encoded = new byte[length];
            buffer.get(encoded);
            members.add(NodeId.of(new String(encoded, StandardCharsets.UTF_8)));
        }
        return members;
    }

    private static byte[] utf8(NodeId member) {
        return member.value().getBytes(StandardCharsets.UTF_8);
    }

    private static int checksum(byte[] body) {
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        return (int) crc.getValue();
    }
}
