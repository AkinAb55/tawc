package me.phie.tawc.install

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Install new distro" screen. Shows a read-only summary of what's
 * about to be installed (distro, detected CPU arch, install path) until
 * the user taps Install, then swaps to a live progress + log view
 * bound to [InstallationService] via [OperationLogPanel].
 *
 * `am start … --es autoStart true --es id <id>` skips the form and
 * triggers the install immediately (used by integration tests + the
 * `am start` install hook documented in notes/installation.md).
 */
class InstallActivity : Activity() {

    private val store by lazy { InstallationStore(this) }
    private var targetId: String = Installation.DISTRO_ARCH

    private lateinit var formSection: LinearLayout
    private lateinit var installButton: Button
    private lateinit var panel: OperationLogPanel

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetId = intent?.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        TextView(this).apply {
            text = "Install distro"
            textSize = 22f
            gravity = Gravity.START
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }

        formSection = buildFormSection(pad)
        root.addView(formSection, lp(MATCH_PARENT, WRAP_CONTENT))

        panel = OperationLogPanel(this)
        panel.view.visibility = View.GONE
        root.addView(panel.view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (autoStartRequested(intent)) {
            intent?.removeExtra(EXTRA_AUTO_START)
            beginInstall()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetId = intent.getStringExtra(EXTRA_ID) ?: targetId
        if (autoStartRequested(intent)) {
            intent.removeExtra(EXTRA_AUTO_START)
            beginInstall()
        }
    }

    // `am start --es autoStart true` sends a string extra; `--ez autoStart
    // true` sends a boolean. Accept either so the CLI is forgiving.
    private fun autoStartRequested(intent: Intent?): Boolean {
        intent ?: return false
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) return true
        return intent.getStringExtra(EXTRA_AUTO_START)?.equals("true", ignoreCase = true) == true
    }

    override fun onStart() {
        super.onStart()
        panel.bindToService()
    }

    override fun onStop() {
        super.onStop()
        panel.unbind()
    }

    private fun buildFormSection(pad: Int): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        s.addView(formRow("Distro:", "Arch"), lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        s.addView(formRow("Architecture:", primaryArch()), lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        s.addView(
            formRow("Install location:", store.installationDir(targetId).absolutePath),
            lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        installButton = Button(this).apply {
            text = "Install"
            setOnClickListener { beginInstall() }
        }
        if (store.list().any { it.id == targetId }) {
            installButton.isEnabled = false
            installButton.text = "Install (already installed)"
        }
        s.addView(installButton, lp(MATCH_PARENT, WRAP_CONTENT))
        return s
    }

    private fun formRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val l = TextView(this).apply { text = label; textSize = 14f }
        val v = TextView(this).apply { text = value; textSize = 14f; typeface = Typeface.MONOSPACE }
        row.addView(l, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 16 })
        row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun beginInstall() {
        if (started) return
        if (!Su.rootAvailable()) {
            // Surface the error in the log area so the form stays visible
            // for the user to read the install location alongside.
            formSection.visibility = View.GONE
            panel.view.visibility = View.VISIBLE
            panel.setStatus("ERROR: root (su) not available — Magisk must grant this app.")
            return
        }
        started = true
        formSection.visibility = View.GONE
        panel.view.visibility = View.VISIBLE
        panel.appendLog("[ui] starting install of '$targetId'")
        InstallationService.startInstall(this, targetId)
    }

    private fun primaryArch(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_AUTO_START = "autoStart"
    }
}
