String log = new File(basedir, 'build.log').text
assert log.contains('<groupId>io.codemine.java.postgresql</groupId>')
assert log.contains('<artifactId>jdbc</artifactId>')
assert log.contains('<version>1.4.0</version>')
return true
