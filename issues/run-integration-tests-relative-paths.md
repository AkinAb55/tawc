# scripts/run-integration-tests.sh still has a few relative paths

`scripts/run-integration-tests.sh` is mostly `$ROOT_DIR`-based now, but
still references a few paths relative to the caller's cwd:
- `tests/apps/tawc-pidfile-exec.sh`
- `tawcroot/build.sh`, `tawcroot/build-fixtures.sh`

`set -e` means a cwd mismatch fails loudly, but using `$ROOT_DIR/...`
prefixes everywhere would be more robust and consistent with the rest
of the file.
