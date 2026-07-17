package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyCheckTest {
  private static final String GENERATED_POM =
      "<project><dependencies>\n"
          + "  <dependency>\n"
          + "    <groupId>io.codemine.java.postgresql</groupId>\n"
          + "    <artifactId>jdbc</artifactId>\n"
          + "    <version>1.4.0</version>\n"
          + "  </dependency>\n"
          + "</dependencies></project>\n";

  @Test void extractsRequiredVersion() {
    assertEquals("1.4.0", DependencyCheck.requiredJdbcVersion(GENERATED_POM));
    assertNull(DependencyCheck.requiredJdbcVersion("<project/>"));
  }

  @Test void okWhenPresentAtRightVersion() {
    DependencyCheck.Result r =
        DependencyCheck.check(List.of("io.codemine.java.postgresql:jdbc:1.4.0"), "1.4.0");
    assertEquals(DependencyCheck.Status.OK, r.status);
  }

  @Test void mismatchWhenPresentAtOtherVersion() {
    DependencyCheck.Result r =
        DependencyCheck.check(List.of("io.codemine.java.postgresql:jdbc:1.3.0"), "1.4.0");
    assertEquals(DependencyCheck.Status.VERSION_MISMATCH, r.status);
    assertTrue(r.message.contains("1.3.0"));
    assertTrue(r.message.contains("1.4.0"));
  }

  @Test void missingProducesSnippet() {
    DependencyCheck.Result r = DependencyCheck.check(List.of("org.slf4j:slf4j-api:2.0.16"), "1.4.0");
    assertEquals(DependencyCheck.Status.MISSING, r.status);
    assertTrue(r.message.contains("<groupId>io.codemine.java.postgresql</groupId>"));
    assertTrue(r.message.contains("<artifactId>jdbc</artifactId>"));
    assertTrue(r.message.contains("<version>1.4.0</version>"));
  }
}
