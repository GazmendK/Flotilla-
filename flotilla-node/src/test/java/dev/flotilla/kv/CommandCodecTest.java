/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandCodecTest {

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private static List<Command> everyCommandShape() {
        return List.of(
                new Command.Put(b("k"), b("v")),
                new Command.Put(Bytes.EMPTY, Bytes.EMPTY),
                new Command.Delete(b("k")),
                new Command.CompareAndSwap(b("k"), b("old"), b("new")),
                new Command.CompareAndSwap(b("k"), null, b("new")),
                new Command.CompareAndSwap(b("k"), b("old"), null),
                new Command.CompareAndSwap(b("k"), null, null),
                new Command.Get(b("k")),
                new Command.Scan(b("a"), b("z"), 0),
                new Command.Scan(Bytes.EMPTY, b("ÿ"), Integer.MAX_VALUE));
    }

    @Test
    void everyCommandShapeSurvivesARoundTrip() {
        for (Command command : everyCommandShape()) {
            assertThat(CommandCodec.decodeCommand(CommandCodec.encode(command)))
                    .as("round trip of %s", command)
                    .isEqualTo(command);
        }
    }

    @Test
    void everyResponseShapeSurvivesARoundTrip() {
        List<KvResponse> responses = List.of(
                KvResponse.ABSENT,
                new KvResponse.Value(b("v")),
                new KvResponse.Value(Bytes.EMPTY),
                new KvResponse.Swapped(true),
                new KvResponse.Swapped(false),
                new KvResponse.Entries(List.of()),
                new KvResponse.Entries(List.of(new KeyValue(b("a"), b("1")), new KeyValue(b("b"), b("2")))));

        for (KvResponse response : responses) {
            assertThat(CommandCodec.decodeResponse(CommandCodec.encode(response)))
                    .as("round trip of %s", response)
                    .isEqualTo(response);
        }
    }

    @Test
    @DisplayName("encoding is a function of the value alone, so two equal commands are equal bytes")
    void encodingIsStable() {
        Command first = new Command.Put(b("k"), b("v"));
        Command second = new Command.Put(b("k"), b("v"));

        assertThat(CommandCodec.encode(first)).isEqualTo(CommandCodec.encode(second));
    }

    @Test
    void anUnknownTypeByteIsRejected() {
        assertThatThrownBy(() -> CommandCodec.decodeCommand(Bytes.wrap(new byte[] {99})))
                .isInstanceOf(MalformedCommandException.class)
                .hasMessageContaining("Unknown command type");
    }

    @Test
    void anEmptyPayloadIsRejected() {
        assertThatThrownBy(() -> CommandCodec.decodeCommand(Bytes.EMPTY)).isInstanceOf(MalformedCommandException.class);
    }

    @Test
    @DisplayName("a truncated command is reported, never thrown as a buffer error")
    void truncationIsReportedAsCorruption() {
        byte[] encoded =
                CommandCodec.encode(new Command.Put(b("key"), b("value"))).toByteArray();

        for (int length = 1; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> CommandCodec.decodeCommand(Bytes.wrap(truncated)))
                    .as("truncated to %d of %d bytes", length, encoded.length)
                    .isInstanceOf(MalformedCommandException.class);
        }
    }

    @Test
    @DisplayName("trailing bytes are refused rather than silently ignored")
    void trailingBytesAreRefused() {
        byte[] encoded = CommandCodec.encode(new Command.Get(b("k"))).toByteArray();
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 1);

        assertThatThrownBy(() -> CommandCodec.decodeCommand(Bytes.wrap(withTail)))
                .isInstanceOf(MalformedCommandException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    @DisplayName("a declared length larger than the payload is caught before anything is allocated")
    void anImpossibleLengthIsBounded() {
        byte[] hostile = new byte[] {1, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};

        assertThatThrownBy(() -> CommandCodec.decodeCommand(Bytes.wrap(hostile)))
                .isInstanceOf(MalformedCommandException.class);
    }
}
