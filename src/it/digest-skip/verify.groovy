File log = new File(basedir, 'target/generated-sources/pgenie/staging/invocations.log')
assert log.exists()
assert log.readLines().size() == 1 : "second build must skip pgn, got:\n" + log.text
return true
