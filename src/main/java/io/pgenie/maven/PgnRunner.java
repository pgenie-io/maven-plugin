package io.pgenie.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs pgn generate in the staging directory, streaming output to the Maven log. */
final class PgnRunner {
  private static final long TIMEOUT_MINUTES = 15;

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
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader r =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                  transcript.append(line).append('\n');
                  logLine.accept(line);
                }
              } catch (IOException ignored) {
                // stream closes when the process is killed after a timeout; nothing more to read
              }
            });
    reader.setDaemon(true);
    reader.start();

    int exit;
    try {
      boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
      if (!finished) {
        process.destroyForcibly();
        throw new IOException(
            "pgn generate did not complete within "
                + TIMEOUT_MINUTES
                + " minutes; the process was killed");
      }
      exit = process.exitValue();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for pgn", e);
    } finally {
      // Let the reader thread drain any final buffered output after the process exits or is
      // killed; it is a daemon thread so it can't block JVM shutdown even if join times out.
      try {
        reader.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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
