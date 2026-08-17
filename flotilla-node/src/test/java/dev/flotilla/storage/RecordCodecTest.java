/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecordCodecTest {

    private static LogEntry roundTrip(LogEntry entry) {
        byte[] record = RecordCodec.encode(entry);
        byte[] frame = Arrays.copyOfRange(record, 0, RecordCodec.FRAME_BYTES);
        int bodyLength = RecordCodec.declaredBodyLength(frame);
        byte[] body = Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);

        assertThat(bodyLength).isEqualTo(body.length);
        assertThat(RecordCodec.checksumMatches(frame, body)).isTrue();
        return RecordCodec.decode(body, "test", 0);
    }

    @ParameterizedTest
    @EnumSource(EntryType.class)
    @DisplayName("every entry type survives a round trip")
    void everyEntryTypeRoundTrips(EntryType type) {
        LogEntry entry = new LogEntry(7, 42, type, Bytes.ofUtf8("payload"));

        assertThat(roundTrip(entry)).isEqualTo(entry);
    }

    @Test
    void anEmptyPayloadRoundTrips() {
        assertThat(roundTrip(LogEntry.noOp(1, 1))).isEqualTo(LogEntry.noOp(1, 1));
    }

    @Test
    void aLargePayloadRoundTrips() {
        byte[] payload = new byte[128 * 1024];
        Arrays.fill(payload, (byte) 0xAB);
        LogEntry entry = LogEntry.normal(3, 9, Bytes.copyOf(payload));

        assertThat(roundTrip(entry)).isEqualTo(entry);
    }

    @Test
    @DisplayName("a single flipped bit in the payload is detected")
    void aFlippedBitIsDetected() {
        byte[] record = RecordCodec.encode(LogEntry.normal(1, 1, Bytes.ofUtf8("value")));
        byte[] frame = Arrays.copyOfRange(record, 0, RecordCodec.FRAME_BYTES);
        byte[] body = Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);

        body[body.length - 1] ^= 0x01;

        assertThat(RecordCodec.checksumMatches(frame, body)).isFalse();
    }

    @Test
    @DisplayName("a flipped bit in the header is detected too, since the checksum covers the body only via the frame")
    void aFlippedBitInTheLengthIsDetected() {
        byte[] record = RecordCodec.encode(LogEntry.normal(1, 1, Bytes.ofUtf8("value")));
        byte[] frame = Arrays.copyOfRange(record, 0, RecordCodec.FRAME_BYTES);
        byte[] body = Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);

        frame[RecordCodec.FRAME_BYTES - 1] ^= 0x01;

        assertThat(RecordCodec.checksumMatches(frame, body)).isFalse();
    }

    @Test
    @DisplayName("an implausible length is rejected rather than allocated")
    void anImplausibleLengthIsRejected() {
        byte[] frame = new byte[RecordCodec.FRAME_BYTES];
        ByteBuffer.wrap(frame).putInt(0, Integer.MAX_VALUE);

        assertThat(RecordCodec.declaredBodyLength(frame)).isNegative();
    }

    @Test
    @DisplayName("an unwritten frame of zeros reads as end-of-log, not as a record")
    void zeroesReadAsEndOfLog() {
        assertThat(RecordCodec.declaredBodyLength(new byte[RecordCodec.FRAME_BYTES]))
                .isNegative();
    }

    @Test
    void anUnknownEntryTypeIsReportedAsCorruption() {
        byte[] record = RecordCodec.encode(LogEntry.normal(1, 1, Bytes.EMPTY));
        byte[] body = Arrays.copyOfRange(record, RecordCodec.FRAME_BYTES, record.length);
        body[1 + Long.BYTES + Long.BYTES] = 99;

        assertThatThrownBy(() -> RecordCodec.decode(body, "segment", 128))
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("unknown entry type")
                .hasMessageContaining("offset 128");
    }

    @Property(tries = 300)
    @DisplayName("arbitrary entries round-trip byte for byte")
    void arbitraryEntriesRoundTrip(
            @ForAll @LongRange(min = 0, max = 1_000_000) long term,
            @ForAll @LongRange(min = 1, max = 1_000_000) long index,
            @ForAll byte[] payload) {
        LogEntry entry = LogEntry.normal(term, index, Bytes.copyOf(payload));

        assertThat(roundTrip(entry)).isEqualTo(entry);
    }
}
