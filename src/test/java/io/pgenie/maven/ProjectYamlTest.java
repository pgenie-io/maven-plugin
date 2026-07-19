package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProjectYamlTest {
  @Test void rendersProjectFile() {
    String yaml = ProjectYaml.project(
        "io_pgenie", "music", "1.2.3", 18, "https://example.com/g.dhall", true,
        "com.example.music", "catalogue-lib", null);
    assertEquals(
        "space: io_pgenie\n"
            + "name: music\n"
            + "version: 1.2.3\n"
            + "postgres: 18\n"
            + "artifacts:\n"
            + "  java:\n"
            + "    gen: https://example.com/g.dhall\n"
            + "    config:\n"
            + "      useOptional: true\n"
            + "      groupId: com.example.music\n"
            + "      artifactId: catalogue-lib\n",
        yaml);
  }

  @Test void rendersProjectFileWithRootPackageOverride() {
    String yaml = ProjectYaml.project(
        "io_pgenie", "music", "1.2.3", 18, "https://example.com/g.dhall", true,
        "com.example.music", "catalogue-lib", "com.example.music.catalogue");
    assertEquals(
        "space: io_pgenie\n"
            + "name: music\n"
            + "version: 1.2.3\n"
            + "postgres: 18\n"
            + "artifacts:\n"
            + "  java:\n"
            + "    gen: https://example.com/g.dhall\n"
            + "    config:\n"
            + "      useOptional: true\n"
            + "      groupId: com.example.music\n"
            + "      artifactId: catalogue-lib\n"
            + "      rootPackage: com.example.music.catalogue\n",
        yaml);
  }

  @Test void rendersFreezeFile() {
    assertEquals(
        "https://example.com/g.dhall: sha256:abc123\n",
        ProjectYaml.freeze("https://example.com/g.dhall", "abc123"));
  }
}
