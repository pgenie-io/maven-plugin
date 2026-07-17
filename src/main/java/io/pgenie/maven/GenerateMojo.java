package io.pgenie.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public final class GenerateMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  MavenProject project;

  /** pGenie space; defaults to the sanitized groupId. */
  @Parameter(property = "pgenie.space")
  String space;

  /** pGenie project name; defaults to the sanitized artifactId. */
  @Parameter(property = "pgenie.name")
  String name;

  /** PostgreSQL major version to validate against. */
  @Parameter(defaultValue = "18")
  int postgres;

  /** java.gen option: use Optional for nullable fields. */
  @Parameter(defaultValue = "false")
  boolean useOptional;

  /** Override of the java.gen release URL; requires genSha256. */
  @Parameter
  String gen;

  /** sha256 of the overridden gen; required iff gen is set. */
  @Parameter
  String genSha256;

  @Parameter(defaultValue = "src/main/pgenie")
  String pgnProjectDirectory;

  @Parameter(defaultValue = "false")
  boolean failOnSeqScans;

  @Parameter(property = "pgenie.skip", defaultValue = "false")
  boolean skip;

  // Properties-only knobs — deliberately no defaultValue-from-pom, never in <configuration>.
  @Parameter(property = "pgenie.databaseUrl", readonly = true)
  String databaseUrl;

  @Parameter(property = "pgenie.reuseContainer", readonly = true)
  String reuseContainer;

  @Parameter(property = "pgenie.pgnExecutable", readonly = true)
  String pgnExecutable;

  @Parameter(property = "pgenie.force", defaultValue = "false", readonly = true)
  boolean force;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("pGenie generation skipped");
      return;
    }
    if (gen != null && genSha256 == null) {
      throw new MojoFailureException(
          "Overriding <gen> requires <genSha256> — the sha256 of the overridden generator release.");
    }
    String genUrl = gen != null ? gen : Pins.GEN_URL;
    String genHash = gen != null ? genSha256 : Pins.GEN_SHA256;

    Path baseDir = project.getBasedir().toPath();
    Path sourceDir = baseDir.resolve(pgnProjectDirectory);
    Path pgenieTarget = Path.of(project.getBuild().getDirectory()).resolve("pgenie");
    Path stagingDir = pgenieTarget.resolve("staging");
    Path digestFile = pgenieTarget.resolve("digest");

    String effectiveSpace;
    String effectiveName;
    String version;
    try {
      effectiveSpace = space != null ? space : Sanitizer.name(project.getGroupId());
      effectiveName = name != null ? name : Sanitizer.name(project.getArtifactId());
      version = Sanitizer.version(project.getVersion());
    } catch (IllegalArgumentException e) {
      throw new MojoFailureException(e.getMessage());
    }

    String projectYaml =
        ProjectYaml.project(effectiveSpace, effectiveName, version, postgres, genUrl, useOptional);
    String freezeYaml = ProjectYaml.freeze(genUrl, genHash);

    try {
      String fingerprint =
          projectYaml + " " + freezeYaml + " " + Pins.PGN_VERSION + " "
              + failOnSeqScans + " " + String.valueOf(databaseUrl);
      String digest = Digest.compute(sourceDir, fingerprint);
      boolean upToDate =
          !force
              && Files.isRegularFile(digestFile)
              && Files.readString(digestFile).equals(digest)
              && Files.isDirectory(stagingDir.resolve("artifacts/java/src/main/java"));
      if (upToDate) {
        getLog().info("pGenie inputs unchanged; skipping generation (-Dpgenie.force to override)");
        if (databaseUrl != null) {
          getLog().debug(
              "Note: with an external database url, server-side schema drift is invisible"
                  + " to the up-to-date check");
        }
      } else {
        Path executable = resolveExecutable();
        Staging.stage(sourceDir, stagingDir, projectYaml, freezeYaml);
        getLog().info("Running pgn generate in " + stagingDir);
        PgnRunner.generate(
            executable, stagingDir, databaseUrl, reuseContainer, failOnSeqScans,
            line -> getLog().info("[pgn] " + line));
        for (Path sig : Staging.copyBackSignatures(stagingDir, sourceDir)) {
          getLog().info("Signature file added/updated: " + pgnProjectDirectory + "/" + sig
              + " — commit it to version control");
        }
        Files.createDirectories(pgenieTarget);
        Files.writeString(digestFile, digest);
      }
    } catch (IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    attachAndCheck(stagingDir);
  }

  private Path resolveExecutable() throws MojoExecutionException, IOException {
    if (pgnExecutable != null) {
      Path exe = Path.of(pgnExecutable);
      if (!Files.isRegularFile(exe)) {
        throw new MojoExecutionException("pgenie.pgnExecutable does not exist: " + pgnExecutable);
      }
      return exe;
    }
    String platform;
    try {
      platform = Platform.detect();
    } catch (IllegalStateException e) {
      throw new MojoExecutionException(e.getMessage());
    }
    Path cacheRoot = Path.of(System.getProperty("user.home"), ".m2", "pgenie");
    Provisioner provisioner =
        new Provisioner(
            cacheRoot,
            platform,
            Pins.PGN_SHA256,
            url -> {
              try {
                getLog().info("Downloading " + url);
                return URI.create(url).toURL().openStream();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    try {
      return provisioner.provision();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private void attachAndCheck(Path stagingDir) throws MojoExecutionException, MojoFailureException {
    Path generatedSources = stagingDir.resolve("artifacts/java/src/main/java");
    if (!Files.isDirectory(generatedSources)) {
      throw new MojoExecutionException(
          "Expected generated sources at " + generatedSources + " but pgn produced none");
    }
    project.addCompileSourceRoot(generatedSources.toAbsolutePath().toString());
    getLog().info("Attached generated sources: " + generatedSources);

    String requiredVersion = null;
    Path generatedPom = stagingDir.resolve("artifacts/java/pom.xml");
    try {
      if (Files.isRegularFile(generatedPom)) {
        requiredVersion = DependencyCheck.requiredJdbcVersion(Files.readString(generatedPom));
      }
    } catch (IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
    DependencyCheck.Result result =
        DependencyCheck.check(
            project.getArtifacts().stream()
                .filter(a ->
                    Artifact.SCOPE_COMPILE.equals(a.getScope())
                        || Artifact.SCOPE_PROVIDED.equals(a.getScope()))
                .map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
                .collect(Collectors.toList()),
            requiredVersion);
    switch (result.status) {
      case MISSING:
        throw new MojoFailureException(result.message);
      case VERSION_MISMATCH:
        getLog().warn(result.message);
        break;
      case OK:
        break;
    }
  }
}
