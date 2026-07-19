# pgenie-maven-plugin v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Maven plugin (`io.pgenie:pgenie-maven-plugin`, goal `pgenie:generate`) that runs a pinned `pgn generate` during `generate-sources` and attaches the generated Java as a compile source root, with the consumer's pom as the sole project definition.

**Architecture:** One Mojo orchestrating small package-private helper classes, each pure or near-pure and unit-testable: name sanitization, platform detection, YAML synthesis, digest-based up-to-date check, binary provisioning, process execution, signature copy-back, dependency check. Stub-first integration tests via maven-invoker-plugin with a fake `pgn` script; one profile-gated real end-to-end IT.

**Tech Stack:** Java 11, Maven plugin API 3.6.3 + maven-plugin-annotations, `org.apache.commons:commons-compress` (tar.gz extraction), JUnit 5, maven-invoker-plugin with Groovy verify scripts.

## Global Constraints

- Coordinates `io.pgenie:pgenie-maven-plugin`, goal prefix `pgenie`, goal `pgenie:generate` default-bound to `generate-sources`.
- Plugin bytecode targets **JDK 11** (`maven.compiler.release=11`); minimum Maven **3.6.3**.
- Baked-in certified triple for development: **pgn v0.13.0** + **java.gen v1.1.0** (`https://github.com/pgenie-io/java.gen/releases/download/v1.1.0/resolved.dhall`, sha256 `1f08d67dc1286a818ecc6eb28d360fb5b4ab7af694afc98950f85e47c338c101`). Bump before release when java.gen#9 (Java-8 templates) lands.
- pgn release assets: `https://github.com/pgenie-io/pgenie/releases/download/v<ver>/pgn-<platform>.tar.gz` for platforms `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`.
- No `genConfig` free-form parameter — java.gen's only config option is lifted to a `useOptional` boolean plugin parameter (agreed 2026-07-17, supersedes spec's `genConfig` row).
- Name sanitization: lowercase, `.`/`-` → `_`, result must match `[a-z][a-z0-9_]*`, else fail.
- The only source-tree mutation is copy-back of `*.sig1.pgn.yaml` files.
- Properties-only knobs (never pom `<configuration>`): `pgenie.databaseUrl`, `pgenie.reuseContainer`, `pgenie.pgnExecutable`, `pgenie.force`, `pgenie.skip`.
- On up-to-date digest match, steps 2–5 (provision/stage/run/copy-back) are skipped, but source-root attachment and the dependency check still run every build — compilation needs them.

---

### Task 1: Project scaffold and Mojo skeleton with skip flag

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/io/pgenie/maven/GenerateMojo.java`
- Test: none yet (build itself is the check)

**Interfaces:**
- Produces: the Maven project all later tasks build on; `GenerateMojo` with parameters `skip`, `space`, `name`, `postgres`, `useOptional`, `gen`, `genSha256`, `pgnProjectDirectory`, `failOnSeqScans` and property-only fields.

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.pgenie</groupId>
  <artifactId>pgenie-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>maven-plugin</packaging>

  <name>pGenie Maven Plugin</name>
  <description>Generates Java data-access code from PostgreSQL migrations and queries via pGenie.</description>
  <url>https://github.com/pgenie-io/maven-plugin</url>

  <licenses>
    <license>
      <name>MIT</name>
      <url>https://opensource.org/licenses/MIT</url>
    </license>
  </licenses>
  <scm>
    <url>https://github.com/pgenie-io/maven-plugin</url>
    <connection>scm:git:https://github.com/pgenie-io/maven-plugin.git</connection>
  </scm>
  <developers>
    <developer>
      <name>Nikita Volkov</name>
      <email>nikita.y.volkov@mail.ru</email>
    </developer>
  </developers>

  <properties>
    <maven.compiler.release>11</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <mavenVersion>3.6.3</mavenVersion>
  </properties>

  <prerequisites>
    <maven>3.6.3</maven>
  </prerequisites>

  <dependencies>
    <dependency>
      <groupId>org.apache.maven</groupId>
      <artifactId>maven-plugin-api</artifactId>
      <version>${mavenVersion}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.maven</groupId>
      <artifactId>maven-core</artifactId>
      <version>${mavenVersion}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.maven.plugin-tools</groupId>
      <artifactId>maven-plugin-annotations</artifactId>
      <version>3.13.1</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-compress</artifactId>
      <version>1.27.1</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-plugin-plugin</artifactId>
        <version>3.13.1</version>
        <configuration>
          <goalPrefix>pgenie</goalPrefix>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write the Mojo skeleton**

`src/main/java/io/pgenie/maven/GenerateMojo.java`:

```java
package io.pgenie.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public final class GenerateMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  MavenProject project;

  /** pGenie space; defaults to the sanitized groupId. */
  @Parameter(property = "pgenie.space")
  String space;

  /** pGenie project name; defaults to the sanitized artifactId. */
  @Parameter(property = "pgenie.name")
  String name;

  /** PostgreSQL major version to validate against. */
  @Parameter(defaultValue = "18")
  int postgres;

  /** java.gen option: use Optional for nullable fields. */
  @Parameter(defaultValue = "false")
  boolean useOptional;

  /** Override of the java.gen release URL; requires genSha256. */
  @Parameter
  String gen;

  /** sha256 of the overridden gen; required iff gen is set. */
  @Parameter
  String genSha256;

  @Parameter(defaultValue = "src/main/pgenie")
  String pgnProjectDirectory;

  @Parameter(defaultValue = "false")
  boolean failOnSeqScans;

  @Parameter(property = "pgenie.skip", defaultValue = "false")
  boolean skip;

  // Properties-only knobs — deliberately no defaultValue-from-pom, never in <configuration>.
  @Parameter(property = "pgenie.databaseUrl", readonly = true)
  String databaseUrl;

  @Parameter(property = "pgenie.reuseContainer", readonly = true)
  String reuseContainer;

  @Parameter(property = "pgenie.pgnExecutable", readonly = true)
  String pgnExecutable;

  @Parameter(property = "pgenie.force", defaultValue = "false", readonly = true)
  boolean force;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("pGenie generation skipped");
      return;
    }
    throw new MojoExecutionException("Not implemented yet");
  }
}
```

- [ ] **Step 3: Build and verify plugin descriptor generation**

Run: `mvn -q install`
Expected: BUILD SUCCESS; `target/classes/META-INF/maven/plugin.xml` exists and contains `<goalPrefix>pgenie</goalPrefix>` and a `generate` mojo.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/java/io/pgenie/maven/GenerateMojo.java
git commit -m "Scaffold plugin project with generate mojo skeleton"
```

---

### Task 2: Name and version sanitization

**Files:**
- Create: `src/main/java/io/pgenie/maven/Sanitizer.java`
- Test: `src/test/java/io/pgenie/maven/SanitizerTest.java`

**Interfaces:**
- Produces: `static String Sanitizer.name(String raw)` (throws `IllegalArgumentException` with the raw value in the message), `static String Sanitizer.version(String raw)`.

