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
