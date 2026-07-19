# pgenie-maven-plugin

Generates typed Java data-access code from your PostgreSQL migrations and
queries at build time, via [pGenie](https://pgenie.io).

## Quick start

Put your SQL under `src/main/pgenie/`:

    src/main/pgenie/
      migrations/   -- schema migrations, applied in lexical order
      queries/      -- one .sql file per query
      types/        -- optional custom type definitions

Add to your pom:

    <plugin>
      <groupId>io.pgenie</groupId>
      <artifactId>pgenie-maven-plugin</artifactId>
      <version>1.0.0</version>
      <executions>
        <execution>
          <goals><goal>generate</goal></goals>
        </execution>
      </executions>
    </plugin>

and the runtime dependency:

    <dependency>
      <groupId>io.codemine.java.postgresql</groupId>
      <artifactId>jdbc</artifactId>
      <version><!-- reported by the build if missing --></version>
    </dependency>

`mvn compile` downloads a pinned, checksum-verified `pgn` binary, runs it
against a disposable Docker Postgres, and compiles the generated sources as
part of your module. The build may add `*.sig1.pgn.yaml` signature files
under `src/main/pgenie/` — commit them.

Generated code's Java package and Maven coordinates default to this project's
own `groupId`/`artifactId` (dashes stripped from the artifactId, per Maven
convention) — e.g. `groupId=com.example`, `artifactId=catalogue-lib` yields
package `com.example.cataloguelib`. Set `<rootPackage>` to use a package
independent of your Maven coordinates instead.

## Configuration

| Parameter | Default | |
|---|---|---|
| `space` | sanitized groupId | pGenie namespace |
| `name` | sanitized artifactId | pGenie project name |
| `postgres` | `18` | PostgreSQL major to validate against |
| `useOptional` | `false` | Use Optional for nullable fields |
| `rootPackage` | derived from groupId/artifactId | override the root Java package for generated code |
| `gen` / `genSha256` | pinned java.gen | generator override (both required together) |
| `pgnProjectDirectory` | `src/main/pgenie` | SQL input root |
| `failOnSeqScans` | `false` | fail the build on sequential scans |
| `skip` | `false` | skip generation |

Per-machine properties (command line or ~/.m2/settings.xml, not pom):

| Property | |
|---|---|
| `-Dpgenie.databaseUrl` | validate against an existing server instead of Docker |
| `-Dpgenie.reuseContainer` | keep a named container across builds |
| `-Dpgenie.pgnExecutable` | use a local pgn binary, skip download |
| `-Dpgenie.force` | ignore the up-to-date check |
| `-Dpgenie.skip` | skip generation |

Note: with `-Dpgenie.databaseUrl`, schema changes made directly on that
server are invisible to the up-to-date check — use `-Dpgenie.force` after
out-of-band schema changes.

## Requirements

Maven 3.6.3+, JDK 11+ to build the plugin itself. Docker (default mode) or a
reachable PostgreSQL (`-Dpgenie.databaseUrl`). libpq on the host:
`brew install libpq` (macOS) / `apt-get install libpq5` (Debian/Ubuntu).

**Compiling generated code currently requires JDK 16+.** The certified
`java.gen` release emits records and text blocks, so consuming modules need
`maven.compiler.release=16` (or higher) and a JDK ≥16 on the build machine —
JDK 11 is enough to build the plugin, but not to compile the code it
generates. This is a known gap (java.gen#9); the plugin will support Java 8
output once that lands. See "Releasing (maintainers)" below for details.

## Releasing (maintainers)

Each release certifies a (plugin, pgn, java.gen) triple pinned in
`Pins.java`. To release: update pins if needed, run `mvn verify -Pe2e`,
tag `vX.Y.Z`, push the tag. Requires repo secrets `CENTRAL_USERNAME`,
`CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` (create these once,
as a manual owner step, before the first tag push) — the `release` workflow
picks up the tag and runs `mvn deploy -Prelease`.

Known gap: the certified triple's generated code currently compiles at
`maven.compiler.release=16`, not the target Java 8, because java.gen does
not yet emit Java-8-compatible code (tracked as java.gen#9). The e2e
certification IT (and its CI job, `e2e.yaml`) runs on JDK 21 to accommodate
this. Once java.gen#9 lands and a Java-8-compatible `jdbc` runtime is
released, this should be revisited so the certified triple targets Java 8
as originally intended.
