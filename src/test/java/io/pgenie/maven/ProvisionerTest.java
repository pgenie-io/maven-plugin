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
