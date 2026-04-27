## ALARM bootstrap — weaker integrity story than Arch x86_64

The aarch64 (ALARM) bootstrap is currently verified by **cross-mirror
MD5 over HTTPS**: download `.md5` from `fl.us.` and `ca.us.` (two
independently-operated ALARM mirrors with valid Let's Encrypt certs
for their own hostname), require byte-for-byte agreement, then check
the tarball's MD5 against the consensus.

Arch x86_64 instead has full PGP — `archlinux-bootstrap-x86_64.tar.zst.sig`
is signed by Pierre Schmitz's developer key, verified against the
key bundle shipped at `res/raw/arch_signing_key.asc`.

The asymmetry is upstream's: ALARM doesn't publish a PGP signature
for the bootstrap tarball at all (only an `.md5` sidecar; no `.sig`).

## Threat model gap

**Cross-mirror MD5 over HTTPS catches:**

- Passive WiFi / coffee-shop / ISP MITM (TLS).
- A single mirror or CDN POP being compromised in isolation.
- Random transmission corruption.

**It does NOT catch:**

- Both `fl.us.mirror.archlinuxarm.org` AND `ca.us.mirror.archlinuxarm.org`
  being compromised concurrently with a coordinated forgery (same
  upstream operator runs them; one nation-state attacker against the
  underlying CDN/hosting is plausible).
- Let's Encrypt mis-issuing certs for both subdomains.
- An MD5 second-preimage attack on a tarball binary (still expensive
  in 2026, but it's MD5 — not where you want your floor).

PGP would defeat all three: the signature is bound to a key whose
fingerprint we ship with the app and which is independent of the
mirror infrastructure entirely.

## Acceptance — promote to PGP when upstream signs

The clean upgrade is for ALARM upstream to start signing:

1. ALARM publishes `ArchLinuxARM-aarch64-latest.tar.gz.sig` signed by
   one of their developer keys (anyone in
   https://archlinuxarm.org/about/contributors-and-staff would do).
2. We grab the corresponding key, ship it at
   `res/raw/alarm_signing_key.asc`, and flip
   `ArchLinuxArm.bootstrap.verification` from
   `BootstrapVerification.CrossMirrorMd5(...)` to
   `BootstrapVerification.Pgp(signatureUrl, "alarm_signing_key")`.
   That's the only code change.

Until then, the cross-mirror MD5 is the floor. Periodically re-check
whether ALARM has started signing.

## Alternative paths, ranked

If ALARM upstream stays unsigned indefinitely, options in increasing
order of effort:

1. **Add a third independent HTTPS mirror to the cross-check.** Strict
   improvement to the cross-mirror agreement floor.
2. **Bootstrap-via-pacstrap from the verified Arch x86_64 rootfs +
   `qemu-aarch64-static`.** Use Pierre's PGP-signed Arch x86_64
   bootstrap to install ALARM packages cross-arch via pacman; the
   ALARM packages are signed by `archlinuxarm-keyring` which Arch's
   keyring trusts via the keyring package's own signature. This
   inherits Arch's PGP web of trust end-to-end and would obsolete the
   tarball-fetching path entirely. Heavy: needs qemu-user-static
   embedded in the APK, and a working pacstrap inside the bootstrap.
3. **Mirror the bootstrap ourselves and sign with our own key.** Set
   up a TLS-protected endpoint we control, sign the tarball with a
   key shipped with the app. Adds infra cost (bandwidth, key
   rotation) but gives us full custody.

(1) is the cheap follow-up if a third HTTPS-capable ALARM mirror
becomes available. (2) is the elegant "right answer" but expensive.
(3) is the nuclear option.

## Affected code

- `server/app/src/main/java/me/phie/tawc/install/distro/arch/ArchLinuxArm.kt:22-46`
  — bootstrap URL + `BootstrapVerification.CrossMirrorMd5` declaration.
- `server/app/src/main/java/me/phie/tawc/install/SignatureVerifier.kt`
  — `verifyCrossMirrorMd5(...)` implementation.
- `notes/installation.md` — *Bootstrap integrity* section. The "Hard
  rules" there forbid silently downgrading any of this; this issue is
  the one place to track and discuss the gap.
