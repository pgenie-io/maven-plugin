package io.pgenie.maven;

import java.util.Map;

/** The certified triple: this plugin version + pinned pgn + pinned java.gen. */
final class Pins {
  static final String PGN_VERSION = "0.13.0";

  static final Map<String, String> PGN_SHA256 =
      Map.of(
          "linux-x64", "380bd55595b1737e348761300cfa00ff97f0fa9c9ca00634ef816fb2ec6d499a",
          "linux-arm64", "a69afe567507fa4f09ad5ed622f6059dfa16eb6d1a1bacae1555caaba0d82591",
          "macos-x64", "138fd5763a7cf3d71ef5ff1054c73b8fb2bd2b9dc89dc29844ca2a24d33c16cf",
          "macos-arm64", "e01eafd554d4f887d913c32a0e22da4a375bc679455b547ea6f084c046320cd0",
          "windows-x64", "ed6845d1426f3ba0794b93cfc21cc93d5a4ec86f35b3896fbce433c062c212d2");

  static String pgnUrl(String platform) {
    return "https://github.com/pgenie-io/pgenie/releases/download/v"
        + PGN_VERSION + "/pgn-" + platform + ".tar.gz";
  }

  static final String GEN_URL =
      "https://github.com/pgenie-io/java.gen/releases/download/v1.2.0/resolved.dhall";
  static final String GEN_SHA256 =
      "0cc4c6c31bfd2513fd6191b7fe7d986af91eea909995e07c1ea97bfd639cdea3";

  private Pins() {}
}
