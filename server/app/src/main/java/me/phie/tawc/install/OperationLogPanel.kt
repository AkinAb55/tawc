package me.phie.tawc.install

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shared "operation in progress" UI. Builds a status line + progress
 * bar + scrolling log, manages a binding to [InstallationService] for
 * the lifetime of [activity], and updates the views off the service's
 * progress and log flows.
 *
 * Owners attach [view] into their layout, call [bindToService] from
 * `onStart` and [unbind] from `onStop`. Local log lines (e.g. "[ui]
 * starting install") can be pushed in via [appendLog].
 */
class OperationLogPanel(private val activity: Activity) {

    val view: LinearLayout
    private val statusText: TextView
    private val progressBar: ProgressBar
    private val logText: TextView
    private val logScroll: ScrollView

    private var service: InstallationService? = null
    private var collectScope: CoroutineScope? = null

    init {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()

        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        statusText = TextView(activity).apply { text = "" }
        view.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        view.addView(progressBar, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        logScroll = ScrollView(activity)
        logText = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        logScroll.addView(logText)
        view.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

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

    fun bindToService() {
        activity.bindService(
            Intent(activity, InstallationService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        try { activity.unbindService(connection) } catch (_: IllegalArgumentException) { /* not bound */ }
        collectScope?.cancel()
        collectScope = null
        service = null
    }

    fun setStatus(text: String) {
        statusText.text = text
    }

    fun appendLog(line: String) {
        // Cap on-screen log to keep memory bounded; full history is in logcat.
        val cur = logText.text
        if (cur.length > 80_000) {
            logText.text = cur.subSequence(40_000, cur.length).toString()
        }
        logText.append(line)
        logText.append("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startCollecting() {
        collectScope?.cancel()
        val s = service ?: return
        val cs = CoroutineScope(Dispatchers.Main)
        collectScope = cs

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
                    InstallStage.IDLE, InstallStage.DONE, InstallStage.FAILED -> View.GONE
                    else -> View.VISIBLE
                }
            }
        }

        cs.launch {
            s.log.collect { line -> appendLog(line) }
        }
    }

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }
}
