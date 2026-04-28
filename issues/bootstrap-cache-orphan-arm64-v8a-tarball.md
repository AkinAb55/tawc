# BootstrapCache orphans an old arm64-v8a tarball

`BootstrapCache.sweepStale` doesn't recognize tarballs from the previous Android-ABI naming scheme, so a ~928 MB file lingers forever after the upgrade.

## Background

`BootstrapCache` keys cached tarballs by Linux arch (`bootstrap-aarch64.tar.gz`), but at some earlier point the key was the Android ABI (`bootstrap-arm64-v8a.tar.gz`). The `BootstrapCache.sweepStale` TTL janitor only knows about the current naming, so an old-format tarball is never considered for deletion.

## Observed

On the dev device today:

```
$ adb shell "su -c 'ls -la /data/data/me.phie.tawc/cache/install/'"
-rw------- ... 973680051 2026-04-27 17:20 bootstrap-aarch64.tar.gz
-rw------- ... 973680051 2026-04-27 04:21 bootstrap-arm64-v8a.tar.gz   # orphan
```

## Fix

Extend `BootstrapCache.sweepStale` to also unconditionally delete old-format names. The easy variant is to hard-code the few historical Android-ABI strings (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) alongside the canonical Linux arches; if one is found and the matching new-format entry exists, drop the orphan. The cleaner variant is to delete anything matching `bootstrap-*.tar.{gz,zst}` that doesn't match a registered Linux arch — the registry is small and known.

## Workaround

```
adb shell "su -c 'rm /data/data/me.phie.tawc/cache/install/bootstrap-arm64-v8a.tar.gz'"
```
