package me.phie.tawc.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.verticalLp
import java.io.File

/**
 * Minimal in-app directory browser over real filesystem paths, used by
 * the manage-binds screen to pick an [ExternalBind.hostPath]. Browses
 * with the app's own credentials (the whole point is that the app has
 * all-files access — see notes/external-binds.md), deliberately not
 * SAF: a `content://` tree URI has no path tawcroot could bind.
 *
 * Unreadable directories still render (with a note) and can still be
 * picked — the default `/` bind is exactly such a partially-readable
 * tree, and DAC/SELinux opacity at browse time doesn't predict what
 * in-rootfs processes will be able to do with subpaths.
 */
class DirectoryPickerActivity : AppCompatActivity() {

    private lateinit var scaffold: me.phie.tawc.ui.Scaffold
    private lateinit var pathLabel: TextView
    private lateinit var listColumn: LinearLayout
    private var currentPath: File = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = savedInstanceState?.getString(KEY_PATH)
            ?: intent?.getStringExtra(EXTRA_START)
        currentPath = File(start ?: Environment.getExternalStorageDirectory().absolutePath)
        if (!currentPath.isDirectory) currentPath = File("/")

        scaffold = buildChildScreen(getString(R.string.title_directory_picker))
        val pad = (16 * resources.displayMetrics.density).toInt()

        pathLabel = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scaffold.content.addView(pathLabel, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listColumn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        scaffold.content.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        scaffold.content.addView(
            primaryButton(getString(R.string.dir_picker_use)) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, currentPath.absolutePath))
                finish()
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )

        setContentView(scaffold.root)
        renderDir()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PATH, currentPath.absolutePath)
    }

    private fun renderDir() {
        pathLabel.text = currentPath.absolutePath
        listColumn.removeAllViews()

        currentPath.parentFile?.let { parent ->
            listColumn.addView(dirRow("..") { currentPath = parent; renderDir() })
        }

        val subdirs = currentPath.listFiles { f -> f.isDirectory }?.sortedBy { it.name }
        when {
            subdirs == null -> listColumn.addView(noteRow(getString(R.string.dir_picker_unreadable)))
            subdirs.isEmpty() -> listColumn.addView(noteRow(getString(R.string.dir_picker_empty)))
            else -> for (d in subdirs) {
                listColumn.addView(dirRow("${d.name}/") { currentPath = d; renderDir() })
            }
        }
    }

    private fun dirRow(name: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = name
            textSize = 16f
            typeface = Typeface.MONOSPACE
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad / 2, pad, pad / 2, pad)
            // Standard pressed-state ripple so rows read as tappable.
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }

    private fun noteRow(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad / 2, pad, pad / 2, pad)
        }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_PATH = "path"
        private const val KEY_PATH = "tawc.dirpicker.path"

        fun intentFor(context: Context, start: String?): Intent =
            Intent(context, DirectoryPickerActivity::class.java).apply {
                if (!start.isNullOrEmpty()) putExtra(EXTRA_START, start)
            }
    }
}
