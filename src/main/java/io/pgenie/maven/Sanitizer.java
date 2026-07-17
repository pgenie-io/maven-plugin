package io.pgenie.maven;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives valid pGenie identifiers from Maven coordinates. */
final class Sanitizer {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[.-].*)?");

  static String name(String raw) {
    String s = raw.toLowerCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    if (!NAME.matcher(s).matches()) {
      throw new IllegalArgumentException(
          "Cannot derive a pGenie name from \"" + raw + "\": result \"" + s
              + "\" must match [a-z][a-z0-9_]*. Set it explicitly in the plugin configuration.");
    }
    return s;
  }

  static String version(String raw) {
    Matcher m = VERSION.matcher(raw);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Cannot derive a SemVer version from project version \"" + raw + "\"");
    }
    String minor = m.group(2) == null ? "0" : m.group(2);
    String patch = m.group(3) == null ? "0" : m.group(3);
    return m.group(1) + "." + minor + "." + patch;
  }

  private Sanitizer() {}
}
