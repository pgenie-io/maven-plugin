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
