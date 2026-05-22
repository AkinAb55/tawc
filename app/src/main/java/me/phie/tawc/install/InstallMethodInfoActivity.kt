package me.phie.tawc.install

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.verticalLp

/**
 * Read-only reference page describing the install methods this APK
 * ships ([EnabledMethods]). Linked from the install form when more
 * than one method is enabled — the single-method case skips the link
 * since there's nothing to compare. Content lives here (in code, not
 * `notes/installation.md`) so it ships in the APK and is reachable
 * offline.
 */
class InstallMethodInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_install_methods))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val content = scaffold.content

        if (EnabledMethods.tawcroot) {
            section(
                content, pad,
                getString(R.string.install_method_info_tawcroot_title),
                getString(R.string.install_method_info_tawcroot_body),
            )
        }
        if (EnabledMethods.proot) {
            section(
                content, pad,
                getString(R.string.install_method_info_proot_title),
                getString(R.string.install_method_info_proot_body),
            )
        }
        if (EnabledMethods.chroot) {
            section(
                content, pad,
                getString(R.string.install_method_info_chroot_title),
                getString(R.string.install_method_info_chroot_body),
            )
        }

        setContentView(scaffold.root)
    }

    private fun section(parent: LinearLayout, pad: Int, title: String, body: String) {
        TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }.also { parent.addView(it, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 4)) }

        TextView(this).apply {
            text = body
            textSize = 14f
        }.also { parent.addView(it, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad)) }
    }
}
