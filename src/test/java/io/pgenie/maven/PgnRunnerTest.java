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
