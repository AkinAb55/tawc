package me.phie.tawc.install

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Uninstall" screen. Asks for confirmation before deleting an
 * installation, then runs the uninstall via [InstallationService] and
 * shows live progress + log via [OperationLogPanel].
 *
 * `am start … --es autoStart true --es id <id>` skips the confirmation
 * (used by integration tests + the headless uninstall hook).
 */
class UninstallActivity : Activity() {

    private val store by lazy { InstallationStore(this) }
    private var targetId: String = Installation.DISTRO_ARCH

    private lateinit var confirmSection: LinearLayout
    private lateinit var panel: OperationLogPanel

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetId = intent?.getStringExtra(EXTRA_ID) ?: Installation.DISTRO_ARCH
        started = savedInstanceState?.getBoolean(KEY_STARTED) == true

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        TextView(this).apply {
            text = "Uninstall"
            textSize = 22f
            gravity = Gravity.START
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }

        confirmSection = buildConfirmSection(pad)
        root.addView(confirmSection, lp(MATCH_PARENT, WRAP_CONTENT))

        panel = OperationLogPanel(this)
        panel.view.visibility = if (started) View.VISIBLE else View.GONE
        if (started) confirmSection.visibility = View.GONE
        root.addView(panel.view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)

        // autoStart fires once per launch; see InstallActivity for the
        // savedInstanceState rationale.
        if (savedInstanceState == null && autoStartRequested(intent)) {
            beginUninstall()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetId = intent.getStringExtra(EXTRA_ID) ?: targetId
        if (autoStartRequested(intent)) {
            beginUninstall()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTED, started)
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

    private fun buildConfirmSection(pad: Int): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val installation = store.load(targetId)
        val path = store.installationDir(targetId).absolutePath
        val name = installation?.let {
            "${it.distro.replaceFirstChar { ch -> ch.titlecase() }} (${it.arch})"
        } ?: targetId

        TextView(this).apply {
            text = "Permanently delete '$name'?"
            textSize = 16f
        }.also { s.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2)) }

        TextView(this).apply {
            text = "Location: $path"
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }.also { s.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val confirm = Button(this).apply {
            text = "Uninstall"
            setOnClickListener { beginUninstall() }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = 12 })
        row.addView(confirm, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        s.addView(row, lp(MATCH_PARENT, WRAP_CONTENT))
        return s
    }

    private fun beginUninstall() {
        if (!Su.rootAvailable()) {
            confirmSection.visibility = View.GONE
            panel.view.visibility = View.VISIBLE
            panel.setStatus("ERROR: root (su) not available — Magisk must grant this app.")
            return
        }
        confirmSection.visibility = View.GONE
        panel.view.visibility = View.VISIBLE
        // [InstallationService] is the authoritative gate; we just hand
        // off. `started` only tracks UI state (confirm vs panel) so a
        // process-death recreate restores the panel view.
        panel.appendLog(if (started) "[ui] re-requesting uninstall of '$targetId'"
                        else "[ui] starting uninstall of '$targetId'")
        started = true
        InstallationService.startUninstall(this, targetId)
    }

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_AUTO_START = "autoStart"
        private const val KEY_STARTED = "tawc.uninstall.started"
    }
}
