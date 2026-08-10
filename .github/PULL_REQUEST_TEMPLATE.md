# What

<!-- One or two sentences. The diff shows the rest. -->

# Why

<!-- The reason this change exists. If it fixes a bug, describe how the bug could occur --
     ideally as a sequence of events, not as "X was wrong". -->

# Trade-offs

<!-- What was given up, and what was deliberately not done. If a simpler approach was rejected,
     say which one and why. If nothing was traded away, say so. -->

# How this was verified

<!-- Which tests cover it. For consensus changes: which simulation scenarios, and how many seeds.
     "It compiles" is not verification. -->

# Checklist

- [ ] `./gradlew build` passes
- [ ] New behaviour has tests; consensus changes have simulation coverage
- [ ] Javadoc on new public types, `package-info.java` for new packages
- [ ] Affected `docs/` updated in this PR
- [ ] ADR added if a non-obvious decision was made
- [ ] `CHANGELOG.md` entry under `[Unreleased]`
- [ ] No `TODO` without a linked issue
