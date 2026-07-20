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
      copyTree(origin, target);
    }
  }

  /**
   * Mirrors the compiled Java package tree into the given directory, replacing whatever was
   * there before. Used to expose only the actual compile source root under {@code
   * target/generated-sources} — the rest of the staging directory (pom.xml, README, tests,
   * duplicated SQL/yaml) stays out of that IDE-visible tree.
   */
  static void exposeSources(Path javaSourceRoot, Path dest) throws IOException {
    deleteRecursively(dest);
    copyTree(javaSourceRoot, dest);
  }

  private static void copyTree(Path origin, Path target) throws IOException {
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

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.delete(p);
      }
    }
  }

  private Staging() {}
}
