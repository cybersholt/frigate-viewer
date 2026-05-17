---
name: refactor
description: Refactor playbook. Constrain scope, preserve behavior, leave tests green.
---

# Refactor playbook

## Before
1. State the goal in one sentence. If it has "and", split it.
2. Identify the public API surface that must not change. Write it down.
3. Find the existing test coverage. If <70% on the target code, add characterization tests first.

## During
- One commit = one mechanical change. No drive-by reformatting.
- Never combine "rename" + "behavior change" in the same commit.
- If a change touches more than 3 files, justify why a wrapper / facade isn't a smaller alternative.
- Don't introduce abstractions for hypothetical second callers. Wait for the second caller.

## Stop conditions
- Test fails that wasn't failing before → stop, investigate, revert if root cause unclear.
- Diff exceeds the original goal → stop, split.
- New TODO appears in code → either fix it or open a ticket. Don't accumulate.

## After
- Run `./gradlew :app:lintDebug :app:testDebugUnitTest`.
- Update `ARCHITECTURE.md` if module layout changed.
- If a public type was renamed, search for it across `docs/` too.

## Anti-patterns specific to this repo
- Don't replace `safeApiCall` with try/catch. The sealed `ApiResult` type is the contract.
- Don't move credentials into DataStore "just for convenience".
- Don't add a `Json { isLenient = true }` instance elsewhere — there is one in `AppModule`, reuse it.
