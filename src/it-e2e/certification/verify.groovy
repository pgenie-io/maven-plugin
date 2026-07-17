assert new File(basedir, 'target/classes').list().length > 0
File queries = new File(basedir, 'src/main/pgenie/queries')
assert queries.listFiles().any { it.name.endsWith('.sig1.pgn.yaml') }
return true
