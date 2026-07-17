#!/bin/sh
# Fake pgn for integration tests. Records invocations, validates the staged
# project, and emits a canned java artifact plus a signature file.
set -e
echo "$PWD $*" >> invocations.log

test -f project1.pgn.yaml
test -f freeze1.pgn.yaml
test -d migrations

mkdir -p artifacts/java/src/main/java/gen
cat > artifacts/java/src/main/java/gen/Generated.java <<'EOF'
package gen;

public final class Generated {
  public static final String MARKER = "generated";

  private Generated() {}
}
EOF

cat > artifacts/java/pom.xml <<'EOF'
<project>
  <dependencies>
    <dependency>
      <groupId>io.codemine.java.postgresql</groupId>
      <artifactId>jdbc</artifactId>
      <version>1.4.0</version>
    </dependency>
  </dependencies>
</project>
EOF

mkdir -p queries
cat > queries/select_one.sig1.pgn.yaml <<'EOF'
result: stub-signature
EOF
