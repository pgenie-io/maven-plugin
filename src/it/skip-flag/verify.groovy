assert new File(basedir, 'build.log').text.contains('pGenie generation skipped')
assert !new File(basedir, 'target/pgenie').exists()
return true
