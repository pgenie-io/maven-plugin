package io.pgenie.maven;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the consumer declares the jdbc runtime the generated code needs. */
final class DependencyCheck {
  static final String GROUP_ID = "io.codemine.java.postgresql";
  static final String ARTIFACT_ID = "jdbc";

  enum Status { OK, VERSION_MISMATCH, MISSING }

  static final class Result {
    final Status status;
    final String message;

    private Result(Status status, String message) {
      this.status = status;
      this.message = message;
    }
  }

  private static final Pattern GENERATED_DEP =
      Pattern.compile(
          "<groupId>\\s*" + Pattern.quote(GROUP_ID) + "\\s*</groupId>\\s*"
              + "<artifactId>\\s*" + ARTIFACT_ID + "\\s*</artifactId>\\s*"
              + "<version>\\s*([^<\\s]+)\\s*</version>",
          Pattern.DOTALL);

  static String requiredJdbcVersion(String generatedPomXml) {
    Matcher m = GENERATED_DEP.matcher(generatedPomXml);
    return m.find() ? m.group(1) : null;
  }

  static Result check(Collection<String> compileDependencyGavs, String requiredVersion) {
    for (String gav : compileDependencyGavs) {
      String[] parts = gav.split(":");
      if (parts.length >= 3 && parts[0].equals(GROUP_ID) && parts[1].equals(ARTIFACT_ID)) {
        String found = parts[2];
        if (requiredVersion == null || found.equals(requiredVersion)) {
          return new Result(Status.OK, "");
        }
        return new Result(
            Status.VERSION_MISMATCH,
            GROUP_ID + ":" + ARTIFACT_ID + " is at version " + found
                + " but the generated code was built against " + requiredVersion + ".");
      }
    }
    String version = requiredVersion == null ? "RELEASE" : requiredVersion;
    return new Result(
        Status.MISSING,
        "The generated code requires a dependency that is missing from this project."
            + " Add to <dependencies>:\n\n"
            + "    <dependency>\n"
            + "      <groupId>" + GROUP_ID + "</groupId>\n"
            + "      <artifactId>" + ARTIFACT_ID + "</artifactId>\n"
            + "      <version>" + version + "</version>\n"
            + "    </dependency>\n");
  }

  private DependencyCheck() {}
}
