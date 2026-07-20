assert new File(basedir, 'build.log').text.contains('pGenie generation skipped')
assert !new File(basedir, 'target/pgenie').exists()
assert !new File(basedir, 'target/generated-sources/pgenie').exists()
return true