- [ ] **Step 1: Write the failing tests**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SanitizerTest {
  @Test void nameMapsDotsAndDashes() {
    assertEquals("io_pgenie", Sanitizer.name("io.pgenie"));
    assertEquals("my_app", Sanitizer.name("My-App"));
  }

  @Test void nameRejectsUnsanitizable() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.name("1weird"));
    assertTrue(e.getMessage().contains("1weird"));
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.name("héllo"));
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.name(""));
  }

  @Test void versionStripsQualifierAndPadsToThreeComponents() {
    assertEquals("1.2.3", Sanitizer.version("1.2.3"));
    assertEquals("1.2.3", Sanitizer.version("1.2.3-SNAPSHOT"));
    assertEquals("1.0.0", Sanitizer.version("1.0"));
    assertEquals("2.0.0", Sanitizer.version("2"));
  }

  @Test void versionRejectsNonNumeric() {
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.version("abc"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=SanitizerTest`
Expected: compilation error — `Sanitizer` not defined.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives valid pGenie identifiers from Maven coordinates. */
final class Sanitizer {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[.-].*)?");

  static String name(String raw) {
    String s = raw.toLowerCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    if (!NAME.matcher(s).matches()) {
      throw new IllegalArgumentException(
          "Cannot derive a pGenie name from \"" + raw + "\": result \"" + s
              + "\" must match [a-z][a-z0-9_]*. Set it explicitly in the plugin configuration.");
    }
    return s;
  }

  static String version(String raw) {
    Matcher m = VERSION.matcher(raw);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Cannot derive a SemVer version from project version \"" + raw + "\"");
    }
    String minor = m.group(2) == null ? "0" : m.group(2);
    String patch = m.group(3) == null ? "0" : m.group(3);
    return m.group(1) + "." + minor + "." + patch;
  }

  private Sanitizer() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=SanitizerTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/Sanitizer.java src/test/java/io/pgenie/maven/SanitizerTest.java
git commit -m "Add name and version sanitization"
```

---

### Task 3: Pins and platform detection

**Files:**
- Create: `src/main/java/io/pgenie/maven/Pins.java`
- Create: `src/main/java/io/pgenie/maven/Platform.java`
- Test: `src/test/java/io/pgenie/maven/PlatformTest.java`

**Interfaces:**
- Produces: `Pins.PGN_VERSION`, `Pins.pgnUrl(String platform)`, `Pins.PGN_SHA256` (`Map<String,String>` keyed by platform), `Pins.GEN_URL`, `Pins.GEN_SHA256`; `static String Platform.detect(String osName, String osArch)` and `static String Platform.detect()` returning one of `linux-x64|linux-arm64|macos-x64|macos-arm64|windows-x64`, throwing `IllegalStateException` naming the unsupported combination.

- [ ] **Step 1: Write the failing test**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlatformTest {
  @Test void detectsKnownPlatforms() {
    assertEquals("linux-x64", Platform.detect("Linux", "amd64"));
    assertEquals("linux-arm64", Platform.detect("Linux", "aarch64"));
    assertEquals("macos-x64", Platform.detect("Mac OS X", "x86_64"));
    assertEquals("macos-arm64", Platform.detect("Mac OS X", "aarch64"));
    assertEquals("windows-x64", Platform.detect("Windows 11", "amd64"));
  }

  @Test void rejectsUnsupported() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> Platform.detect("Linux", "riscv64"));
    assertTrue(e.getMessage().contains("riscv64"));
  }

  @Test void pinsAreComplete() {
    for (String p : new String[] {"linux-x64", "linux-arm64", "macos-x64", "macos-arm64", "windows-x64"}) {
      assertTrue(Pins.PGN_SHA256.containsKey(p), p);
      assertTrue(Pins.PGN_SHA256.get(p).matches("[0-9a-f]{64}"), p);
      assertTrue(Pins.pgnUrl(p).endsWith("pgn-" + p + ".tar.gz"));
    }
    assertTrue(Pins.GEN_SHA256.matches("[0-9a-f]{64}"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=PlatformTest`
Expected: compilation error.

- [ ] **Step 3: Compute the real pgn archive checksums**

```bash
cd "$(mktemp -d)"
for p in linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64; do
  curl -sSLO "https://github.com/pgenie-io/pgenie/releases/download/v0.13.0/pgn-$p.tar.gz"
  shasum -a 256 "pgn-$p.tar.gz"
done
```

Record the five hex digests for Step 4.

- [ ] **Step 4: Implement**

`Platform.java`:

```java
package io.pgenie.maven;

import java.util.Locale;

/** Maps JVM os.name/os.arch to pgn release asset platform ids. */
final class Platform {
  static String detect() {
    return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  static String detect(String osName, String osArch) {
    String os = osName.toLowerCase(Locale.ROOT);
    String arch = osArch.toLowerCase(Locale.ROOT);
    boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
    boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
    if (os.contains("linux") && x64) return "linux-x64";
    if (os.contains("linux") && arm64) return "linux-arm64";
    if (os.contains("mac") && x64) return "macos-x64";
    if (os.contains("mac") && arm64) return "macos-arm64";
    if (os.contains("windows") && x64) return "windows-x64";
    throw new IllegalStateException(
        "Unsupported platform: " + osName + "/" + osArch
            + ". Supported: linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64."
            + " You can point at a locally installed pgn with -Dpgenie.pgnExecutable=/path/to/pgn.");
  }

  private Platform() {}
}
```

`Pins.java` (substitute the digests from Step 3 for the `<sha256:...>` placeholders — the test rejects non-hex values, so forgetting fails loudly):

```java
package io.pgenie.maven;

import java.util.Map;

/** The certified triple: this plugin version + pinned pgn + pinned java.gen. */
final class Pins {
  static final String PGN_VERSION = "0.13.0";

  static final Map<String, String> PGN_SHA256 =
      Map.of(
          "linux-x64", "<sha256:pgn-linux-x64>",
          "linux-arm64", "<sha256:pgn-linux-arm64>",
          "macos-x64", "<sha256:pgn-macos-x64>",
          "macos-arm64", "<sha256:pgn-macos-arm64>",
          "windows-x64", "<sha256:pgn-windows-x64>");

  static String pgnUrl(String platform) {
    return "https://github.com/pgenie-io/pgenie/releases/download/v"
        + PGN_VERSION + "/pgn-" + platform + ".tar.gz";
  }

  static final String GEN_URL =
      "https://github.com/pgenie-io/java.gen/releases/download/v1.1.0/resolved.dhall";
  static final String GEN_SHA256 =
      "1f08d67dc1286a818ecc6eb28d360fb5b4ab7af694afc98950f85e47c338c101";

  private Pins() {}
}
```

- [ ] **Step 5: Run to verify pass**

Run: `mvn -q test -Dtest=PlatformTest`
Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/pgenie/maven/Platform.java src/main/java/io/pgenie/maven/Pins.java src/test/java/io/pgenie/maven/PlatformTest.java
git commit -m "Add baked-in pins and platform detection"
```

---

### Task 4: YAML synthesis (project + freeze files)

**Files:**
- Create: `src/main/java/io/pgenie/maven/ProjectYaml.java`
- Test: `src/test/java/io/pgenie/maven/ProjectYamlTest.java`

**Interfaces:**
- Consumes: nothing (pure string building; callers pass already-sanitized values).
- Produces: `static String ProjectYaml.project(String space, String name, String version, int postgres, String genUrl, boolean useOptional)` and `static String ProjectYaml.freeze(String genUrl, String genSha256)`.

- [ ] **Step 1: Write the failing test**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProjectYamlTest {
  @Test void rendersProjectFile() {
    String yaml = ProjectYaml.project("io_pgenie", "music", "1.2.3", 18, "https://example.com/g.dhall", true);
    assertEquals(
        "space: io_pgenie\n"
            + "name: music\n"
            + "version: 1.2.3\n"
            + "postgres: 18\n"
            + "artifacts:\n"
            + "  java:\n"
            + "    gen: https://example.com/g.dhall\n"
            + "    config:\n"
            + "      useOptional: true\n",
        yaml);
  }

  @Test void rendersFreezeFile() {
    assertEquals(
        "https://example.com/g.dhall: sha256:abc123\n",
        ProjectYaml.freeze("https://example.com/g.dhall", "abc123"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=ProjectYamlTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

/**
 * Synthesizes project1.pgn.yaml and freeze1.pgn.yaml. All values are
 * sanitized identifiers, integers, booleans, or URLs, so plain
 * concatenation is valid YAML — no escaping layer needed.
 */
final class ProjectYaml {
  static String project(
      String space, String name, String version, int postgres, String genUrl, boolean useOptional) {
    return "space: " + space + "\n"
        + "name: " + name + "\n"
        + "version: " + version + "\n"
        + "postgres: " + postgres + "\n"
        + "artifacts:\n"
        + "  java:\n"
        + "    gen: " + genUrl + "\n"
        + "    config:\n"
        + "      useOptional: " + useOptional + "\n";
  }

  static String freeze(String genUrl, String genSha256) {
    return genUrl + ": sha256:" + genSha256 + "\n";
  }

  private ProjectYaml() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=ProjectYamlTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/ProjectYaml.java src/test/java/io/pgenie/maven/ProjectYamlTest.java
git commit -m "Add project and freeze yaml synthesis"
```

---

### Task 5: Up-to-date digest

**Files:**
- Create: `src/main/java/io/pgenie/maven/Digest.java`
- Test: `src/test/java/io/pgenie/maven/DigestTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static String Digest.compute(Path inputDir, String configFingerprint)` — sha256 hex over the sorted relative paths and bytes of every file under `inputDir` plus the fingerprint string (effective config + pins, assembled by the Mojo in Task 10). Missing `inputDir` digests as empty.

- [ ] **Step 1: Write the failing test**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DigestTest {
  @TempDir Path dir;

  @Test void stableAcrossRunsAndSensitiveToContentPathAndConfig() throws Exception {
    Files.createDirectories(dir.resolve("queries"));
    Files.writeString(dir.resolve("queries/a.sql"), "select 1");
    String d1 = Digest.compute(dir, "cfg");
    assertEquals(d1, Digest.compute(dir, "cfg"));
    assertTrue(d1.matches("[0-9a-f]{64}"));

    assertNotEquals(d1, Digest.compute(dir, "cfg2"));

    Files.writeString(dir.resolve("queries/a.sql"), "select 2");
    String d2 = Digest.compute(dir, "cfg");
    assertNotEquals(d1, d2);

    Files.move(dir.resolve("queries/a.sql"), dir.resolve("queries/b.sql"));
    assertNotEquals(d2, Digest.compute(dir, "cfg"));
  }

  @Test void missingDirectoryDigestsAsEmpty() throws Exception {
    assertEquals(
        Digest.compute(dir.resolve("nope"), "cfg"),
        Digest.compute(dir.resolve("also-nope"), "cfg"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=DigestTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Content digest of the pgenie inputs for the up-to-date check. */
final class Digest {
  static String compute(Path inputDir, String configFingerprint) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    md.update(configFingerprint.getBytes(StandardCharsets.UTF_8));
    if (Files.isDirectory(inputDir)) {
      List<Path> files;
      try (Stream<Path> walk = Files.walk(inputDir)) {
        files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
      }
      for (Path f : files) {
        md.update((byte) 0);
        md.update(inputDir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        try {
          md.update(Files.readAllBytes(f));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : md.digest()) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private Digest() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=DigestTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/Digest.java src/test/java/io/pgenie/maven/DigestTest.java
git commit -m "Add input digest for up-to-date check"
```

---

### Task 6: Staging and signature copy-back

**Files:**
- Create: `src/main/java/io/pgenie/maven/Staging.java`
- Test: `src/test/java/io/pgenie/maven/StagingTest.java`

**Interfaces:**
- Consumes: `ProjectYaml` output strings.
- Produces:
  - `static void Staging.stage(Path sourceDir, Path stagingDir, String projectYaml, String freezeYaml)` — writes both yaml files into `stagingDir`, mirrors `migrations/`, `queries/`, `types/` from `sourceDir` (deleting stale mirrored files, preserving the rest of staging).
  - `static List<Path> Staging.copyBackSignatures(Path stagingDir, Path sourceDir)` — copies new/changed `*.sig1.pgn.yaml` under `queries/` and `types/` back into `sourceDir`; returns source-relative paths of files actually written.

- [ ] **Step 1: Write the failing test**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingTest {
  @TempDir Path source;
  @TempDir Path staging;

  private void stageOnce() throws Exception {
    Staging.stage(source, staging, "space: s\n", "u: sha256:h\n");
  }

  @Test void stagesYamlsAndInputDirs() throws Exception {
    Files.createDirectories(source.resolve("migrations"));
    Files.createDirectories(source.resolve("queries/sub"));
    Files.writeString(source.resolve("migrations/1.sql"), "create table t ()");
    Files.writeString(source.resolve("queries/sub/q.sql"), "select 1");
    stageOnce();
    assertEquals("space: s\n", Files.readString(staging.resolve("project1.pgn.yaml")));
    assertEquals("u: sha256:h\n", Files.readString(staging.resolve("freeze1.pgn.yaml")));
    assertEquals("create table t ()", Files.readString(staging.resolve("migrations/1.sql")));
    assertEquals("select 1", Files.readString(staging.resolve("queries/sub/q.sql")));
  }

  @Test void removesStaleStagedInputsButKeepsArtifacts() throws Exception {
    Files.createDirectories(source.resolve("queries"));
    Files.writeString(source.resolve("queries/old.sql"), "select 1");
    stageOnce();
    Files.createDirectories(staging.resolve("artifacts/java"));
    Files.writeString(staging.resolve("artifacts/java/keep.txt"), "keep");
    Files.delete(source.resolve("queries/old.sql"));
    Files.writeString(source.resolve("queries/new.sql"), "select 2");
    stageOnce();
    assertFalse(Files.exists(staging.resolve("queries/old.sql")));
    assertTrue(Files.exists(staging.resolve("queries/new.sql")));
    assertTrue(Files.exists(staging.resolve("artifacts/java/keep.txt")));
  }

  @Test void copiesBackOnlyNewOrChangedSignatures() throws Exception {
    Files.createDirectories(source.resolve("queries"));
    Files.writeString(source.resolve("queries/same.sig1.pgn.yaml"), "sig-a");
    Files.createDirectories(staging.resolve("queries"));
    Files.createDirectories(staging.resolve("types"));
    Files.writeString(staging.resolve("queries/same.sig1.pgn.yaml"), "sig-a");
    Files.writeString(staging.resolve("queries/fresh.sig1.pgn.yaml"), "sig-b");
    Files.writeString(staging.resolve("types/t.sig1.pgn.yaml"), "sig-c");
    Files.writeString(staging.resolve("queries/not-a-sig.sql"), "select 1");
    List<Path> copied = Staging.copyBackSignatures(staging, source);
    assertEquals(
        List.of(Path.of("queries/fresh.sig1.pgn.yaml"), Path.of("types/t.sig1.pgn.yaml")),
        copied);
    assertEquals("sig-b", Files.readString(source.resolve("queries/fresh.sig1.pgn.yaml")));
    assertEquals("sig-c", Files.readString(source.resolve("types/t.sig1.pgn.yaml")));
    assertFalse(Files.exists(source.resolve("queries/not-a-sig.sql")));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=StagingTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Builds the staging directory pgn runs in, and copies signatures back. */
final class Staging {
  private static final List<String> INPUT_DIRS = List.of("migrations", "queries", "types");

  static void stage(Path sourceDir, Path stagingDir, String projectYaml, String freezeYaml)
      throws IOException {
    Files.createDirectories(stagingDir);
    Files.writeString(stagingDir.resolve("project1.pgn.yaml"), projectYaml);
    Files.writeString(stagingDir.resolve("freeze1.pgn.yaml"), freezeYaml);
    for (String dirName : INPUT_DIRS) {
      Path target = stagingDir.resolve(dirName);
      deleteRecursively(target);
      Path origin = sourceDir.resolve(dirName);
      if (!Files.isDirectory(origin)) continue;
      try (Stream<Path> walk = Files.walk(origin)) {
        for (Path p : walk.collect(Collectors.toList())) {
          Path dest = target.resolve(origin.relativize(p).toString());
          if (Files.isDirectory(p)) {
            Files.createDirectories(dest);
          } else {
            Files.createDirectories(dest.getParent());
            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }

  static List<Path> copyBackSignatures(Path stagingDir, Path sourceDir) throws IOException {
    List<Path> copied = new ArrayList<>();
    for (String dirName : Arrays.asList("queries", "types")) {
      Path staged = stagingDir.resolve(dirName);
      if (!Files.isDirectory(staged)) continue;
      List<Path> sigs;
      try (Stream<Path> walk = Files.walk(staged)) {
        sigs =
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".sig1.pgn.yaml"))
                .sorted()
                .collect(Collectors.toList());
      }
      for (Path sig : sigs) {
        Path rel = Path.of(dirName).resolve(staged.relativize(sig).toString());
        Path dest = sourceDir.resolve(rel.toString());
        if (Files.exists(dest) && Arrays.equals(Files.readAllBytes(dest), Files.readAllBytes(sig))) {
          continue;
        }
        Files.createDirectories(dest.getParent());
        Files.copy(sig, dest, StandardCopyOption.REPLACE_EXISTING);
        copied.add(rel);
      }
    }
    return copied;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.delete(p);
      }
    }
  }

  private Staging() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=StagingTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/Staging.java src/test/java/io/pgenie/maven/StagingTest.java
git commit -m "Add staging and signature copy-back"
```

---

### Task 7: Binary provisioning

**Files:**
- Create: `src/main/java/io/pgenie/maven/Provisioner.java`
- Test: `src/test/java/io/pgenie/maven/ProvisionerTest.java`

**Interfaces:**
- Consumes: `Pins`, `Platform`.
- Produces: `Provisioner` constructed as `new Provisioner(Path cacheRoot, String platform, Function<String, InputStream> download)` with `Path provision() throws IOException` returning the cached executable. The Mojo (Task 10) constructs it with `~/.m2/pgenie` (derived from the resolved local-repository path's parent, i.e. sibling of `repository`... use `Path.of(System.getProperty("user.home"), ".m2", "pgenie")`), `Platform.detect()`, and a URL-opening function; tests inject a fake download. Also `static String Provisioner.sha256(byte[] bytes)`.

- [ ] **Step 1: Write the failing test**

The test builds an in-memory tar.gz containing a `pgn` entry and serves it through the injected download function.

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvisionerTest {
  @TempDir Path cache;

  private static byte[] tarGz(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
      TarArchiveEntry entry = new TarArchiveEntry(entryName);
      entry.setSize(content.length);
      entry.setMode(0755);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  /** Provisioner whose pinned checksum map is overridden to match the fake archive. */
  private Provisioner provisioner(String platform, byte[] archive, AtomicInteger downloads) {
    Map<String, String> pins = new HashMap<>(Pins.PGN_SHA256);
    pins.put(platform, Provisioner.sha256(archive));
    Function<String, InputStream> download =
        url -> {
          downloads.incrementAndGet();
          return new ByteArrayInputStream(archive);
        };
    return new Provisioner(cache, platform, pins, download);
  }

  @Test void downloadsVerifiesExtractsAndCaches() throws Exception {
    byte[] binary = "#!/bin/sh\necho pgn\n".getBytes(StandardCharsets.UTF_8);
    AtomicInteger downloads = new AtomicInteger();
    Provisioner p = provisioner("linux-x64", tarGz("pgn", binary), downloads);
    Path exe = p.provision();
    assertEquals(cache.resolve(Pins.PGN_VERSION).resolve("pgn"), exe);
    assertArrayEquals(binary, Files.readAllBytes(exe));
    assertTrue(Files.isExecutable(exe));
    assertEquals(1, downloads.get());
    assertEquals(exe, p.provision());
    assertEquals(1, downloads.get(), "second provision must hit the cache");
  }

  @Test void windowsArchiveYieldsPgnExe() throws Exception {
    AtomicInteger downloads = new AtomicInteger();
    Provisioner p = provisioner("windows-x64", tarGz("pgn.exe", new byte[] {1, 2, 3}), downloads);
    assertEquals("pgn.exe", p.provision().getFileName().toString());
  }

  @Test void checksumMismatchFailsWithoutCaching() throws Exception {
    byte[] archive = tarGz("pgn", new byte[] {1});
    AtomicInteger downloads = new AtomicInteger();
    Map<String, String> pins = new HashMap<>(Pins.PGN_SHA256);
    pins.put("linux-x64", "0".repeat(64));
    Provisioner p =
        new Provisioner(cache, "linux-x64", pins, url -> new ByteArrayInputStream(archive));
    IOException e = assertThrows(IOException.class, p::provision);
    assertTrue(e.getMessage().contains("sha256"));
    assertFalse(Files.exists(cache.resolve(Pins.PGN_VERSION).resolve("pgn")));
  }

  @Test void archiveWithoutBinaryFails() throws Exception {
    byte[] archive = tarGz("README.md", new byte[] {1});
    AtomicInteger downloads = new AtomicInteger();
    Provisioner p = provisioner("linux-x64", archive, downloads);
    IOException e = assertThrows(IOException.class, p::provision);
    assertTrue(e.getMessage().contains("pgn"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=ProvisionerTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Downloads the pinned pgn release archive for the current platform into a
 * per-version cache, verifies its baked-in checksum, and extracts the binary.
 */
final class Provisioner {
  private final Path cacheRoot;
  private final String platform;
  private final Map<String, String> pinnedSha256;
  private final Function<String, InputStream> download;

  Provisioner(
      Path cacheRoot,
      String platform,
      Map<String, String> pinnedSha256,
      Function<String, InputStream> download) {
    this.cacheRoot = cacheRoot;
    this.platform = platform;
    this.pinnedSha256 = pinnedSha256;
    this.download = download;
  }

  Path provision() throws IOException {
    String binaryName = platform.startsWith("windows") ? "pgn.exe" : "pgn";
    Path dir = cacheRoot.resolve(Pins.PGN_VERSION);
    Path executable = dir.resolve(binaryName);
    if (Files.isRegularFile(executable)) return executable;

    String url = Pins.pgnUrl(platform);
    byte[] archive;
    try (InputStream in = download.apply(url)) {
      archive = in.readAllBytes();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    String actual = sha256(archive);
    String expected = pinnedSha256.get(platform);
    if (!actual.equals(expected)) {
      throw new IOException(
          "sha256 mismatch for " + url + ": expected " + expected + " but got " + actual
              + ". The download may be corrupted or tampered with; retry, or report an issue.");
    }

    byte[] binary = null;
    try (TarArchiveInputStream tar =
        new TarArchiveInputStream(new GZIPInputStream(new java.io.ByteArrayInputStream(archive)))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        Path name = Path.of(entry.getName()).getFileName();
        if (!entry.isDirectory() && name != null && name.toString().equals(binaryName)) {
          binary = tar.readAllBytes();
          break;
        }
      }
    }
    if (binary == null) {
      throw new IOException("Archive " + url + " does not contain a " + binaryName + " binary");
    }

    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, binaryName, ".part");
    Files.write(tmp, binary);
    if (!tmp.toFile().setExecutable(true, false)) {
      // Windows: executability is not a file bit; ignore.
    }
    Files.move(tmp, executable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return executable;
  }

  static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=ProvisionerTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/Provisioner.java src/test/java/io/pgenie/maven/ProvisionerTest.java
git commit -m "Add pgn binary provisioning with checksum verification"
```

---

### Task 8: pgn process execution

**Files:**
- Create: `src/main/java/io/pgenie/maven/PgnRunner.java`
- Test: `src/test/java/io/pgenie/maven/PgnRunnerTest.java`

**Interfaces:**
- Consumes: an executable path (from `Provisioner.provision()` or `pgenie.pgnExecutable`).
- Produces: `static void PgnRunner.generate(Path executable, Path workingDir, String databaseUrl, String reuseContainer, boolean failOnSeqScans, Consumer<String> logLine) throws IOException` — runs `pgn generate [--database-url U] [--reuse-container V] [--fail-on-seq-scans]` with `workingDir` as cwd, streaming merged stdout/stderr lines to `logLine`; throws `IOException` on nonzero exit, appending a `brew install libpq` / `apt-get install libpq5` hint when the output mentions libpq (`libpq` or `error while loading shared libraries`).

- [ ] **Step 1: Write the failing test** (uses a shell stub; skipped on Windows via `@DisabledOnOs`)

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class PgnRunnerTest {
  @TempDir Path dir;

  private Path stub(String script) throws IOException {
    Path exe = dir.resolve("pgn");
    Files.writeString(exe, "#!/bin/sh\n" + script);
    Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
    return exe;
  }

  @Test void passesArgsAndRunsInWorkingDir() throws Exception {
    Path work = Files.createDirectories(dir.resolve("work"));
    Path exe = stub("echo \"$PWD|$@\"\n");
    List<String> lines = new ArrayList<>();
    PgnRunner.generate(exe, work, "postgres://x", "c1", true, lines::add);
    assertEquals(
        List.of(work.toRealPath() + "|generate --database-url postgres://x --reuse-container c1 --fail-on-seq-scans"),
        lines);
  }

  @Test void omitsUnsetFlags() throws Exception {
    Path exe = stub("echo \"$@\"\n");
    List<String> lines = new ArrayList<>();
    PgnRunner.generate(exe, dir, null, null, false, lines::add);
    assertEquals(List.of("generate"), lines);
  }

  @Test void nonzeroExitThrows() throws Exception {
    Path exe = stub("echo boom >&2\nexit 3\n");
    IOException e =
        assertThrows(IOException.class, () -> PgnRunner.generate(exe, dir, null, null, false, l -> {}));
    assertTrue(e.getMessage().contains("exit code 3"));
  }

  @Test void libpqFailureGetsInstallHint() throws Exception {
    Path exe = stub("echo 'error while loading shared libraries: libpq.so.5' >&2\nexit 127\n");
    IOException e =
        assertThrows(IOException.class, () -> PgnRunner.generate(exe, dir, null, null, false, l -> {}));
    assertTrue(e.getMessage().contains("libpq"));
    assertTrue(e.getMessage().contains("brew install libpq"));
    assertTrue(e.getMessage().contains("apt-get install libpq5"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=PgnRunnerTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Runs pgn generate in the staging directory, streaming output to the Maven log. */
final class PgnRunner {
  static void generate(
      Path executable,
      Path workingDir,
      String databaseUrl,
      String reuseContainer,
      boolean failOnSeqScans,
      Consumer<String> logLine)
      throws IOException {
    List<String> command = new ArrayList<>();
    command.add(executable.toAbsolutePath().toString());
    command.add("generate");
    if (databaseUrl != null) {
      command.add("--database-url");
      command.add(databaseUrl);
    }
    if (reuseContainer != null) {
      command.add("--reuse-container");
      command.add(reuseContainer);
    }
    if (failOnSeqScans) {
      command.add("--fail-on-seq-scans");
    }
    Process process =
        new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start();
    StringBuilder transcript = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        transcript.append(line).append('\n');
        logLine.accept(line);
      }
    }
    int exit;
    try {
      exit = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for pgn", e);
    }
    if (exit != 0) {
      String message = "pgn generate failed with exit code " + exit;
      String output = transcript.toString();
      if (output.contains("libpq") || output.contains("error while loading shared libraries")) {
        message +=
            ". pgn requires the libpq client library:"
                + " on macOS run `brew install libpq`,"
                + " on Debian/Ubuntu run `apt-get install libpq5`.";
      }
      throw new IOException(message);
    }
  }

  private PgnRunner() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=PgnRunnerTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/PgnRunner.java src/test/java/io/pgenie/maven/PgnRunnerTest.java
git commit -m "Add pgn process runner with libpq hint"
```

---

### Task 9: Dependency check

**Files:**
- Create: `src/main/java/io/pgenie/maven/DependencyCheck.java`
- Test: `src/test/java/io/pgenie/maven/DependencyCheckTest.java`

**Interfaces:**
- Consumes: the generated pom at `<staging>/artifacts/java/pom.xml` (to learn the required jdbc version).
- Produces:
  - `static String DependencyCheck.requiredJdbcVersion(String generatedPomXml)` — extracts the version of `io.codemine.java.postgresql:jdbc` from the generated pom (null if not found).
  - `DependencyCheck.Result` enum-ish outcome via `static Result DependencyCheck.check(Collection<String> compileDependencyGavs, String requiredVersion)` where each GAV is `groupId:artifactId:version`; results are `OK`, `VERSION_MISMATCH` (carries found+required versions), `MISSING` (carries `snippet()` — the copy-pasteable `<dependency>` block).

- [ ] **Step 1: Write the failing test**

```java
package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyCheckTest {
  private static final String GENERATED_POM =
      "<project><dependencies>\n"
          + "  <dependency>\n"
          + "    <groupId>io.codemine.java.postgresql</groupId>\n"
          + "    <artifactId>jdbc</artifactId>\n"
          + "    <version>1.4.0</version>\n"
          + "  </dependency>\n"
          + "</dependencies></project>\n";

  @Test void extractsRequiredVersion() {
    assertEquals("1.4.0", DependencyCheck.requiredJdbcVersion(GENERATED_POM));
    assertNull(DependencyCheck.requiredJdbcVersion("<project/>"));
  }

  @Test void okWhenPresentAtRightVersion() {
    DependencyCheck.Result r =
        DependencyCheck.check(List.of("io.codemine.java.postgresql:jdbc:1.4.0"), "1.4.0");
    assertEquals(DependencyCheck.Status.OK, r.status);
  }

  @Test void mismatchWhenPresentAtOtherVersion() {
    DependencyCheck.Result r =
        DependencyCheck.check(List.of("io.codemine.java.postgresql:jdbc:1.3.0"), "1.4.0");
    assertEquals(DependencyCheck.Status.VERSION_MISMATCH, r.status);
    assertTrue(r.message.contains("1.3.0"));
    assertTrue(r.message.contains("1.4.0"));
  }

  @Test void missingProducesSnippet() {
    DependencyCheck.Result r = DependencyCheck.check(List.of("org.slf4j:slf4j-api:2.0.16"), "1.4.0");
    assertEquals(DependencyCheck.Status.MISSING, r.status);
    assertTrue(r.message.contains("<groupId>io.codemine.java.postgresql</groupId>"));
    assertTrue(r.message.contains("<artifactId>jdbc</artifactId>"));
    assertTrue(r.message.contains("<version>1.4.0</version>"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=DependencyCheckTest`
Expected: compilation error.

- [ ] **Step 3: Implement**

```java
package io.pgenie.maven;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the consumer declares the jdbc runtime the generated code needs. */
final class DependencyCheck {
  static final String GROUP_ID = "io.codemine.java.postgresql";
  static final String ARTIFACT_ID = "jdbc";

  enum Status { OK, VERSION_MISMATCH, MISSING }

  static final class Result {
    final Status status;
    final String message;

    private Result(Status status, String message) {
      this.status = status;
      this.message = message;
    }
  }

  private static final Pattern GENERATED_DEP =
      Pattern.compile(
          "<groupId>\\s*" + Pattern.quote(GROUP_ID) + "\\s*</groupId>\\s*"
              + "<artifactId>\\s*" + ARTIFACT_ID + "\\s*</artifactId>\\s*"
              + "<version>\\s*([^<\\s]+)\\s*</version>",
          Pattern.DOTALL);

  static String requiredJdbcVersion(String generatedPomXml) {
    Matcher m = GENERATED_DEP.matcher(generatedPomXml);
    return m.find() ? m.group(1) : null;
  }

  static Result check(Collection<String> compileDependencyGavs, String requiredVersion) {
    for (String gav : compileDependencyGavs) {
      String[] parts = gav.split(":");
      if (parts.length >= 3 && parts[0].equals(GROUP_ID) && parts[1].equals(ARTIFACT_ID)) {
        String found = parts[2];
        if (requiredVersion == null || found.equals(requiredVersion)) {
          return new Result(Status.OK, "");
        }
        return new Result(
            Status.VERSION_MISMATCH,
            GROUP_ID + ":" + ARTIFACT_ID + " is at version " + found
                + " but the generated code was built against " + requiredVersion + ".");
      }
    }
    String version = requiredVersion == null ? "RELEASE" : requiredVersion;
    return new Result(
        Status.MISSING,
        "The generated code requires a dependency that is missing from this project."
            + " Add to <dependencies>:\n\n"
            + "    <dependency>\n"
            + "      <groupId>" + GROUP_ID + "</groupId>\n"
            + "      <artifactId>" + ARTIFACT_ID + "</artifactId>\n"
            + "      <version>" + version + "</version>\n"
            + "    </dependency>\n");
  }

  private DependencyCheck() {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=DependencyCheckTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/pgenie/maven/DependencyCheck.java src/test/java/io/pgenie/maven/DependencyCheckTest.java
git commit -m "Add jdbc dependency check"
```

---

### Task 10: Mojo orchestration

**Files:**
- Modify: `src/main/java/io/pgenie/maven/GenerateMojo.java` (replace the `execute` stub; add imports and helpers)

**Interfaces:**
- Consumes: everything from Tasks 2–9 with the exact signatures listed there.
- Produces: the fully wired `pgenie:generate` goal. No unit test — behavior is covered by the invoker ITs in Task 11.

- [ ] **Step 1: Replace `execute()` and add the orchestration**

Full new body of `GenerateMojo` below the parameter declarations (keep the fields from Task 1; replace `execute` and add the private methods and these imports: `java.io.IOException`, `java.io.InputStream`, `java.io.UncheckedIOException`, `java.net.URI`, `java.nio.file.Files`, `java.nio.file.Path`, `java.util.stream.Collectors`, `org.apache.maven.artifact.Artifact`):

```java
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("pGenie generation skipped");
      return;
    }
    if (gen != null && genSha256 == null) {
      throw new MojoFailureException(
          "Overriding <gen> requires <genSha256> — the sha256 of the overridden generator release.");
    }
    String genUrl = gen != null ? gen : Pins.GEN_URL;
    String genHash = gen != null ? genSha256 : Pins.GEN_SHA256;

    Path baseDir = project.getBasedir().toPath();
    Path sourceDir = baseDir.resolve(pgnProjectDirectory);
    Path pgenieTarget = Path.of(project.getBuild().getDirectory()).resolve("pgenie");
    Path stagingDir = pgenieTarget.resolve("staging");
    Path digestFile = pgenieTarget.resolve("digest");

    String effectiveSpace;
    String effectiveName;
    try {
      effectiveSpace = space != null ? space : Sanitizer.name(project.getGroupId());
      effectiveName = name != null ? name : Sanitizer.name(project.getArtifactId());
    } catch (IllegalArgumentException e) {
      throw new MojoFailureException(e.getMessage());
    }
    String version = Sanitizer.version(project.getVersion());

    String projectYaml =
        ProjectYaml.project(effectiveSpace, effectiveName, version, postgres, genUrl, useOptional);
    String freezeYaml = ProjectYaml.freeze(genUrl, genHash);

    try {
      String fingerprint =
          projectYaml + " " + freezeYaml + " " + Pins.PGN_VERSION + " "
              + failOnSeqScans + " " + String.valueOf(databaseUrl);
      String digest = Digest.compute(sourceDir, fingerprint);
      boolean upToDate =
          !force
              && Files.isRegularFile(digestFile)
              && Files.readString(digestFile).equals(digest)
              && Files.isDirectory(stagingDir.resolve("artifacts/java/src/main/java"));
      if (upToDate) {
        getLog().info("pGenie inputs unchanged; skipping generation (-Dpgenie.force to override)");
        if (databaseUrl != null) {
          getLog().debug(
              "Note: with an external database url, server-side schema drift is invisible"
                  + " to the up-to-date check");
        }
      } else {
        Path executable = resolveExecutable();
        Staging.stage(sourceDir, stagingDir, projectYaml, freezeYaml);
        getLog().info("Running pgn generate in " + stagingDir);
        PgnRunner.generate(
            executable, stagingDir, databaseUrl, reuseContainer, failOnSeqScans,
            line -> getLog().info("[pgn] " + line));
        for (Path sig : Staging.copyBackSignatures(stagingDir, sourceDir)) {
          getLog().info("Signature file added/updated: " + pgnProjectDirectory + "/" + sig
              + " — commit it to version control");
        }
        Files.createDirectories(pgenieTarget);
        Files.writeString(digestFile, digest);
      }
    } catch (IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    attachAndCheck(stagingDir);
  }

  private Path resolveExecutable() throws MojoExecutionException, IOException {
    if (pgnExecutable != null) {
      Path exe = Path.of(pgnExecutable);
      if (!Files.isRegularFile(exe)) {
        throw new MojoExecutionException("pgenie.pgnExecutable does not exist: " + pgnExecutable);
      }
      return exe;
    }
    String platform;
    try {
      platform = Platform.detect();
    } catch (IllegalStateException e) {
      throw new MojoExecutionException(e.getMessage());
    }
    Path cacheRoot = Path.of(System.getProperty("user.home"), ".m2", "pgenie");
    Provisioner provisioner =
        new Provisioner(
            cacheRoot,
            platform,
            Pins.PGN_SHA256,
            url -> {
              try {
                getLog().info("Downloading " + url);
                return URI.create(url).toURL().openStream();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    return provisioner.provision();
  }

  private void attachAndCheck(Path stagingDir) throws MojoExecutionException, MojoFailureException {
    Path generatedSources = stagingDir.resolve("artifacts/java/src/main/java");
    if (!Files.isDirectory(generatedSources)) {
      throw new MojoExecutionException(
          "Expected generated sources at " + generatedSources + " but pgn produced none");
    }
    project.addCompileSourceRoot(generatedSources.toAbsolutePath().toString());
    getLog().info("Attached generated sources: " + generatedSources);

    String requiredVersion = null;
    Path generatedPom = stagingDir.resolve("artifacts/java/pom.xml");
    try {
      if (Files.isRegularFile(generatedPom)) {
        requiredVersion = DependencyCheck.requiredJdbcVersion(Files.readString(generatedPom));
      }
    } catch (IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
    DependencyCheck.Result result =
        DependencyCheck.check(
            project.getArtifacts().stream()
                .filter(a ->
                    Artifact.SCOPE_COMPILE.equals(a.getScope())
                        || Artifact.SCOPE_PROVIDED.equals(a.getScope()))
                .map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
                .collect(Collectors.toList()),
            requiredVersion);
    switch (result.status) {
      case MISSING:
        throw new MojoFailureException(result.message);
      case VERSION_MISMATCH:
        getLog().warn(result.message);
        break;
      case OK:
        break;
    }
  }
```

- [ ] **Step 2: Build and run all unit tests**

Run: `mvn -q install`
Expected: BUILD SUCCESS, all tests from Tasks 2–9 pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/pgenie/maven/GenerateMojo.java
git commit -m "Wire the generate mojo end to end"
```

---

### Task 11: Stub-based invoker integration tests

**Files:**
- Modify: `pom.xml` (add maven-invoker-plugin)
- Create: `src/it/settings.xml`
- Create: `src/it/stub/pgn-stub.sh` (shared fake pgn, copied into each IT by invoker's setup)
- Create: `src/it/happy-path/{pom.xml,invoker.properties,verify.groovy,src/main/pgenie/...,src/main/java/...}`
- Create: `src/it/digest-skip/{pom.xml,invoker.properties,verify.groovy,src/main/pgenie/...}`
- Create: `src/it/missing-dependency/{pom.xml,invoker.properties,verify.groovy,src/main/pgenie/...}`
- Create: `src/it/gen-override-requires-sha/{pom.xml,invoker.properties,verify.groovy,src/main/pgenie/...}`
- Create: `src/it/skip-flag/{pom.xml,invoker.properties,verify.groovy}`

**Interfaces:**
- Consumes: the built plugin; `-Dpgenie.pgnExecutable` pointing at the stub.
- Produces: CI-runnable `mvn verify` covering staging, yaml synthesis, copy-back, digest skip, dependency check, error paths. No Docker, no network.

The stub pgn records each invocation and emits a canned artifact tree plus a signature file. ITs run on Linux/macOS only (`invoker.os.family = !windows` where the stub is used).

- [ ] **Step 1: Add invoker plugin to `pom.xml`** (inside `<build><plugins>`)

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-invoker-plugin</artifactId>
        <version>3.8.1</version>
        <configuration>
          <cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>
          <settingsFile>src/it/settings.xml</settingsFile>
          <localRepositoryPath>${project.build.directory}/local-repo</localRepositoryPath>
          <postBuildHookScript>verify</postBuildHookScript>
          <addTestClassPath>true</addTestClassPath>
          <scriptVariables>
            <stubScript>${project.basedir}/src/it/stub/pgn-stub.sh</stubScript>
          </scriptVariables>
          <properties>
            <pgenie.pgnExecutable>${project.basedir}/src/it/stub/pgn-stub.sh</pgenie.pgnExecutable>
          </properties>
          <goals>
            <goal>generate-sources</goal>
          </goals>
          <streamLogs>true</streamLogs>
        </configuration>
        <executions>
          <execution>
            <id>integration-test</id>
            <goals>
              <goal>install</goal>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 2: Write `src/it/settings.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles>
    <profile>
      <id>it-repo</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
        <repository>
          <id>local.central</id>
          <url>@localRepositoryUrl@</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>local.central</id>
          <url>@localRepositoryUrl@</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
</settings>
```

- [ ] **Step 3: Write the stub `src/it/stub/pgn-stub.sh`** and `chmod +x` it

```bash
#!/bin/sh
# Fake pgn for integration tests. Records invocations, validates the staged
# project, and emits a canned java artifact plus a signature file.
set -e
echo "$PWD $*" >> invocations.log

test -f project1.pgn.yaml
test -f freeze1.pgn.yaml
test -d migrations

mkdir -p artifacts/java/src/main/java/gen
cat > artifacts/java/src/main/java/gen/Generated.java <<'EOF'
package gen;

public final class Generated {
  public static final String MARKER = "generated";

  private Generated() {}
}
EOF

cat > artifacts/java/pom.xml <<'EOF'
<project>
  <dependencies>
    <dependency>
      <groupId>io.codemine.java.postgresql</groupId>
      <artifactId>jdbc</artifactId>
      <version>1.4.0</version>
    </dependency>
  </dependencies>
</project>
EOF

mkdir -p queries
cat > queries/select_one.sig1.pgn.yaml <<'EOF'
result: stub-signature
EOF
```

Run: `chmod +x src/it/stub/pgn-stub.sh`

- [ ] **Step 4: Write the happy-path IT**

`src/it/happy-path/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.pgenie.it</groupId>
  <artifactId>happy-path</artifactId>
  <version>1.2.3-SNAPSHOT</version>

  <properties>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
  </properties>

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
          <useOptional>true</useOptional>
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

Note on the jdbc dependency: the artifact doesn't exist in the IT's isolated local repo, so declare it with `system` scope pointing at an empty jar committed as `src/it/happy-path/fake-jdbc.jar` (create with `cd src/it/happy-path && jar cf fake-jdbc.jar -C "$(mktemp -d)" .`). System-scoped artifacts appear in `project.getArtifacts()` with scope `system`, so extend the Mojo's scope filter from Task 10 to also include `Artifact.SCOPE_SYSTEM` — this lets IT projects satisfy the check without publishing a fake artifact.

`src/it/happy-path/invoker.properties`:

```properties
invoker.os.family = !windows
invoker.goals = compile
```

`src/it/happy-path/src/main/pgenie/migrations/0001-init.sql`:

```sql
create table t (id int8 not null primary key);
```

`src/it/happy-path/src/main/pgenie/queries/select_one.sql`:

```sql
select 1;
```

`src/it/happy-path/src/main/java/app/Uses.java` (proves the attached source root reaches compilation):

```java
package app;

public final class Uses {
  public static final String M = gen.Generated.MARKER;

  private Uses() {}
}
```

`src/it/happy-path/verify.groovy`:

```groovy
File staging = new File(basedir, 'target/pgenie/staging')

String projectYaml = new File(staging, 'project1.pgn.yaml').text
assert projectYaml.contains('space: io_pgenie_it')
assert projectYaml.contains('name: happy_path')
assert projectYaml.contains('version: 1.2.3')
assert projectYaml.contains('postgres: 18')
assert projectYaml.contains('useOptional: true')

String freeze = new File(staging, 'freeze1.pgn.yaml').text
assert freeze.contains('java.gen') && freeze.contains('sha256:')

assert new File(staging, 'migrations/0001-init.sql').exists()
assert new File(staging, 'queries/select_one.sql').exists()

// copy-back into the source tree
assert new File(basedir, 'src/main/pgenie/queries/select_one.sig1.pgn.yaml').text.contains('stub-signature')

// generated code was compiled via the attached source root
assert new File(basedir, 'target/classes/gen/Generated.class').exists()
assert new File(basedir, 'target/classes/app/Uses.class').exists()

// digest written
assert new File(basedir, 'target/pgenie/digest').text.length() == 64
return true
```

- [ ] **Step 5: Write the digest-skip IT**

Same `pom.xml` pattern as happy-path (artifactId `digest-skip`) including the jdbc system dep and the same `src/main/pgenie` inputs, no `src/main/java`.

`src/it/digest-skip/invoker.properties` (two invocations of the same project):

```properties
invoker.os.family = !windows
invoker.goals.1 = generate-sources
invoker.goals.2 = generate-sources
```

`src/it/digest-skip/verify.groovy`:

```groovy
File log = new File(basedir, 'target/pgenie/staging/invocations.log')
assert log.exists()
assert log.readLines().size() == 1 : "second build must skip pgn, got:\n" + log.text
return true
```

- [ ] **Step 6: Write the missing-dependency IT**

`pom.xml` like happy-path (artifactId `missing-dependency`) but **without** the jdbc dependency; same pgenie inputs.

`src/it/missing-dependency/invoker.properties`:

```properties
invoker.os.family = !windows
invoker.goals = generate-sources
invoker.buildResult = failure
```

`src/it/missing-dependency/verify.groovy`:

```groovy
String log = new File(basedir, 'build.log').text
assert log.contains('<groupId>io.codemine.java.postgresql</groupId>')
assert log.contains('<artifactId>jdbc</artifactId>')
assert log.contains('<version>1.4.0</version>')
return true
```

- [ ] **Step 7: Write the gen-override-requires-sha IT**

`pom.xml` like happy-path (artifactId `gen-override`) with:

```xml
        <configuration>
          <gen>https://example.com/custom.dhall</gen>
        </configuration>
```

`invoker.properties`:

```properties
invoker.goals = generate-sources
invoker.buildResult = failure
```

`verify.groovy`:

```groovy
assert new File(basedir, 'build.log').text.contains('requires <genSha256>')
return true
```

- [ ] **Step 8: Write the skip-flag IT**

`pom.xml` like happy-path (artifactId `skip-flag`, no jdbc dep, no pgenie sources needed).

`invoker.properties` (properties go on the goal line — invoker has no per-project property key):

```properties
invoker.goals = generate-sources -Dpgenie.skip=true
```

`verify.groovy`:

```groovy
assert new File(basedir, 'build.log').text.contains('pGenie generation skipped')
assert !new File(basedir, 'target/pgenie').exists()
return true
```

- [ ] **Step 9: Run the ITs**

Run: `mvn -q verify`
Expected: BUILD SUCCESS; invoker reports 5 ITs passed. Iterate on Mojo/stub bugs here — this is the step where integration reality bites.

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/it
git commit -m "Add stub-based invoker integration tests"
```

---

### Task 12: Real end-to-end IT (release certification)

**Files:**
- Modify: `pom.xml` (add `e2e` profile)
- Create: `src/it-e2e/certification/{pom.xml,invoker.properties,verify.groovy,src/main/pgenie/...}`

**Interfaces:**
- Consumes: real pinned pgn (downloaded), Docker, network, real java.gen release.
- Produces: `mvn verify -Pe2e` certifying the pinned triple; generated output compiled with `--release 8`.

- [ ] **Step 1: Add the `e2e` profile to `pom.xml`** (after `</build>`)

```xml
  <profiles>
    <profile>
      <id>e2e</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-invoker-plugin</artifactId>
            <version>3.8.1</version>
            <executions>
              <execution>
                <id>e2e</id>
                <goals>
                  <goal>install</goal>
                  <goal>integration-test</goal>
                  <goal>verify</goal>
                </goals>
                <configuration>
                  <projectsDirectory>src/it-e2e</projectsDirectory>
                  <cloneProjectsTo>${project.build.directory}/it-e2e</cloneProjectsTo>
                  <settingsFile>src/it/settings.xml</settingsFile>
                  <localRepositoryPath>${project.build.directory}/local-repo</localRepositoryPath>
                  <postBuildHookScript>verify</postBuildHookScript>
                  <streamLogs>true</streamLogs>
                </configuration>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

- [ ] **Step 2: Write the certification project**

`src/it-e2e/certification/pom.xml` — like happy-path but: artifactId `certification`, compiler pinned to Java 8 for the generated code, real jdbc dependency from Central (use the version the generated pom requires — check `java.gen`'s current generated pom; adjust when pins bump):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.pgenie.it</groupId>
  <artifactId>certification</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.release>8</maven.compiler.release>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.codemine.java.postgresql</groupId>
      <artifactId>jdbc</artifactId>
      <version>RELEASE</version><!-- pin to the version the generated pom requires once known -->
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.pgenie</groupId>
        <artifactId>pgenie-maven-plugin</artifactId>
        <version>@project.version@</version>
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

`invoker.properties`:

```properties
invoker.goals = compile
```

`src/main/pgenie/migrations/0001-init.sql` and `queries/` — copy a minimal subset from the demo project (`/Users/mojojojo/repos/pgenie/demo`): one migration creating a table, one query selecting from it. Do **not** pre-commit `*.sig1.pgn.yaml`; the run must create one.

`verify.groovy`:

```groovy
assert new File(basedir, 'target/classes').list().length > 0
File queries = new File(basedir, 'src/main/pgenie/queries')
assert queries.listFiles().any { it.name.endsWith('.sig1.pgn.yaml') }
return true
```

- [ ] **Step 3: Run it for real (requires Docker + network)**

Run: `mvn verify -Pe2e`
Expected: real pgn downloaded and checksum-verified, Docker Postgres spun up by pgn, generated sources compiled with `--release 8`. **Known risk:** until java.gen#9 lands, generated code may not compile under `--release 8` — if so, temporarily set `maven.compiler.release` to 11 in the certification pom with a `TODO(java.gen#9)` comment, and treat restoring 8 as a release blocker.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/it-e2e
git commit -m "Add profile-gated end-to-end certification IT"
```

---

### Task 13: CI workflows

**Files:**
- Create: `.github/workflows/ci.yaml`
- Create: `.github/workflows/e2e.yaml`

- [ ] **Step 1: Write `ci.yaml`** (stub ITs, fast, per push/PR)

```yaml
name: ci
on:
  push:
    branches: [main, master]
  pull_request:
jobs:
  test:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '11'
          cache: maven
      - run: mvn -B verify
```

- [ ] **Step 2: Write `e2e.yaml`** (certification, on main and manual dispatch; Docker is preinstalled on ubuntu runners)

```yaml
name: e2e
on:
  push:
    branches: [main, master]
  workflow_dispatch:
jobs:
  certify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '11'
          cache: maven
      - run: sudo apt-get update && sudo apt-get install -y libpq5
      - run: mvn -B verify -Pe2e
```

- [ ] **Step 3: Commit and push; watch both workflows**

```bash
git add .github
git commit -m "Add CI workflows"
git push
gh run watch
```

Expected: `ci` green on both OSes; `e2e` green (or failing only on the known java.gen#9 gap documented in Task 12).

---

### Task 14: Maven Central publishing and README

**Files:**
- Modify: `pom.xml` (release profile: sources, javadoc, GPG, central-publishing-maven-plugin)
- Create: `.github/workflows/release.yaml`
- Modify: `README.md`

- [ ] **Step 1: Add the `release` profile to `pom.xml`** (inside `<profiles>`)

```xml
    <profile>
      <id>release</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-source-plugin</artifactId>
            <version>3.3.1</version>
            <executions>
              <execution>
                <id>attach-sources</id>
                <goals><goal>jar-no-fork</goal></goals>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-javadoc-plugin</artifactId>
            <version>3.11.2</version>
            <executions>
              <execution>
                <id>attach-javadocs</id>
                <goals><goal>jar</goal></goals>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-gpg-plugin</artifactId>
            <version>3.2.7</version>
            <executions>
              <execution>
                <id>sign-artifacts</id>
                <phase>verify</phase>
                <goals><goal>sign</goal></goals>
                <configuration>
                  <gpgArguments>
                    <arg>--pinentry-mode</arg>
                    <arg>loopback</arg>
                  </gpgArguments>
                </configuration>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <groupId>org.sonatype.central</groupId>
            <artifactId>central-publishing-maven-plugin</artifactId>
            <version>0.7.0</version>
            <extensions>true</extensions>
            <configuration>
              <publishingServerId>central</publishingServerId>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 2: Write `release.yaml`** (tag-triggered; needs `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` repo secrets — creating those is a manual owner step, note it in the README's release section)

```yaml
name: release
on:
  push:
    tags: ['v*']
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '11'
          cache: maven
          server-id: central
          server-username: CENTRAL_USERNAME
          server-password: CENTRAL_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE
      - run: mvn -B deploy -DskipTests -Prelease
        env:
          CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

- [ ] **Step 3: Write the README**

Replace `README.md` with user-facing docs covering, in order:

```markdown
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

## Configuration

| Parameter | Default | |
|---|---|---|
| `space` | sanitized groupId | pGenie namespace |
| `name` | sanitized artifactId | pGenie project name |
| `postgres` | `18` | PostgreSQL major to validate against |
| `useOptional` | `false` | Use Optional for nullable fields |
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

Maven 3.6.3+, JDK 11+ for the build. Docker (default mode) or a reachable
PostgreSQL (`-Dpgenie.databaseUrl`). libpq on the host: `brew install libpq`
(macOS) / `apt-get install libpq5` (Debian/Ubuntu).

## Releasing (maintainers)

Each release certifies a (plugin, pgn, java.gen) triple pinned in
`Pins.java`. To release: update pins if needed, run `mvn verify -Pe2e`,
tag `vX.Y.Z`, push the tag. Requires repo secrets CENTRAL_USERNAME,
CENTRAL_PASSWORD, GPG_PRIVATE_KEY, GPG_PASSPHRASE.
```

- [ ] **Step 4: Full verify and commit**

Run: `mvn -q verify`
Expected: BUILD SUCCESS.

```bash
git add pom.xml .github/workflows/release.yaml README.md
git commit -m "Add Central publishing and user documentation"
```

---

## Self-Review Notes

Checked against `docs/spec.md`:

- **Identity** — coordinates/prefix (Task 1), JDK 11 + Maven 3.6.3 (Task 1, prerequisites), Java 8 generated target (Task 12, with the java.gen#9 contingency called out).
- **Model** — pom as sole source of truth / synthesized yamls (Tasks 4, 6, 10), certified triple with no `pgnVersion` parameter (Task 3), `src/main/pgenie` default (Task 1).
- **Execution steps 1–7** — digest (5, 10), provisioning incl. platform errors, sha256, windows naming, `pgnExecutable` bypass (3, 7, 10), staging incl. freeze synthesis (4, 6), run with flags (8), copy-back (6), attach (10), dependency check with snippet + mismatch warning (9, 10). Digest-skip caveat for external DB logged (10) and documented (14).
- **Configuration** — all pom parameters and properties-only knobs (Task 1); `genConfig` deliberately replaced by `useOptional` per 2026-07-17 decision (Global Constraints).
- **Testing** — stub-first invoker ITs (11), profile-gated real e2e in CI (12, 13).
- **Out of scope** respected: no analyse/manage-indexes, no project-file mode, no test attachment, no dependency injection, Maven-Central binary distribution deferred.

Known open items (not plan gaps, tracked contingencies): pgn archive checksums are computed in Task 3 Step 3; the real jdbc version and Java-8 compile in the e2e IT depend on java.gen#9 and the Java-8 jdbc release.
