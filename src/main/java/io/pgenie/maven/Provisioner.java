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
