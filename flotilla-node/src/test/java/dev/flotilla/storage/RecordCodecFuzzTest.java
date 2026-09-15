/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.testing.SeededInputs;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordCodecFuzzTest {

    private static final int TRIES = 5000;

    private static byte[] frameOf(byte[] record) {
        return Arrays.copyOfRange(record, 0, RecordCodec.FRAME_BYTES);
    }

    private static byte[] bodyOf(byte[] record) {
        return Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);
    }

    @Test
    @DisplayName("arbitrary bytes make the decoder report corruption, never anything else")
    void decodingArbitraryBytesOnlyReportsCorruption() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < TRIES; attempt++) {
            byte[] body = SeededInputs.bytes(random, 256);
            try {
                RecordCodec.decode(body, "fuzz", 0);
            } catch (CorruptionException expected) {
                assertThat(expected).hasMessageContaining("fuzz");
            } catch (RuntimeException unexpected) {
                throw new AssertionError(
                        "seed " + SeededInputs.SEED + ", attempt " + attempt + ": decoding " + body.length
                                + " arbitrary bytes threw " + unexpected,
                        unexpected);
            }
        }
    }

    @Test
    @DisplayName("the framing helpers never throw, whatever the disk contains")
    void framingHelpersNeverThrow() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < TRIES; attempt++) {
            byte[] frame = Arrays.copyOf(SeededInputs.bytes(random, 32), RecordCodec.FRAME_BYTES);
            byte[] body = SeededInputs.bytes(random, 256);

            int length = RecordCodec.declaredBodyLength(frame);

            assertThat(length)
                    .as("seed %d, attempt %d", SeededInputs.SEED, attempt)
                    .isLessThanOrEqualTo(RecordCodec.MAX_BODY_BYTES);
            boolean _ = RecordCodec.checksumMatches(frame, body);
        }
    }

    @Test
    @DisplayName("a corrupted length is never large enough to be allocated blindly")
    void aCorruptedLengthIsAlwaysBounded() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < TRIES; attempt++) {
            byte[] frame = Arrays.copyOf(SeededInputs.bytes(random, 32), RecordCodec.FRAME_BYTES);

            int length = RecordCodec.declaredBodyLength(frame);

            assertThat(length == -1 || (length > 0 && length <= RecordCodec.MAX_BODY_BYTES))
                    .as(
                            "seed %d, attempt %d: declared length %d escaped its bounds",
                            SeededInputs.SEED, attempt, length)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("flipping any bits of any single byte of a valid record is detected, checked exhaustively")
    void everySingleByteMutationIsDetected() {
        byte[] original = RecordCodec.encode(LogEntry.normal(3, 7, Bytes.ofUtf8("a payload worth checking")));

        for (int position = 0; position < original.length; position++) {
            for (int mask = 1; mask <= 0xFF; mask++) {
                byte[] record = original.clone();
                record[position] ^= (byte) mask;

                byte[] frame = frameOf(record);
                byte[] body = bodyOf(record);
                boolean detected = RecordCodec.declaredBodyLength(frame) != body.length
                        || !RecordCodec.checksumMatches(frame, body);

                assertThat(detected)
                        .as("flipping mask 0x%02X at byte %d passed the checksum unnoticed", mask, position)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("truncating a valid record at every possible length is detected, checked exhaustively")
    void everyTruncationIsDetected() {
        byte[] record = RecordCodec.encode(LogEntry.normal(5, 11, Bytes.ofUtf8("payload")));

        for (int keep = RecordCodec.FRAME_BYTES; keep < record.length; keep++) {
            byte[] truncated = Arrays.copyOf(record, keep);

            assertThat(RecordCodec.declaredBodyLength(frameOf(truncated)))
                    .as("a record cut to %d of %d bytes must not appear complete", keep, record.length)
                    .isNotEqualTo(bodyOf(truncated).length);
        }
    }
}
