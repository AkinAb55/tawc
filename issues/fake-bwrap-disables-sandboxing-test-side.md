# `tests/apps/fake-bwrap` disables bubblewrap sandboxing — production users hit the same kernel limitation

The integration test setup script (`scripts/install-test-deps.sh`)
installs a wrapper script over `/usr/bin/bwrap` in the chroot rootfs:

```
HOST_BWRAP="$ROOT_DIR/tests/apps/fake-bwrap"
GUEST_BWRAP="/data/data/me.phie.tawc/distros/arch/rootfs/usr/bin/bwrap"
adb shell "su -c 'install -m 0755 $TAWC_SCRATCH/fake-bwrap $GUEST_BWRAP'"
```

`tests/apps/fake-bwrap` walks bubblewrap's known argv, throws away the
sandbox-flavour flags (`--unshare-user`, `--bind`, `--ro-bind`,
`--symlink`, `--setenv`, …), and `exec`s the COMMAND that follows
with **no isolation at all** — the loader process runs in the
calling process's environment.

The reason: stock Android kernels ship without `CONFIG_USER_NS`, so
even setuid bwrap can't `clone(NEWUSER)`. Modern Arch GTK + Firefox
both pull in glycin, which in turn execs bwrap to sandbox each image
loader. Without the replacement, every gtk-app integration test
crashes on the first SVG icon.

This unblocks the test suite, but:

## Production gap

End users running tawc on the same Android device will hit the same
`CONFIG_USER_NS` missing bug as soon as they:

- launch any Adwaita-themed GTK app that loads SVG icons
- launch Firefox (which uses glycin for image decode)
- launch any app that uses `bwrap`-via-flatpak-portal patterns

Today's user-facing experience is "app crashes silently on first
icon load" — exactly the failure mode the integration tests would
have hit before we installed the fake-bwrap workaround. There is
**no production-side fix shipped today**.

## What a real fix looks like

The cleanest answer turns out to be **don't ship fake-bwrap** —
glycin (the GTK/Adwaita image loader that pulls in bwrap) already
autodetects this case upstream:

- `SandboxSelector::Auto` (the default) calls
  `Sandbox::check_bwrap_syscalls_blocked()` on first use, which
  execs `bwrap --help` and looks for `"Creating new namespace
  failed"`, `"No permissions to create a new namespace"`, or
  `"bwrap: setting up uid map: Permission denied"` on stderr, or
  for a SIGSYS death.
- On a kernel without `CONFIG_USER_NS`, stock bwrap prints
  exactly `"Creating new namespace failed, likely because the
  kernel does not support user namespaces"` and exits.
- glycin then caches `SandboxMechanism::NotSandboxed` for the rest
  of the process lifetime and runs loaders without bwrap.

Our `fake-bwrap` actively defeats this mechanism by pretending to
succeed, so glycin never trips its fallback and we end up with
"sort of" sandboxing semantics that nobody actually asked for.

So the production fix is, paradoxically, to ship **real** bwrap
and let it fail honestly. No env var, no shim, no in-app pipeline
change needed beyond keeping `bubblewrap` as a regular package
dep.

### Open questions before we can drop fake-bwrap

1. **Why did tests crash without fake-bwrap?** The issue history
   says "every gtk-app integration test crashes on the first SVG
   icon" pre-fake-bwrap. If glycin's autodetect is supposed to
   handle this, fake-bwrap is masking a fixable upstream-side
   bug. Possibilities to verify:
   - Old glycin in the test rootfs lacking the autodetect
     (autodetect is in glycin 3.x; check rootfs version).
   - Code path bypassing glycin entirely (some lib `exec`ing
     bwrap directly).
   - Different stderr message from the Arch bwrap build that
     doesn't match glycin's substring list.
   The fix is to reproduce on a clean rootfs with real bwrap and
   read the actual error.

2. **Firefox is separate.** Its content sandbox is its own
   seccomp/namespace code — it only hits glycin transitively
   through `GtkFileChooser`-style widgets. The glycin fix doesn't
   help Firefox's main sandbox; for that we already set
   `MOZ_DISABLE_CONTENT_SANDBOX=1` in
   `RootfsProfile.kt:58`. Worth confirming that's enough on
   tawcroot.

### Fallback options if the autodetect approach doesn't pan out

In rough order of cost:

1. **Build `gdk-pixbuf` with `-Dglycin=disabled`** — Arch ships
   `gdk-pixbuf2-noglycin` in AUR for exactly this case. Skips
   glycin entirely; image decoding falls back to gdk-pixbuf's
   built-in loaders. Concrete, supported upstream, not a hack.
2. **Build a userspace bwrap shim using Landlock + seccomp-bpf**
   (both unprivileged, no USER_NS needed). Real partial sandbox:
   filesystem reads restricted, syscalls filtered. Stronger than
   fake-bwrap, weaker than real bwrap (no PID/net namespaces, no
   mount remapping). Bwrap-specific reimplementation, several
   weeks of work.
3. **Emulate `CLONE_NEWUSER` in tawcroot's seccomp handler.**
   Most generic — any user-namespace-using app (bwrap, Firefox
   content sandbox, flatpak portal) just works. The cheap form
   (return success, no real isolation) is equivalent in security
   to fake-bwrap; the real form (uid translation, mount-namespace
   emulation) is a major tawcroot project.
4. **Ship `fake-bwrap` from the in-app install pipeline.** Now
   actively a regression vs. just shipping real bwrap, since it
   blinds glycin's autodetect. Listed for completeness only.

(The previous "run under proot" option is gone — proot is no
longer a supported install method.)

## Why we didn't fix it inline

The test rig doing this in the test-deps script was exactly the
right call to unblock the integration suite — but the production
gap was filed away rather than addressed.

## References

- `tests/apps/fake-bwrap` (the script itself, with rationale comment)
- `scripts/install-test-deps.sh` (where it gets installed)
- `app/src/main/java/me/phie/tawc/install/RootfsProfile.kt:58`
  (where `MOZ_DISABLE_CONTENT_SANDBOX=1` is already set for the
  Firefox side)
- glycin sandbox autodetect:
  `glycin-core/src/sandbox.rs::check_bwrap_syscalls_blocked` in
  https://gitlab.gnome.org/GNOME/glycin
- glycin upstream issue #203 ("replace bwrap") — long-term
  upstream direction
