/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

@RaftSpec(value = "§4.1 Safety", source = RaftSpec.Source.DISSERTATION)
public sealed interface ConfChange {

    NodeId node();

    ClusterConfig applyTo(ClusterConfig current);

    record AddLearner(NodeId node) implements ConfChange {
        public AddLearner {
            Objects.requireNonNull(node, "node");
        }

        @Override
        public ClusterConfig applyTo(ClusterConfig current) {
            return current.withLearner(node);
        }
    }

    record Promote(NodeId node) implements ConfChange {
        public Promote {
            Objects.requireNonNull(node, "node");
        }

        @Override
        public ClusterConfig applyTo(ClusterConfig current) {
            return current.withPromotion(node);
        }
    }

    record Remove(NodeId node) implements ConfChange {
        public Remove {
            Objects.requireNonNull(node, "node");
        }

        @Override
        public ClusterConfig applyTo(ClusterConfig current) {
            return current.without(node);
        }
    }
}
