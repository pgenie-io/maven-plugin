package io.pgenie.maven;

/**
 * Synthesizes project1.pgn.yaml and freeze1.pgn.yaml. All values are
 * sanitized identifiers, integers, booleans, or URLs, so plain
 * concatenation is valid YAML — no escaping layer needed.
 */
final class ProjectYaml {
  static String project(
      String space, String name, String version, int postgres, String genUrl, boolean useOptional) {
    return "space: " + space + "\n"
        + "name: " + name + "\n"
        + "version: " + version + "\n"
        + "postgres: " + postgres + "\n"
        + "artifacts:\n"
        + "  java:\n"
        + "    gen: " + genUrl + "\n"
        + "    config:\n"
        + "      useOptional: " + useOptional + "\n";
  }

  static String freeze(String genUrl, String genSha256) {
    return genUrl + ": sha256:" + genSha256 + "\n";
  }

  private ProjectYaml() {}
}
