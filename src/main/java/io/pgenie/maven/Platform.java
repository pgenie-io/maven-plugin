package io.pgenie.maven;

import java.util.Locale;

/** Maps JVM os.name/os.arch to pgn release asset platform ids. */
final class Platform {
  static String detect() {
    return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  static String detect(String osName, String osArch) {
    String os = osName.toLowerCase(Locale.ROOT);
    String arch = osArch.toLowerCase(Locale.ROOT);
    boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
    boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
    if (os.contains("linux") && x64) return "linux-x64";
    if (os.contains("linux") && arm64) return "linux-arm64";
    if (os.contains("mac") && x64) return "macos-x64";
    if (os.contains("mac") && arm64) return "macos-arm64";
    if (os.contains("windows") && x64) return "windows-x64";
    throw new IllegalStateException(
        "Unsupported platform: " + osName + "/" + osArch
            + ". Supported: linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64."
            + " You can point at a locally installed pgn with -Dpgenie.pgnExecutable=/path/to/pgn.");
  }

  private Platform() {}
}
