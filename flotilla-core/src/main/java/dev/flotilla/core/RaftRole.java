/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

@RaftSpec("§5.1 Raft basics")
public enum RaftRole {
    FOLLOWER,

    PRE_CANDIDATE,

    CANDIDATE,

    LEADER
}
