# pgenie-maven-plugin — v1 Specification

Agreed 2026-07-17.

## Identity

- **Coordinates:** `io.pgenie:pgenie-maven-plugin`, goal prefix `pgenie`.
- **Home:** `pgenie-io/maven-plugin`, published to Maven Central under the verified `io.pgenie` namespace.
- **Platform:** plugin bytecode targets JDK 11; minimum Maven 3.6.3.
- **Generated code** targets Java 8. Contingent on adjacent work:
  [java.gen#9](https://github.com/pgenie-io/java.gen/issues/9) (template downgrade)
  and a Java-8-bytecode release of `io.codemine.java.postgresql:jdbc`.

## Model

Source-injection: the plugin runs `pgn generate` during the build and attaches the
generated Java sources as an extra compile source root of the consumer's own module.
The consumer's pom is the **sole source of truth** for the pGenie project definition —
there is no `project1.pgn.yaml` in the repo; the plugin synthesizes one. SQL inputs
(`migrations/`, `queries/`, `types/`) live under `src/main/pgenie/` by default.

A plugin release is a certified triple: (plugin, pinned `pgn` version, pinned
`java.gen` release). Both pins and their sha256 checksums are baked into the plugin;
there is no `pgnVersion` parameter.

## Goal

One goal, `pgenie:generate`, default-bound to `generate-sources`.
Out of scope for v1: `analyse`/`manage-indexes` goals, project-file mode for polyglot
repos, attaching generated tests, injecting dependencies into the consumer's model.

## Execution steps

1. **Up-to-date check.** Digest of `src/main/pgenie/**`, the effective plugin
   configuration, and the baked-in pins is compared against `target/pgenie/digest`.
   On match, skip everything. `-Dpgenie.force` bypasses. (Caveat documented: with an
   external `--database-url`, server schema drift is invisible to the digest.)
2. **Binary provisioning.** Download the pinned `pgn` for the detected platform
   (`linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`) from
   GitHub release assets into a `~/.m2/pgenie/<version>/` cache; verify the baked-in
   sha256. `-Dpgenie.pgnExecutable` bypasses download entirely. Actionable errors for
   missing libpq (`brew install libpq` / `apt-get install libpq5`) and unsupported
   platforms. Windows path handles `pgn.exe` naming and extraction.
   Maven-Central classifier-artifact distribution is a deferred follow-up (see issues).
3. **Staging.** Into `target/pgenie/staging/`:
   - synthesize `project1.pgn.yaml` — `space`, `name`, `version` (from
     `${project.version}`, not user-facing), `postgres`, one artifact `java`
     with the gen URL and `genConfig` rendered to YAML;
   - synthesize `freeze1.pgn.yaml` from the baked-in (or overridden) gen sha256, so
     pgn's own lock verification enforces the pin — no freeze file in the source tree;
   - copy `migrations/`, `queries/`, `types/` from `src/main/pgenie/`.
   Staging persists across builds (dies on `clean`).
4. **Run** `pgn generate` with staging as the working directory, passing
   `--database-url` / `--reuse-container` when set (Docker mode otherwise) and
   `--fail-on-seq-scans` when configured.
5. **Copy-back.** New or changed `*.sig1.pgn.yaml` under `queries/` and `types/` are
   copied back into `src/main/pgenie/` — the only source-tree mutation. Docs:
   "the build may add signature files; commit them."
6. **Attach** `target/pgenie/staging/artifacts/java/src/main/java` as a compile
   source root. The generated `pom.xml` and `src/test/java` are ignored.
7. **Dependency check.** Fail with a copy-pasteable `<dependency>` snippet if
   `io.codemine.java.postgresql:jdbc` is absent from the consumer's compile
   dependencies; warn on version mismatch against the generated pom.

## Configuration

Pom parameters:

| Parameter | Default |
|---|---|
| `space` | sanitized `${project.groupId}` (fail if unsanitizable) |
| `name` | sanitized `${project.artifactId}` |
| `postgres` | pgn's default major (18) |
| `genConfig` | empty; free-form XML subtree rendered to YAML |
| `gen` / `genSha256` | baked-in java.gen release; overriding `gen` requires `genSha256` |
| `pgnProjectDirectory` | `src/main/pgenie` |
| `failOnSeqScans` | `false` |
| `skip` | `false` (also `-Dpgenie.skip`) |

Properties-only (per-machine / per-run, never invited into `<configuration>`):
`pgenie.databaseUrl`, `pgenie.reuseContainer`, `pgenie.pgnExecutable`,
`pgenie.force`, `pgenie.skip`.

## Testing

- **Stub-first:** maven-invoker-plugin ITs against a fake `pgn` script injected via
  `pgenie.pgnExecutable`, emitting canned artifacts and signature files. Covers
  staging, yaml synthesis, copy-back, digest skip, dependency check, error paths.
  Fast, no Docker, no network.
- **One real end-to-end IT** as release certification of the pinned triple: real
  download, Docker Postgres, generated output compiled with `--release 8`.
  Profile-gated locally, runs in CI.
