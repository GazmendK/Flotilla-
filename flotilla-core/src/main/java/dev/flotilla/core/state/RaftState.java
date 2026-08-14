/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.RaftRole;
import dev.flotilla.core.RaftSpec;

@RaftSpec("§5.1 Raft basics")
public sealed interface RaftState permits Follower, Candidate, Leader {

    RaftRole role();
}
