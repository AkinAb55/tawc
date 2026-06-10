package me.phie.tawc.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * Runtime gate for the external-storage binds feature
 * (notes/external-binds.md), built on Android 11+'s "all files access"
 * (`MANAGE_EXTERNAL_STORAGE`).
 *
 * Two independent layers:
 *   - [declared]: the permission is in this APK's manifest at all. A
 *     `-PtawcAllFilesAccess=false` build strips it; every binds UI
 *     surface hides itself when this is false.
 *   - [granted]: the user has flipped the "All files access" toggle in
 *     system settings ([settingsIntent] deep-links there). Binds whose
 *     host path needs the grant ([requiresGrant]) refuse to spawn
 *     without it — fail closed, never an empty stand-in dir.
 */
object AllFilesAccess {
    private const val PERMISSION = "android.permission.MANAGE_EXTERNAL_STORAGE"

    /** In-rootfs path the Android user's home (shared storage) is bound at by default. */
    const val DEFAULT_GUEST_HOME = "/home/android"

    /** In-rootfs path the Android root dir is bound at by default. */
    const val DEFAULT_GUEST_ROOT = "/android"

    fun declared(context: Context): Boolean {
        // The all-files-access model is Android 11+; on the one older
        // API level we support (29) the feature is simply absent.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        val pi = try {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return pi.requestedPermissions?.contains(PERMISSION) == true
    }

    fun granted(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()

    /**
     * Open this app's "All files access" toggle in system settings.
     * Try-then-catch rather than `resolveActivity`: with target SDK
     * 30+ package-visibility filtering, resolveActivity returns null
     * for Settings without a `<queries>` declaration even though
     * startActivity succeeds. Falls back to the all-apps list screen
     * for settings apps that don't handle the per-app form.
     */
    fun openSettings(context: Context) {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(perApp)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * Does binding [hostPath] need the all-files grant? Shared storage
     * (`/storage/...`, `/sdcard/...`) is unreadable to the app uid
     * without it. Everything else (e.g. the default `/` bind) is
     * governed by ordinary SELinux/DAC, where partial unreadability is
     * expected and not a config error.
     */
    fun requiresGrant(hostPath: String): Boolean {
        val p = hostPath.trimEnd('/')
        return p == "/storage" || hostPath.startsWith("/storage/") ||
            p == "/sdcard" || hostPath.startsWith("/sdcard/")
    }

    fun requiresGrant(binds: List<ExternalBind>): Boolean =
        binds.any { requiresGrant(it.hostPath) }

    /**
     * Default bind set for fresh tawcroot installs: the Android root
     * at /android (much of it unreadable to the app uid — expected)
     * and the Android user's home (shared storage) at /home/android.
     * Editable/removable like any user-added bind.
     */
    fun defaultBinds(): List<ExternalBind> = listOf(
        ExternalBind(
            hostPath = "/",
            guestPath = DEFAULT_GUEST_ROOT,
            label = "Android root",
        ),
        ExternalBind(
            hostPath = Environment.getExternalStorageDirectory().absolutePath,
            guestPath = DEFAULT_GUEST_HOME,
            label = "Android home",
        ),
    )
}
