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
