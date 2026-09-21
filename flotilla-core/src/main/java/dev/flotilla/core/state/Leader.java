/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.RaftSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

@RaftSpec("Figure 2, Volatile state on leaders")
public final class Leader implements RaftState {

    private final SortedSet<NodeId> recentlyActive = new TreeSet<>();
    private final SortedMap<NodeId, Progress> peers = new TreeMap<>();
    private final SortedMap<NodeId, Long> ackedRounds = new TreeMap<>();
    private final NavigableMap<Long, Long> roundSentAtTick = new TreeMap<>();
    private final ReadIndexQueue reads = new ReadIndexQueue();
    private final SortedMap<NodeId, Long> lastHeardAtTick = new TreeMap<>();
    private final SortedMap<NodeId, CatchUpTracker> catchUps = new TreeMap<>();

    @Nullable
    private NodeId transferee;

    private long transferStartedAtTick;
    private boolean leaseForfeited;

    private long round;
    private long ticks;

    public void trackPeer(NodeId peer, long nextIndex) {
        peers.put(Objects.requireNonNull(peer, "peer"), new Progress(nextIndex));
    }

    public void untrackPeer(NodeId peer) {
        peers.remove(peer);
        ackedRounds.remove(peer);
        recentlyActive.remove(peer);
        lastHeardAtTick.remove(peer);
        catchUps.remove(peer);
    }

    public SortedMap<NodeId, Progress> peers() {
        return Collections.unmodifiableSortedMap(peers);
    }

    @Nullable
    public Progress progressFor(NodeId peer) {
        return peers.get(peer);
    }

    public void markActive(NodeId peer) {
        recentlyActive.add(Objects.requireNonNull(peer, "peer"));
        lastHeardAtTick.put(peer, ticks);
    }

    public boolean heardFromWithin(NodeId peer, long windowTicks) {
        Long heard = lastHeardAtTick.get(peer);
        return heard != null && ticks - heard <= windowTicks;
    }

    public SortedSet<NodeId> recentlyActive() {
        return Collections.unmodifiableSortedSet(recentlyActive);
    }

    public int recentlyActiveAmong(Collection<NodeId> voters) {
        int active = 0;
        for (NodeId voter : voters) {
            if (recentlyActive.contains(voter)) {
                active++;
            }
        }
        return active;
    }

    public void resetActivity(NodeId self) {
        recentlyActive.clear();
        recentlyActive.add(Objects.requireNonNull(self, "self"));
    }

    public void tick() {
        ticks++;
    }

    public long ticks() {
        return ticks;
    }

    public long round() {
        return round;
    }

    public long nextRound() {
        round++;
        roundSentAtTick.put(round, ticks);
        return round;
    }

    public void recordAck(NodeId peer, long ackedRound) {
        ackedRounds.merge(Objects.requireNonNull(peer, "peer"), ackedRound, Math::max);
    }

    @RaftSpec(value = "§6.4 Processing read-only queries more efficiently", source = RaftSpec.Source.DISSERTATION)
    public long quorumAckedRound(Collection<NodeId> voters, NodeId self, int quorum) {
        List<Long> acked = new ArrayList<>(voters.size());
        for (NodeId voter : voters) {
            acked.add(voter.equals(self) ? round : ackedRounds.getOrDefault(voter, 0L));
        }
        acked.sort(Comparator.reverseOrder());
        long confirmed = acked.get(quorum - 1);
        roundSentAtTick.headMap(confirmed, false).clear();
        return confirmed;
    }

    public long ticksSinceRoundWasSent(long confirmedRound) {
        Long sentAt = roundSentAtTick.get(confirmedRound);
        return sentAt == null ? Long.MAX_VALUE : ticks - sentAt;
    }

    public void trackCatchUp(NodeId learner, long target) {
        catchUps.putIfAbsent(Objects.requireNonNull(learner, "learner"), new CatchUpTracker(target, ticks));
    }

    public void forgetCatchUp(NodeId node) {
        catchUps.remove(node);
    }

    @Nullable
    public CatchUpTracker catchUpOf(NodeId learner) {
        return catchUps.get(learner);
    }

    public void beginTransfer(NodeId target) {
        transferee = Objects.requireNonNull(target, "target");
        transferStartedAtTick = ticks;
    }

    public void endTransfer() {
        transferee = null;
    }

    @Nullable
    public NodeId transferee() {
        return transferee;
    }

    public long ticksSinceTransferBegan() {
        return ticks - transferStartedAtTick;
    }

    public void forfeitLease() {
        leaseForfeited = true;
    }

    public boolean hasForfeitedLease() {
        return leaseForfeited;
    }

    public ReadIndexQueue reads() {
        return reads;
    }

    @Override
    public RaftRole role() {
        return RaftRole.LEADER;
    }
}
