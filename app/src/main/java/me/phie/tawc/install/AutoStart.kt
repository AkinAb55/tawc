package me.phie.tawc.install

import android.content.Intent

/**
 * Shared "autoStart" intent-extra contract for [InstallActivity] and
 * [UninstallActivity].
 *
 * Activities that perform mutating operations (install / uninstall a
 * Linux rootfs) **must not** trigger those operations as a side-effect
 * of being created. The two acceptable triggers are:
 *
 *   1. A user-visible button press inside the activity.
 *   2. An intent that explicitly carries `autoStart=true` (this extra).
 *
 * A bare `am start` into the activity, or the system re-launching the
 * activity from a recents card, is **not** a trigger — the page opens
 * and shows whatever the bound [InstallationService]'s last operation
 * status was, but no new operation runs. This matters because
 * activities stay in recents after their first launch, and the user
 * tapping the app switcher should never restart a long-running
 * operation that they thought was finished.
 *
 * `--ez autoStart true` (boolean) and `--es autoStart true` (string)
 * are both accepted so the `am start` invocation is forgiving.
 *
 * The autoStart extra is paired with a `savedInstanceState == null`
 * check at the call site so a config-change recreate doesn't re-fire
 * even though the launch intent still carries the extra.
 */
const val EXTRA_AUTO_START = "autoStart"

fun Intent?.requestsAutoStart(): Boolean {
    val intent = this ?: return false
    if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) return true
    return intent.getStringExtra(EXTRA_AUTO_START)?.equals("true", ignoreCase = true) == true
}
