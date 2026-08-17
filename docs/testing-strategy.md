# Testing strategy

The claim this project makes is that its correctness is demonstrated rather than asserted. This
page is what that means concretely.

## Layers

| Layer | What it answers | Where |
|---|---|---|
| Unit tests | Does this type behave as specified at its boundaries? | `flotilla-core` |
| Paper tests | Does the implementation match the figures it claims to implement? | `Figure7Test`, `Figure8Test` |
| Property tests | Does this hold for arbitrary inputs, not just the ones I thought of? | jqwik in `BytesTest` |
| Architecture tests | Are the structural guarantees still true? | `CoreArchitectureTest`, `NodeLayeringTest`, `SimulationIsolationTest` |
| Deterministic simulation | Does safety hold under adversarial schedules nobody wrote down? | `flotilla-testing` |
| Checker tests | Do the checkers themselves detect the thing they claim to detect? | `InvariantTest` |
| Crash-consistency tests | Does durability survive a crash at every write point? | Phase 6 |
| Linearizability checking | Is the observable history actually linearizable? | Phase 11 |
| Integration tests | Does it work with real processes, sockets and files? | Phase 9 |

## The rule that holds it together

**Every safeguard is tested with itself disabled.** A mechanism nobody has watched fail is a
mechanism nobody can trust.

| Safeguard | Test that proves it is load-bearing |
|---|---|
| PreVote | `anIsolatedNodeInflatesItsTermWithoutPreVote` — with `preVote(false)` the term climbs without bound |
| CheckQuorum | `withoutCheckQuorumAnIsolatedLeaderKeepsLeading` — two nodes then believe they lead |
| Current-term commit rule | `Figure8Test` fails with `expected: 0L but was: 2L` when the check is removed |
| Conflict hints | `ConflictBacktrackingTest` bounds the number of round trips; plain decrementing would need a thousand |
| Architecture rules | Adding `System.nanoTime()` to the core makes `CoreArchitectureTest` fail with the reason attached |
| Invariant checkers | `InvariantTest` builds a violating world for each and asserts it fires |

## What runs when

| Trigger | Scope |
|---|---|
| Every push and pull request | Full build on Linux, macOS and Windows: compilation, Error Prone, NullAway, formatting, all unit and architecture tests, and roughly 500 simulation runs |
| Nightly | Tens of thousands of fresh seeds across chaotic and adversarial profiles, sharded across jobs; a failing seed is uploaded as an artifact |
| Release | Everything above, plus benchmarks |

## Deliberate omissions

- **No mocking framework.** Ports have real in-memory implementations and shared contract tests.
  See [ADR-0008](adr/0008-no-mocking-policy.md).
- **No coverage threshold.** Coverage measures which lines ran, not whether anything was checked.
  Mutation testing is on the roadmap and answers the question coverage pretends to.
- **No test that asserts only "it does not throw"**, except where the absence of an invariant
  violation *is* the assertion — which is precisely what the simulation runs are.
