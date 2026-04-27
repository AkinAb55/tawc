package me.phie.tawc.install

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Manage installations" screen. Reflects [InstallationService] state
 * (current operation, progress percent, log tail) and provides Install
 * and Uninstall buttons. Same code paths as the broadcast receiver —
 * anything you can do here you can do from adb.
 */
class ManageInstallationsActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listText: TextView
    private lateinit var installButton: Button
    private lateinit var uninstallButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val store by lazy { InstallationStore(this) }
    private var targetId = Installation.DISTRO_ARCH

    private var service: InstallationService? = null
    private var scope: CoroutineScope? = null
    private var collectorJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? InstallationService.LocalBinder ?: return
            service = b.service
            startCollecting()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        TextView(this).apply {
            text = "Manage installations"
            textSize = 22f
            gravity = Gravity.START
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }

        statusText = TextView(this).apply { text = "Idle" }
        root.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        root.addView(progressBar, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        installButton = Button(this).apply {
            text = "Install Arch"
            setOnClickListener { onInstallClicked() }
        }
        uninstallButton = Button(this).apply {
            text = "Uninstall"
            setOnClickListener { onUninstallClicked() }
        }
        buttonRow.addView(installButton, btnLp())
        buttonRow.addView(uninstallButton, btnLp())
        root.addView(buttonRow, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        TextView(this).apply {
            text = "Installations on this device:"
            textSize = 14f
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT)) }

        listText = TextView(this).apply {
            text = "(scanning…)"
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(listText, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        TextView(this).apply {
            text = "Log:"
            textSize = 14f
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT)) }

        logScroll = ScrollView(this)
        logText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        logScroll.addView(logText)
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)

        refreshList()
        handleAutoActionIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoActionIntent(intent)
    }

    /**
     * If we were launched with an `autoAction` extra (typically by
     * [InstallationCommandReceiver] forwarding an adb broadcast), kick
     * the corresponding service start as soon as the activity is up.
     * From an Activity context, `startForegroundService()` is always
     * permitted, which is the whole point of routing through here.
     */
    private fun handleAutoActionIntent(intent: Intent?) {
        intent ?: return
        val autoAction = intent.getStringExtra(InstallationCommandReceiver.EXTRA_AUTO_ACTION) ?: return
        val id = intent.getStringExtra(InstallationCommandReceiver.EXTRA_ID) ?: targetId
        targetId = id
        // Clear the extra so a configuration change doesn't re-trigger.
        intent.removeExtra(InstallationCommandReceiver.EXTRA_AUTO_ACTION)
        when (autoAction) {
            "install" -> onInstallClicked()
            "uninstall" -> onUninstallClicked()
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to (and start, if not running) the install service so we
        // get the live progress flow even if we joined mid-operation.
        bindService(
            Intent(this, InstallationService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: IllegalArgumentException) { /* not bound */ }
        scope?.cancel()
        scope = null
        collectorJob = null
        service = null
    }

    private fun startCollecting() {
        scope?.cancel()
        val s = service ?: return
        val cs = CoroutineScope(Dispatchers.Main)
        scope = cs

        cs.launch {
            s.progress.collectLatest { p ->
                statusText.text = p.message
                if (p.percent != null) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = p.percent
                } else {
                    progressBar.isIndeterminate = true
                }
                progressBar.visibility = when (p.stage) {
                    InstallStage.IDLE, InstallStage.DONE, InstallStage.FAILED -> android.view.View.GONE
                    else -> android.view.View.VISIBLE
                }
                if (p.stage == InstallStage.DONE || p.stage == InstallStage.FAILED) {
                    refreshList()
                }
            }
        }

        cs.launch {
            s.log.collect { line ->
                appendLog(line)
            }
        }
    }

    private fun appendLog(line: String) {
        // Cap on-screen log to keep memory bounded; full history is in logcat.
        val cur = logText.text
        if (cur.length > 80_000) {
            logText.text = cur.subSequence(40_000, cur.length).toString()
        }
        logText.append(line)
        logText.append("\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun refreshList() {
        val installs = store.list()
        listText.text = if (installs.isEmpty()) {
            "(none)"
        } else {
            installs.joinToString("\n") { i ->
                "${i.id}: distro=${i.distro} arch=${i.arch} method=${i.method}"
            }
        }
        val target = installs.firstOrNull { it.id == targetId }
        installButton.isEnabled = target == null
        uninstallButton.isEnabled = target != null
    }

    private fun onInstallClicked() {
        if (!Su.rootAvailable()) {
            statusText.text = "ERROR: root (su) not available — Magisk must grant this app."
            return
        }
        appendLog("[ui] starting install of '$targetId'")
        InstallationService.startInstall(this, targetId)
    }

    private fun onUninstallClicked() {
        if (!Su.rootAvailable()) {
            statusText.text = "ERROR: root (su) not available — Magisk must grant this app."
            return
        }
        appendLog("[ui] starting uninstall of '$targetId'")
        InstallationService.startUninstall(this, targetId)
    }

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }

    private fun btnLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = 12 }
}
