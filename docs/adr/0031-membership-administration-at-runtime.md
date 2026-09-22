# ADR-0031: Membership is administered through the leader, and a leader that leaves hands over

- Status: Accepted
- Date: 2026-09-22

## Context

[ADR-0029](0029-single-server-membership-changes.md) and
[ADR-0030](0030-catch-up-rounds-and-leadership-transfer.md) made membership changes and leadership
transfer safe in the consensus core. An operator still had no way to ask for one, and three questions
only show up once a real cluster changes shape under load:

- **Where do addresses come from?** A configuration names nodes, not machines. A node that is added
  has to be reachable before anyone can replicate to it.
- **What does a request that cannot be made *yet* return?** A leader elected a moment ago must commit
  an entry of its own term first; a change arriving while another is in flight must wait for it.
  Neither is the operator's mistake.
- **What happens when the leader removes itself?** The core stepped down the moment the removal
  committed. The remaining voters had heard from it an instant earlier, so they sat out an election
  timeout inside the lease they had just granted it. `ScaleClusterIT` measured what that costs:
  clients gave up in half of all runs.

## Decision

**An `AdminService` next to the client service.** `ChangeMembership` adds a learner, promotes one or
removes a member, and answers once the new configuration has committed and been applied.
`TransferLeadership` answers once the target is known to lead. `DescribeCluster` returns a node's view
— configuration, whether it has committed, the leader, and every learner's catch-up rounds — from any
node, or with `leader_only` only from the leader. Every call that must reach the leader is answered
elsewhere with `UNAVAILABLE` and the leader in the trailers, exactly like a client request, so
`FlotillaAdmin` follows the same redirects as `FlotillaClient`. A refused change is
`FAILED_PRECONDITION` with the core's reason as the description, unchanged.

**Addresses stay with the embedder.** The configuration holds node identifiers only, and the
`PeerDirectory` the node was started with must resolve a node before it is added. Replicating
addresses through the log would put transport concerns into the consensus core and a second source
of truth next to the directory. Phase 14 gives the directory a configuration file; until then the
embedder owns it, as it already did for the initial members.

**Temporary refusals wait; permanent ones fail at once.** The core marks each refusal as temporary or
not. A leader that has not committed in its own term, a change already in flight, a transfer under
way, a learner that has not caught up and a majority not heard from recently are all temporary: the
event loop tries again on every tick for up to three election timeouts before giving up with the last
reason. Removing a node that is not a member, promoting a node that is not a learner and removing the
last voter fail immediately.

**A leader that removes itself hands over first.** Once its removal commits, it stops taking
proposals, picks the voter that has replicated the most, brings it up to date, sends it `TimeoutNow`
and only then steps down. If that has not happened within an election timeout, it steps down anyway.
This is the use the dissertation gives for leadership transfer (§3.10). Unavailability after removing
the leader drops from at least an election timeout to the time the successor needs to catch up and
win one round of votes.

**A proposal that committed is not reported as lost.** When a leader loses leadership, the requests
it is still waiting for are failed with "not the leader" — except those at or below its commit index,
which will be applied and answered normally. A leader removing itself steps down in the same step in
which its removal commits, and used to report its own successful removal as a failure.

**Snapshots carry the configuration that was applied, not the one the node started with.** The apply
loop now tracks the configuration of the entries it applies, and a snapshot records that. Before, a
node restarted from a snapshot taken after a membership change came back with its original members.

**Clients remember the leaders they were sent to.** An address learned from a redirect joins the
client's rotation, so a client whose seeds have all been removed still reaches the cluster.

## Alternatives considered

**Addresses in the configuration entry.** etcd does this, as member metadata. It is the better end
state and a larger change: the codec, the snapshot format and the peer directory would all have to
agree on it. Deferred until a configuration file exists that can seed it.

**Reject every refusal immediately and let the operator retry.** Simple, and it turns every change
made right after an election, and every second change sent in a script, into an error for a
condition that clears itself within milliseconds.

**Promote automatically once a learner has caught up.** Keeps an operator from forgetting the second
step, and makes a configuration change nobody asked for at that moment. Rejected, as in ADR-0030.

## Consequences

- `ScaleClusterIT` grows a real cluster from three voters to five and back to three under load,
  removes the leader while it leads, and checks every client operation for linearizability. With
  the default client settings it passes with no client errors, also while the rest of the build
  loads the machine. Without the handover, half its runs fail. With a retry budget of half a second
  per attempt it failed once under that load, which is why it uses the defaults: the claim is about
  what a client sees as shipped, not about how tight a budget survives.
- A removed node keeps running until the operator stops it, and should only be stopped once another
  node leads. Stopping it while it is still handing over brings the full election gap back — the
  first version of the test did exactly that and failed one run in six.
- `MembershipServerTest` covers each runtime guard, and switching each off fails it: the applied
  configuration in snapshots, keeping committed proposals, a transfer that never takes over, a
  second change sent while the first is in flight, and a learner the leader has no address for.
  `MembershipTest` fails when a removed leader steps down without waiting for its successor, and
  `FlotillaClientTest` when a client forgets the leaders it was redirected to.
