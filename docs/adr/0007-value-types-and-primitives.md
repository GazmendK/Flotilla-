# ADR-0007: Wrapper types where they prevent bugs, primitives where they do not

- Status: Accepted
- Date: 2026-08-05

## Context

The core's vocabulary consists of a handful of values that appear everywhere: node identifiers,
terms, log indices and opaque payloads. Wrapping each of them in a dedicated type is the
"primitive obsession" cure and prevents whole classes of mix-up. It also allocates, and terms and
indices are touched on every message.

The question is not whether wrapper types are good in general, but which of these four actually
buy something.

## Decision

**Wrapped**, because the wrapper prevents a concrete defect:

- `Bytes` — a `byte[]` cannot live in a record. Records derive `equals` and `hashCode` from their
  components, and for arrays those are identity-based: two log entries with identical payloads
  would compare unequal, and the array would stay mutable through the accessor. Both are
  unacceptable for values that are compared, hashed and shared across threads.
- `NodeId` — a bare `String` would be validated nowhere and could be confused with any other
  string in scope. Node identifiers end up in file names, metric labels and log lines, so the
  validation has to live somewhere; a record's compact constructor is the one place it cannot be
  forgotten.

**Primitive `long`**, because the wrapper would not:

- `term` and `index` are monotonic counters used in arithmetic (`index + 1`, `lastIndex() - 1`)
  and compared constantly. Wrapping them means allocation on paths that run per message, and
  arithmetic that reads worse than the paper it implements.

## Alternatives considered

**Wrapping term and index too**, e.g. `record Term(long value)`. The genuine benefit is real:
transposing a term and an index compiles cleanly today and produces a subtle bug. This was the
closest call in this ADR.

It was rejected on balance rather than on principle, and the mitigations are explicit:

- every method that takes both declares them as named record components or named parameters, so
  call sites read `LogEntry.normal(term, index, data)` rather than a bare pair of numbers;
- the argument order is `(term, index)` everywhere, without exception;
- the validation in `LogEntry` rejects an index below 1 and a negative term, which catches a
  transposition in most real cases, because index 0 is not a legal entry index.

**Waiting for Valhalla value classes.** When value classes are generally available the trade-off
disappears: `Term` and `Index` would be free. This ADR should be revisited then, and the change
would be mechanical.

## Consequences

- Allocation on the hot path is limited to `Bytes` payloads, which have to exist anyway.
- The transposition risk is real and accepted. If it ever produces an actual bug, that is the
  evidence needed to supersede this ADR rather than argue about it.
- `Bytes.wrap` exists as an explicit ownership transfer for decoding paths. It is a sharp edge
  and documented as one; `Bytes.copyOf` is the default.
