/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * gRPC transport adapter for peer-to-peer and client traffic.
 *
 * <p>Wire types generated from protobuf stay inside this package and are mapped to and from the
 * core's domain records at the boundary. That costs a mapping layer and buys two things: the
 * consensus core never depends on a serialization framework, and the wire format can evolve
 * independently of the algorithm.
 */
@NullMarked
package dev.flotilla.transport.grpc;

import org.jspecify.annotations.NullMarked;
