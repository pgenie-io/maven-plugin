# Handoff: pgenie-maven-plugin v1 implementation

## Status: COMPLETE and pushed

The v1 implementation of `io.pgenie:pgenie-maven-plugin` is fully built, reviewed, and pushed to
`origin/master`. There is no in-progress work to resume unless the user asks for one of the
tracked follow-ups below or reports a new issue.

## Where things live

- Repo: `/Users/mojojojo/repos/pgenie/maven-plugin` — its own git repo (not a submodule of the
  outer `pgenie` workspace), remote `https://github.com/pgenie-io/maven-plugin.git`.
- Spec: `docs/spec.md` (agreed 2026-07-17).
- Implementation plan (14 tasks): `docs/plans/2026-07-17-v1-implementation.md`.
- Full execution ledger with per-task detail, every deviation, and every human decision:
  `.superpowers/sdd/progress.md` — **read this first** for the complete history; this handoff
  does not duplicate it.
- Final state: branch `master`, HEAD `106b860`, 20 commits ahead of where the spec-only commit
  (`cf78163`) started, all pushed. `git status` is clean except a pre-existing untracked
  `docs/plans/` (present before this session, intentionally never added — it's the plan file
  itself referenced above, tracked as a doc but not part of any commit's file set by design).

## How it was built

Executed via `superpowers:subagent-driven-development`: one fresh implementer subagent per task,
a task-scoped reviewer (spec compliance + quality) after each, fix-and-re-review loops for
anything the reviewer flagged, and a final whole-branch review (opus) at the end. Every
plan-vs-code conflict or cross-cutting finding was surfaced to the human for a decision rather
than resolved unilaterally — see the ledger for the full list of what was asked and decided.

## What's built

A Maven plugin (goal `pgenie:generate`, bound to `generate-sources`) that: downloads and
checksum-verifies a pinned `pgn` binary, synthesizes a pGenie project (`project1.pgn.yaml` /
`freeze1.pgn.yaml`) from the consumer's pom, stages SQL inputs, runs `pgn generate`, copies back
signature files, attaches generated Java as a compile source root, and validates the consumer
declares the required `io.codemine.java.postgresql:jdbc` dependency. Covered by 26 unit tests, 5
stub-based `maven-invoker-plugin` ITs, and one real end-to-end IT (`-Pe2e`, actual Docker +
GitHub + Maven Central).

## Known open items (tracked, not blockers, not yet actioned)

1. **Upstream gap (external, not this repo's bug):** `java.gen#9` (Java-8 template downgrade)
   hasn't landed. The certified triple currently compiles generated code at
   `maven.compiler.release=16`, not the spec's target of 8. This is documented in `README.md`
   (consumer-facing Requirements section + maintainers' Releasing section) and in
   `.github/workflows/e2e.yaml`'s JDK 21 setup. Restoring release 8 is called out as a release
   blocker once java.gen#9 ships and a Java-8-compatible jdbc release exists.
2. **Minor findings from the final review, deliberately left unfixed per user decision** (see
   ledger for full context):
   - `src/it-e2e/certification/pom.xml` pins `io.codemine.java.postgresql:jdbc` to the
     non-reproducible `RELEASE` metaversion instead of a concrete version.
   - No integration test proves "source-root attachment + dependency check still run even when
     the up-to-date digest matches" survives a future regression (a bug that moved that logic
     inside the skip-branch would currently pass all tests).
   - `docs/spec.md` still mentions the superseded `genConfig` parameter and "release 8" instead
     of `useOptional` and the current release-16 reality — stale-doc drift, not a code defect.
3. **One intentional non-fix:** `GenerateMojo.skip` lacks `readonly = true` unlike the other four
   properties-only knobs (`databaseUrl`/`reuseContainer`/`pgnExecutable`/`force`), so it CAN be
   set via pom `<configuration>`, technically at odds with the plan's own Global Constraints
   wording. The user explicitly decided to leave this as-is (constraint list treated as
   overstated, not the code as buggy) — do not re-flag this as a defect if revisiting the code.

## If the next session's task is...

- **One of the tracked minor items above:** each is small and self-contained; no need for the
  full subagent-driven-development ceremony — direct implementation + a normal review pass is
  proportionate.
- **The java.gen#9 upstream gap landing:** that's cross-repo work (`java.gen`, `pgenie` core,
  a Java-8 jdbc release) outside this repo; this repo's side of the work is just bumping
  `Pins.java`'s pinned versions/checksums and reverting `maven.compiler.release` back to 8 in the
  certification pom, then re-running the real e2e IT (`mvn verify -Pe2e`, needs Docker + network).
- **A new feature or v2 scope:** start with `superpowers:brainstorming`, then likely
  `superpowers:writing-plans` before any implementation skill — this session's plan/spec pair is
  a good template for structure but v2 scope hasn't been discussed.
- **Something appears broken:** `superpowers:systematic-debugging` before proposing fixes, as
  usual.

## Suggested skills for the next session

- **`superpowers:subagent-driven-development`** — only if picking up a multi-step follow-on plan;
  overkill for a single small fix.
- **`superpowers:requesting-code-review`** — for any standalone fix, to get a review pass without
  the full task-brief/ledger ceremony.
- **`superpowers:brainstorming`** then **`superpowers:writing-plans`** — if scoping v2 or new
  functionality.
- **`superpowers:systematic-debugging`** — if the user reports something broken rather than
  requesting new work.
- **`superpowers:using-git-worktrees`** — the user chose to work directly on `master` for v1 (no
  worktree). Ask again for any new work rather than assuming the same preference holds.
