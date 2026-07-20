File staging = new File(basedir, 'target/pgenie/staging')
String projectYaml = new File(staging, 'project1.pgn.yaml').text
assert projectYaml.contains('groupId: io.pgenie.it')
assert projectYaml.contains('artifactId: root-package-override')
assert projectYaml.contains('rootPackage: com.example.override')
return true
