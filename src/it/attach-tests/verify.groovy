// attachTests=true exposes and attaches pgn's generated *IT.java sources
File exposedTests = new File(basedir, 'target/generated-test-sources/pgenie/src/test/java/gen/GeneratedIT.java')
assert exposedTests.exists()

// and they were actually compiled as test sources
assert new File(basedir, 'target/test-classes/gen/GeneratedIT.class').exists()
assert new File(basedir, 'target/classes/gen/Generated.class').exists()
return true
