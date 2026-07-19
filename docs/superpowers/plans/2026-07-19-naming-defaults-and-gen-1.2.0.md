# Plugin-wide naming defaults + java.gen v1.2.0 pin bump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GenerateMojo` pass the consumer's own Maven `groupId`/`artifactId` to `java.gen`'s `Config` by default (replacing the old `io.pgenie.artifacts.*` hardcoded namespace), add an optional `<rootPackage>` override parameter, and bump the plugin's baked-in `java.gen` pin from v1.1.0 to v1.2.0 — the generator release that added these `Config` fields.

**Architecture:** `java.gen` v1.2.0 (already tagged and released) added optional `groupId`/`artifactId`/`rootPackage` fields to its Dhall `Config` record; when `groupId`/`artifactId` are set without `rootPackage`, it derives the root Java package from them (Maven convention: dashes stripped from artifactId). This plugin synthesizes `project1.pgn.yaml` (which embeds that `Config` as YAML) via `ProjectYaml.project(...)`; that method gains three new parameters, and `GenerateMojo` is changed to always supply `project.getGroupId()`/`project.getArtifactId()` and a new optional `rootPackage` pom parameter.

**Tech Stack:** Java, Maven, `maven-plugin-plugin` annotations, JUnit 5, `maven-invoker-plugin` for `src/it/*` integration tests.

## Global Constraints

- `java.gen` v1.2.0 release asset: `https://github.com/pgenie-io/java.gen/releases/download/v1.2.0/resolved.dhall`, Dhall import-integrity hash `sha256:0cc4c6c31bfd2513fd6191b7fe7d986af91eea909995e07c1ea97bfd639cdea3` (independently verified with `dhall hash --file <downloaded-asset>` — **not** a raw-bytes `shasum` of the file; Dhall's integrity check hashes the alpha/beta-normalized CBOR-encoded AST, which differs from the file's byte hash. Confirmed by running `dhall hash` against the existing v1.1.0 pin and matching `Pins.java`'s pre-existing value before trusting the method for v1.2.0. Do not trust this value without re-verifying it yourself the same way.).
- `GenerateMojo` must always pass `project.getGroupId()` and `project.getArtifactId()` as the generated `Config`'s `groupId`/`artifactId` — raw, unsanitized (these are valid Maven coordinates already; unlike `space`/`name` they are not run through `Sanitizer`).
- The new `<rootPackage>` pom parameter is optional; when unset, no `rootPackage` line is emitted in the generated YAML (java.gen derives it from `groupId`/`artifactId` on its own).
- No change to the `space`/`name` parameters or their defaulting (`Sanitizer.name(groupId)` / `Sanitizer.name(artifactId)`) — those remain the pGenie project identity, orthogonal to the new Java package/Maven-coordinate naming.
- This is a non-breaking, backwards-compatible change: existing consumers who set neither `groupId` nor `artifactId` override in java.gen's `Config` would previously get `io.pgenie.artifacts.<space>.<name>`; after this change they get their own Maven coordinates' package instead. This is an intentional default-behavior change for **this plugin's own users** (java.gen's own default behavior is unchanged — java.gen still defaults to `io.pgenie.artifacts.*` when its `Config.groupId`/`artifactId` are unset, which no longer happens here because this plugin always sets them).

---

### Task 1: `ProjectYaml.project` emits `groupId`/`artifactId`/`rootPackage`

**Files:**
- Modify: `src/main/java/io/pgenie/maven/ProjectYaml.java`
- Test: `src/test/java/io/pgenie/maven/ProjectYamlTest.java`

**Interfaces:**
- Produces: `ProjectYaml.project(String space, String name, String version, int postgres, String genUrl, boolean useOptional, String groupId, String artifactId, String rootPackage)` — `rootPackage` may be `null` (omit the line); `groupId`/`artifactId` are never `null`.

- [ ] **Step 1: Write the failing tests**

