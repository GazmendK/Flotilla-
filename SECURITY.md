# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository, not through a public issue.

Include what you did, what you expected, and what happened. If the issue involves a specific
cluster state, the seed of a simulation run that reproduces it is the most useful thing you can
send.

You can expect an acknowledgement within a week. This is a personal project, not a vendor with an
on-call rotation — please calibrate expectations accordingly.

## Supported versions

Only the latest release is supported. Before 1.0.0, that means the most recent `0.x.0` tag.

## Threat model

Flotilla assumes a **crash-recovery, non-Byzantine** failure model with fair-loss links:

- Nodes may crash at any point and return with only the state they had synced to disk.
- Messages may be lost, delayed, duplicated or reordered.
- Nodes do not lie. A peer that sends a malformed or malicious message is **out of scope**.

Consequences of that model, which are working as intended rather than vulnerabilities:

- A node that can reach a majority can influence consensus. Cluster membership is the security
  boundary; use mTLS (from Phase 14 onwards) to control who may join.
- Losing a majority of nodes makes the cluster unavailable. That is the intended trade-off, not a
  denial of service bug.
- Running with `fsync` disabled can lose acknowledged writes on power failure. This is documented
  and warned about at startup; configuring it that way is a deliberate choice.
- Debug and admin endpoints bind to loopback by default. Exposing them publicly is a
  misconfiguration, and the documentation says so.

In scope, and worth reporting: memory-safety-adjacent issues such as unbounded allocation from a
malformed record or message, a parser that can be made to consume unbounded resources, a crash
that leaves on-disk state unrecoverable, authentication or TLS handling flaws, and any way to
violate linearizability that does not require a Byzantine participant.
