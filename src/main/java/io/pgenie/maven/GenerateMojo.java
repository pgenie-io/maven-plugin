package io.pgenie.maven;

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
    throw new MojoExecutionException("Not implemented yet");
  }
}
