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
