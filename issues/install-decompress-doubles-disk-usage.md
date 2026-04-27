- The x86_64 install path materializes the decompressed tarball to disk
  before extraction, so peak install-time disk usage is roughly
  `compressed tarball + uncompressed tarball + rootfs` (~180 MB +
  ~700 MB + ~900 MB ≈ 1.8 GB).
- See `Archive.ensurePlainTar` (server/app/src/main/java/me/phie/tawc/install/Archive.kt).
  The intermediate `.tar` exists because piping zstd-decompressed bytes
  into `su`'s stdin loses data — the shell pre-buffers stdin past the
  script body and tar then sees garbage ("tar: Not tar"). aarch64 isn't
  affected because toybox tar reads gzip natively, so no intermediate is
  written.
- Fix: write the zstd stream into a named pipe (FIFO) under `cacheDir`
  and have the `su` script pass it to `tar -xf <fifo>` as a positional
  argument (not stdin). The shell never touches the tar bytes, so the
  pre-buffering bug doesn't apply, and we never materialize the full
  uncompressed tar. Peak drops to `compressed + rootfs`.
- Implementation sketch:
  - Kotlin side: `mkfifo` a path in `cacheDir`, fork a thread that
    decompresses zstd → fifo, then `Su.run("tar -xf <fifo> -C $rootfs")`.
  - Or: have the `su` script `mkfifo`, then run `zstd -d` inside the
    same shell as a coproc feeding tar — keeps the dance one-sided.
- Not urgent — works as-is, but bites users with tight storage.
