package io.pgenie.maven;

/**
 * Synthesizes project1.pgn.yaml and freeze1.pgn.yaml. All values are
 * sanitized identifiers, integers, booleans, or URLs, so plain
 * concatenation is valid YAML — no escaping layer needed.
 */
final class ProjectYaml {
  static String project(
      String space, String name, String version, int postgres, String genUrl, boolean useOptional,
      String groupId, String artifactId, String rootPackage) {
    StringBuilder config = new StringBuilder();
    config.append("      useOptional: ").append(useOptional).append("\n");
    config.append("      groupId: ").append(groupId).append("\n");
    config.append("      artifactId: ").append(artifactId).append("\n");
    if (rootPackage != null) {
      config.append("      rootPackage: ").append(rootPackage).append("\n");
    }
    return "space: " + space + "\n"
        + "name: " + name + "\n"
        + "version: " + version + "\n"
        + "postgres: " + postgres + "\n"
        + "artifacts:\n"
        + "  java:\n"
        + "    gen: " + genUrl + "\n"
        + "    config:\n"
        + config;
  }

  static String freeze(String genUrl, String genSha256) {
    return genUrl + ": sha256:" + genSha256 + "\n";
  }

  private ProjectYaml() {}
}
