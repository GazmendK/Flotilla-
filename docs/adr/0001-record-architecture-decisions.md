# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-08-05

## Context

Flotilla is a consensus system. Most of its design consists of decisions that look arbitrary from
the outside and are in fact load-bearing: why an entry from a previous term is not committed by
counting replicas, why a configuration change takes effect when appended rather than when
committed, why lease reads are disabled by default.

Someone reading the code — including the author in six months — cannot recover that reasoning
from the code itself. Without a record, the predictable failure mode is that a deliberate
decision gets "simplified" and the system becomes subtly unsafe in a way no test catches.

## Decision

Every non-obvious decision is recorded as a numbered ADR in `docs/adr/`, written at the time the
decision is made rather than reconstructed afterwards.

A decision is "non-obvious" if any of the following holds:

- a reasonable engineer would have picked differently,
- it trades one desirable property for another,
- it deviates from what the Raft paper or a well-known implementation does,
- undoing it later would be expensive.

Writing an ADR is part of the definition of done for a development phase, not a separate
documentation task.

## Alternatives considered

**Comments in the code.** Best for local "why", useless for decisions that span modules, and they
cannot record the alternative that was rejected. Used in addition, not instead.

**A single design document.** Turns into a narrative that nobody updates, and it hides the moment
at which a decision was made. Individual dated records preserve the chronology.

**Nothing, and rely on the commit history.** Commit messages describe a change; they do not
survive as a searchable record of *why the system is the way it is*, and squash merges lose them.

## Consequences

- The repository accumulates roughly 25–30 records over the project. That is the intended volume.
- Records are immutable. A superseded decision keeps its file and gains a pointer forward, which
  preserves the history of how the design evolved.
- There is a small ongoing cost per decision. It is paid back the first time someone asks
  "why is this like that?" in a code review or an interview.