Replace the existing `rendersProjectFile` test and add a new one in `src/test/java/io/pgenie/maven/ProjectYamlTest.java`:

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProjectYamlTest {
  @Test void rendersProjectFile() {
    String yaml = ProjectYaml.project(
        "io_pgenie", "music", "1.2.3", 18, "https://example.com/g.dhall", true,
        "com.example.music", "catalogue-lib", null);
    assertEquals(
        "space: io_pgenie\n"
            + "name: music\n"
            + "version: 1.2.3\n"
            + "postgres: 18\n"
            + "artifacts:\n"
            + "  java:\n"
            + "    gen: https://example.com/g.dhall\n"
            + "    config:\n"
            + "      useOptional: true\n"
            + "      groupId: com.example.music\n"
            + "      artifactId: catalogue-lib\n",
        yaml);
  }

  @Test void rendersProjectFileWithRootPackageOverride() {
    String yaml = ProjectYaml.project(
        "io_pgenie", "music", "1.2.3", 18, "https://example.com/g.dhall", true,
        "com.example.music", "catalogue-lib", "com.example.music.catalogue");
    assertEquals(
        "space: io_pgenie\n"
            + "name: music\n"
            + "version: 1.2.3\n"
            + "postgres: 18\n"
            + "artifacts:\n"
            + "  java:\n"
            + "    gen: https://example.com/g.dhall\n"
            + "    config:\n"
            + "      useOptional: true\n"
            + "      groupId: com.example.music\n"
            + "      artifactId: catalogue-lib\n"
            + "      rootPackage: com.example.music.catalogue\n",
        yaml);
  }

  @Test void rendersFreezeFile() {
    assertEquals(
        "https://example.com/g.dhall: sha256:abc123\n",
        ProjectYaml.freeze("https://example.com/g.dhall", "abc123"));
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl . test -Dtest=ProjectYamlTest` (from the `maven-plugin` repo root)
Expected: FAIL — compile error, `ProjectYaml.project` has the wrong arity.

- [ ] **Step 3: Implement**

Replace `src/main/java/io/pgenie/maven/ProjectYaml.java` in full:

```java
package io.pgenie.maven;

/**
 * Synthesizes project1.pgn.yaml and freeze1.pgn.yaml. All values are
 * sanitized identifiers, integers, booleans, or URLs, so plain
 * concatenation is valid YAML — no escaping layer needed.
 */
final class ProjectYaml {
  static String project(
      String space, String name, String version, int postgres, String genUrl, boolean useOptional,
      String groupId, String artifactId, String rootPackage) {
    StringBuilder config = new StringBuilder();
    config.append("      useOptional: ").append(useOptional).append("\n");
    config.append("      groupId: ").append(groupId).append("\n");
    config.append("      artifactId: ").append(artifactId).append("\n");
    if (rootPackage != null) {
      config.append("      rootPackage: ").append(rootPackage).append("\n");
    }
    return "space: " + space + "\n"
        + "name: " + name + "\n"
        + "version: " + version + "\n"
        + "postgres: " + postgres + "\n"
        + "artifacts:\n"
        + "  java:\n"
        + "    gen: " + genUrl + "\n"
        + "    config:\n"
        + config;
  }

  static String freeze(String genUrl, String genSha256) {
    return genUrl + ": sha256:" + genSha256 + "\n";
  }

  private ProjectYaml() {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl . test -Dtest=ProjectYamlTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/ProjectYaml.java src/test/java/io/pgenie/maven/ProjectYamlTest.java
git commit -m "ProjectYaml: emit groupId/artifactId/rootPackage in generated Config"
```

---

### Task 2: `GenerateMojo` passes Maven coordinates by default, adds `<rootPackage>`

**Files:**
- Modify: `src/main/java/io/pgenie/maven/GenerateMojo.java`
- Modify: `src/it/happy-path/verify.groovy`
- Create: `src/it/root-package-override/pom.xml`
- Create: `src/it/root-package-override/invoker.properties`
- Create: `src/it/root-package-override/verify.groovy`
- Create: `src/it/root-package-override/fake-jdbc.jar` (binary copy)
- Create: `src/it/root-package-override/src/main/pgenie/migrations/0001-init.sql`
- Create: `src/it/root-package-override/src/main/pgenie/queries/select_one.sql`
- Modify: `README.md`

**Interfaces:**
- Consumes: `ProjectYaml.project(space, name, version, postgres, genUrl, useOptional, groupId, artifactId, rootPackage)` from Task 1.
- Produces: new `GenerateMojo.rootPackage` field (`@Parameter`, no `defaultValue`, may be `null`).

- [ ] **Step 1: Update the mojo**

In `src/main/java/io/pgenie/maven/GenerateMojo.java`, add a new field after the existing `name` field:

```java
  /** pGenie project name; defaults to the sanitized artifactId. */
  @Parameter(property = "pgenie.name")
  String name;

  /**
   * Override of the root Java package for generated code. Defaults to a package
   * derived from this project's groupId/artifactId (Maven convention, dashes
   * stripped from artifactId) — set this only when you want a package
   * independent of your Maven coordinates.
   */
  @Parameter
  String rootPackage;

```

Change the `projectYaml` construction inside `execute()` from:

```java
    String projectYaml =
        ProjectYaml.project(effectiveSpace, effectiveName, version, postgres, genUrl, useOptional);
```

to:

```java
    String projectYaml =
        ProjectYaml.project(
            effectiveSpace, effectiveName, version, postgres, genUrl, useOptional,
            project.getGroupId(), project.getArtifactId(), rootPackage);
```

- [ ] **Step 2: Extend the happy-path IT to assert the new defaults**

In `src/it/happy-path/verify.groovy`, add after the existing `useOptional` assertion:

```groovy
assert projectYaml.contains('groupId: io.pgenie.it')
assert projectYaml.contains('artifactId: happy-path')
```

(`io.pgenie.it` / `happy-path` are `src/it/happy-path/pom.xml`'s own `groupId`/`artifactId` — unchanged by this task.)

- [ ] **Step 3: Add a new IT exercising the `<rootPackage>` override**

Create `src/it/root-package-override/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.pgenie.it</groupId>
  <artifactId>root-package-override</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencies>
    <dependency>
      <groupId>io.codemine.java.postgresql</groupId>
      <artifactId>jdbc</artifactId>
      <version>1.4.0</version>
      <scope>system</scope>
      <systemPath>${basedir}/fake-jdbc.jar</systemPath>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.pgenie</groupId>
        <artifactId>pgenie-maven-plugin</artifactId>
        <version>@project.version@</version>
        <configuration>
          <rootPackage>com.example.override</rootPackage>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>generate</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Create `src/it/root-package-override/invoker.properties`:

```
invoker.os.family = !windows
```

(No `invoker.goals` override — defaults to `generate-sources`, which is enough to trigger the mojo without needing app source to compile.)

Create `src/it/root-package-override/verify.groovy`:

```groovy
File staging = new File(basedir, 'target/pgenie/staging')
String projectYaml = new File(staging, 'project1.pgn.yaml').text
assert projectYaml.contains('groupId: io.pgenie.it')
assert projectYaml.contains('artifactId: root-package-override')
assert projectYaml.contains('rootPackage: com.example.override')
return true
```

Create the migration/query fixtures (copy the two files verbatim from `src/it/happy-path/src/main/pgenie/`):

```bash
mkdir -p src/it/root-package-override/src/main/pgenie/migrations src/it/root-package-override/src/main/pgenie/queries
cp src/it/happy-path/src/main/pgenie/migrations/0001-init.sql src/it/root-package-override/src/main/pgenie/migrations/0001-init.sql
cp src/it/happy-path/src/main/pgenie/queries/select_one.sql src/it/root-package-override/src/main/pgenie/queries/select_one.sql
cp src/it/happy-path/fake-jdbc.jar src/it/root-package-override/fake-jdbc.jar
```

- [ ] **Step 4: Run the invoker ITs to verify**

Run: `mvn -q verify` (from the `maven-plugin` repo root; this runs `mvn install` + `invoker:integration-test` + `invoker:verify` per the `maven-invoker-plugin` binding in `pom.xml`)
Expected: `BUILD SUCCESS`. If a specific IT fails, its log is at `target/it/<project-name>/build.log`.

- [ ] **Step 5: Update README**

In `README.md`'s Configuration table, replace the `space`/`name` rows' surrounding context is unaffected, but add a new row after `useOptional`:

```
| `rootPackage` | derived from groupId/artifactId | override the root Java package for generated code |
```

So the table reads:

```
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
```

Also add a short paragraph right after the Quick start code block (before "## Configuration"), documenting the naming default change:

```markdown
Generated code's Java package and Maven coordinates default to this project's
own `groupId`/`artifactId` (dashes stripped from the artifactId, per Maven
convention) — e.g. `groupId=com.example`, `artifactId=catalogue-lib` yields
package `com.example.cataloguelib`. Set `<rootPackage>` to use a package
independent of your Maven coordinates instead.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/pgenie/maven/GenerateMojo.java src/it/happy-path/verify.groovy src/it/root-package-override README.md
git commit -m "GenerateMojo: default generated code's package/coordinates to the consumer's own, add <rootPackage> override"
```

---

### Task 3: Bump the baked-in `java.gen` pin to v1.2.0

**Files:**
- Modify: `src/main/java/io/pgenie/maven/Pins.java`

**Interfaces:**
- None (internal constants only; `GenerateMojo` already reads `Pins.GEN_URL`/`Pins.GEN_SHA256`).

- [ ] **Step 1: Update the pin**

In `src/main/java/io/pgenie/maven/Pins.java`, change:

```java
  static final String GEN_URL =
      "https://github.com/pgenie-io/java.gen/releases/download/v1.1.0/resolved.dhall";
  static final String GEN_SHA256 =
      "1f08d67dc1286a818ecc6eb28d360fb5b4ab7af694afc98950f85e47c338c101";
```

to:

```java
  static final String GEN_URL =
      "https://github.com/pgenie-io/java.gen/releases/download/v1.2.0/resolved.dhall";
  static final String GEN_SHA256 =
      "0cc4c6c31bfd2513fd6191b7fe7d986af91eea909995e07c1ea97bfd639cdea3";
```

Independently re-verify this hash before committing, the same way the plugin's other pins were verified: download the asset and hash it with the Dhall CLI, **not** `shasum` — Dhall's import integrity check hashes the semantic (alpha/beta-normalized, CBOR-encoded) form of the expression, not the file's raw bytes, so a plain `shasum -a 256` of the downloaded file gives a different, wrong value (confirm this yourself by comparing `dhall hash` against `shasum` on the existing v1.1.0 asset — `dhall hash` matches the pre-existing `Pins.java` pin; `shasum` does not).

```bash
curl -sL https://github.com/pgenie-io/java.gen/releases/download/v1.2.0/resolved.dhall -o /tmp/resolved-v1.2.0.dhall
dhall hash --file /tmp/resolved-v1.2.0.dhall
```

Confirm the printed hash matches `sha256:0cc4c6c31bfd2513fd6191b7fe7d986af91eea909995e07c1ea97bfd639cdea3` before proceeding — if it does not match, STOP and escalate rather than committing an unverified pin.

- [ ] **Step 2: Run the full test suite and non-e2e ITs**

Run: `mvn -q verify` (from the `maven-plugin` repo root)
Expected: `BUILD SUCCESS`. The default `mvn verify` does not run the `e2e` profile's `it-e2e/certification` project (which needs network + Docker to actually invoke `pgn`), so this does not download/exercise the real v1.2.0 generator end-to-end — that only happens under `-Pe2e`, which is a maintainer release step (see README's "Releasing" section) and out of scope to run here.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/pgenie/maven/Pins.java
git commit -m "Bump baked-in java.gen pin to v1.2.0"
```

---

## Post-plan: push to origin

This repo's `master` was already confirmed up to date with `origin/master` before this plan was written, and has no other pending local-only work. After all three tasks are committed and reviewed, push:

```bash
git push origin master
```

`java.demo`'s migration (separate plan, separate repo) vendors this repo as a git submodule pinned to a specific commit — it needs these commits present on `origin/master` to pin against, not a tagged release.
