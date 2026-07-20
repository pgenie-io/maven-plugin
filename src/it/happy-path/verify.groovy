File staging = new File(basedir, 'target/pgenie/staging')

String projectYaml = new File(staging, 'project1.pgn.yaml').text
assert projectYaml.contains('space: io_pgenie_it')
assert projectYaml.contains('name: happy_path')
assert projectYaml.contains('version: 1.2.3')
assert projectYaml.contains('postgres: 18')
assert projectYaml.contains('useOptional: true')
assert projectYaml.contains('groupId: io.pgenie.it')
assert projectYaml.contains('artifactId: happy-path')

String freeze = new File(staging, 'freeze1.pgn.yaml').text
assert freeze.contains('java.gen') && freeze.contains('sha256:')

assert new File(staging, 'migrations/0001-init.sql').exists()
assert new File(staging, 'queries/select_one.sql').exists()

// copy-back into the source tree
assert new File(basedir, 'src/main/pgenie/queries/select_one.sig1.pgn.yaml').text.contains('stub-signature')

// generated code was compiled via the attached source root
assert new File(basedir, 'target/classes/gen/Generated.class').exists()
assert new File(basedir, 'target/classes/app/Uses.class').exists()

// digest written
assert new File(basedir, 'target/pgenie/digest').text.length() == 64

// only the compiled package tree is exposed under generated-sources, nothing else
File exposed = new File(basedir, 'target/generated-sources/pgenie')
assert new File(exposed, 'src/main/java').exists()
assert !new File(exposed, 'staging').exists()
assert !new File(exposed, 'digest').exists()

// attachTests defaults to false: generated tests are never exposed or attached
assert !new File(basedir, 'target/generated-test-sources/pgenie').exists()
return true
