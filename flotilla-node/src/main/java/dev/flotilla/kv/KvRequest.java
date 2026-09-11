/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import java.util.Objects;

public sealed interface KvRequest {

    long ANONYMOUS = 0;

    record Register() implements KvRequest {}

    record Invoke(long clientId, long sequence, Command command) implements KvRequest {
        public Invoke {
            Objects.requireNonNull(command, "command");
            if (clientId < 0) {
                throw new IllegalArgumentException("clientId must not be negative, was " + clientId);
            }
            if (clientId != ANONYMOUS && sequence < 1) {
                throw new IllegalArgumentException("A sessioned request starts at sequence 1, was " + sequence
                        + "; sequence 0 is what an unused session holds and would be read as a retry");
            }
            if (clientId == ANONYMOUS && sequence != 0) {
                throw new IllegalArgumentException("An anonymous request carries no sequence, was " + sequence);
            }
        }

        public boolean isAnonymous() {
            return clientId == ANONYMOUS;
        }
    }

    static KvRequest anonymous(Command command) {
        return new Invoke(ANONYMOUS, 0, command);
    }

    static KvRequest of(long clientId, long sequence, Command command) {
        return new Invoke(clientId, sequence, command);
    }

    static KvRequest register() {
        return new Register();
    }
}
