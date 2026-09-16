/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import com.google.protobuf.ByteString;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.wire.v1.DeliverRequest;
import java.util.ArrayList;
import java.util.List;

public final class SnapshotChunks {

    private SnapshotChunks() {}

    public static boolean isSnapshotChunk(DeliverRequest envelope) {
        return envelope.getBodyCase() == DeliverRequest.BodyCase.INSTALL_SNAPSHOT_REQUEST;
    }

    public static boolean isWholeSnapshot(dev.flotilla.wire.v1.InstallSnapshotRequest body) {
        return body.getOffset() == 0 && body.getDone();
    }

    public static List<DeliverRequest> split(InstallSnapshotRequest request, int maxChunkBytes) {
        if (maxChunkBytes < 1) {
            throw new IllegalArgumentException("maxChunkBytes must be at least 1, was " + maxChunkBytes);
        }
        DeliverRequest whole = MessageCodec.encode(request);
        ByteString payload = whole.getInstallSnapshotRequest().getData();
        if (payload.size() <= maxChunkBytes) {
            return List.of(whole);
        }

        List<DeliverRequest> chunks = new ArrayList<>();
        for (int offset = 0; offset < payload.size(); offset += maxChunkBytes) {
            int end = Math.min(offset + maxChunkBytes, payload.size());
            chunks.add(whole.toBuilder()
                    .setInstallSnapshotRequest(whole.getInstallSnapshotRequest().toBuilder()
                            .setOffset(offset)
                            .setDone(end == payload.size())
                            .setData(payload.substring(offset, end)))
                    .build());
        }
        return List.copyOf(chunks);
    }
}
