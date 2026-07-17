package io.pgenie.maven;

import java.io.IOException;
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
        md.update(Files.readAllBytes(f));
      }
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : md.digest()) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private Digest() {}
}
