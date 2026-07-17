package io.pgenie.maven;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SanitizerTest {
  @Test void nameMapsDotsAndDashes() {
    assertEquals("io_pgenie", Sanitizer.name("io.pgenie"));
    assertEquals("my_app", Sanitizer.name("My-App"));
  }

  @Test void nameRejectsUnsanitizable() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.name("1weird"));
    assertTrue(e.getMessage().contains("1weird"));
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.name("héllo"));
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.name(""));
  }

  @Test void versionStripsQualifierAndPadsToThreeComponents() {
    assertEquals("1.2.3", Sanitizer.version("1.2.3"));
    assertEquals("1.2.3", Sanitizer.version("1.2.3-SNAPSHOT"));
    assertEquals("1.0.0", Sanitizer.version("1.0"));
    assertEquals("2.0.0", Sanitizer.version("2"));
  }

  @Test void versionRejectsNonNumeric() {
    assertThrows(IllegalArgumentException.class, () -> Sanitizer.version("abc"));
  }
}
