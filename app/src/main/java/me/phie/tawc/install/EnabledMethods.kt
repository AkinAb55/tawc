package me.phie.tawc.install

import me.phie.tawc.BuildConfig

/**
 * Build-time gate for the install methods this APK ships. Driven by the
 * `METHOD_*_ENABLED` BuildConfig fields set per-buildType in
 * `app/build.gradle.kts` (override with `-PtawcMethods=…`).
 *
 * Defaults: debug ships all three (tawcroot/proot/chroot for dev-loop
 * coverage), release ships only tawcroot. tawcroot is the default for
 * new installs and the only officially supported method — chroot/proot
 * remain available for development but are not exposed to release
 * users.
 *
 * The Kotlin classes for every method are always compiled (we can't
 * conditionally exclude classes from the source set without a flavor),
 * but [InstallationMethod.forKey] returns null for any method this APK
 * doesn't ship and the install UI hides the picker entirely when only
 * one method is enabled.
 */
object EnabledMethods {
    val tawcroot: Boolean = BuildConfig.METHOD_TAWCROOT_ENABLED
    val proot: Boolean = BuildConfig.METHOD_PROOT_ENABLED
    val chroot: Boolean = BuildConfig.METHOD_CHROOT_ENABLED

    /** Method keys this APK ships, in UI / fallback preference order. */
    val keys: List<String> = buildList {
        if (tawcroot) add(TawcrootMethod.KEY)
        if (proot) add(ProotMethod.KEY)
        if (chroot) add(ChrootMethod.KEY)
    }

    val onlyOne: Boolean get() = keys.size == 1

    fun isEnabled(key: String): Boolean = when (key) {
        TawcrootMethod.KEY -> tawcroot
        ProotMethod.KEY -> proot
        ChrootMethod.KEY -> chroot
        else -> false
    }
}
