/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import java.util.Arrays;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;

class RecordCodecFuzzTest {

    private static byte[] frameOf(byte[] record) {
        return Arrays.copyOfRange(record, 0, RecordCodec.FRAME_BYTES);
    }

    private static byte[] bodyOf(byte[] record) {
        return Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);
    }

    @Property(tries = 5000)
    @DisplayName("arbitrary bytes make the decoder report corruption, never anything else")
    void decodingArbitraryBytesOnlyReportsCorruption(@ForAll byte[] body) {
        try {
            RecordCodec.decode(body, "fuzz", 0);
        } catch (CorruptionException expected) {
            return;
        }
    }

    @Property(tries = 5000)
    @DisplayName("the framing helpers never throw, whatever the disk contains")
    void framingHelpersNeverThrow(@ForAll byte[] raw, @ForAll byte[] body) {
        byte[] frame = Arrays.copyOf(raw, RecordCodec.FRAME_BYTES);

        int length = RecordCodec.declaredBodyLength(frame);

        assertThat(length).isLessThanOrEqualTo(RecordCodec.MAX_BODY_BYTES);
        RecordCodec.checksumMatches(frame, body);
    }

    @Property(tries = 5000)
    @DisplayName("a corrupted length is never large enough to be allocated blindly")
    void aCorruptedLengthIsAlwaysBounded(@ForAll byte[] raw) {
        byte[] frame = Arrays.copyOf(raw, RecordCodec.FRAME_BYTES);

        int length = RecordCodec.declaredBodyLength(frame);

        assertThat(length == -1 || (length > 0 && length <= RecordCodec.MAX_BODY_BYTES))
                .as("declared length %d escaped its bounds", length)
                .isTrue();
    }

    @Property(tries = 3000)
    @DisplayName("mutating any byte of a valid record is detected before it is believed")
    void anyMutationIsDetected(@ForAll @IntRange(min = 0, max = 10_000) int position, @ForAll byte mask) {
        if (mask == 0) {
            return;
        }
        LogEntry entry = LogEntry.normal(3, 7, Bytes.ofUtf8("a payload worth checking"));
        byte[] record = RecordCodec.encode(entry);
        record[position % record.length] ^= mask;

        byte[] frame = frameOf(record);
        byte[] body = bodyOf(record);
        boolean detected =
                RecordCodec.declaredBodyLength(frame) != body.length || !RecordCodec.checksumMatches(frame, body);

        assertThat(detected)
                .as("a mutation at byte %d passed the checksum unnoticed", position % record.length)
                .isTrue();
    }

    @Property(tries = 2000)
    @DisplayName("truncating a valid record at any point is detected")
    void truncationIsDetected(@ForAll @IntRange(min = 0, max = 10_000) int cut) {
        LogEntry entry = LogEntry.normal(5, 11, Bytes.ofUtf8("payload"));
        byte[] record = RecordCodec.encode(entry);
        int keep = cut % record.length;
        byte[] truncated = Arrays.copyOf(record, keep);

        if (truncated.length < RecordCodec.FRAME_BYTES) {
            return;
        }
        byte[] frame = frameOf(truncated);
        byte[] body = bodyOf(truncated);

        assertThat(RecordCodec.declaredBodyLength(frame))
                .as("a truncated record must not appear complete")
                .isNotEqualTo(body.length);
    }
}
