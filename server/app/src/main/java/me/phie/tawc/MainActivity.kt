package me.phie.tawc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.compositor.CompositorService
import me.phie.tawc.install.InstallActivity
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.UninstallActivity

/**
 * Home screen for the tawc app. Starts the [CompositorService] (which
 * spawns the Rust compositor thread + Wayland socket), then renders a
 * list of currently-installed Linux environments and a button to
 * install a new one. Each row shows distro/arch, on-disk size, and a
 * Delete button that opens [UninstallActivity].
 */
class MainActivity : Activity() {

    private val store by lazy { InstallationStore(this) }
    private val rowMargin by lazy { (8 * resources.displayMetrics.density).toInt() }

    private lateinit var listContainer: LinearLayout
    private var refreshScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compositor is foreground/sticky and outlives this Activity; the
        // launcher tap is the natural place to ensure it's running.
        startForegroundService(Intent(this, CompositorService::class.java))

        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        TextView(this).apply {
            text = "tawc"
            textSize = 32f
            gravity = Gravity.START
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }

        TextView(this).apply {
            text = "Installations"
            textSize = 18f
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2)) }

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        Button(this).apply {
            text = "Install new distro"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, InstallActivity::class.java))
            }
        }.also { root.addView(it, lp(MATCH_PARENT, WRAP_CONTENT)) }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        refreshScope?.cancel()
        refreshScope = null
    }

    private fun refresh() {
        refreshScope?.cancel()
        val cs = CoroutineScope(Dispatchers.Main)
        refreshScope = cs

        listContainer.removeAllViews()
        val installs = store.list()
        if (installs.isEmpty()) {
            TextView(this).apply { text = "(none)" }
                .also { listContainer.addView(it, lp(MATCH_PARENT, WRAP_CONTENT)) }
            return
        }
        for (inst in installs) {
            listContainer.addView(buildRow(inst, cs), lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = rowMargin))
        }
    }

    private fun buildRow(inst: Installation, cs: CoroutineScope): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = displayName(inst)
            textSize = 16f
        }
        val sizeText = TextView(this).apply {
            text = "Size: …"
            textSize = 12f
        }
        info.addView(title, lp(MATCH_PARENT, WRAP_CONTENT))
        info.addView(sizeText, lp(MATCH_PARENT, WRAP_CONTENT))

        val deleteBtn = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                val i = Intent(this@MainActivity, UninstallActivity::class.java)
                    .putExtra(UninstallActivity.EXTRA_ID, inst.id)
                startActivity(i)
            }
        }

        row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(deleteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // Size needs `du` via su (rootfs is root-owned) — push it off
        // the main thread and update the row when it lands.
        cs.launch {
            val bytes = withContext(Dispatchers.IO) { store.computeSizeBytes(inst.id) }
            sizeText.text = when {
                bytes < 0 -> "Size: ?"
                else -> "Size: ${Formatter.formatFileSize(this@MainActivity, bytes)}"
            }
        }

        return row
    }

    private fun displayName(inst: Installation): String {
        val distro = inst.distro.replaceFirstChar { it.titlecase() }
        return "$distro (${inst.arch})"
    }

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }
}
