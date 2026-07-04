package me.phie.tawc.install

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import me.phie.tawc.R

/**
 * The shared ando toggle row (notes/ando.md): checkbox + the one-line
 * description, used by both the install form ([InstallActivity]) and
 * distro settings ([DistroInfoActivity]) — the two differ only in what
 * [onChange] does. The checkbox is passed back so a commit-path caller
 * can revert it on failure.
 */
internal fun buildAndoToggleRow(
    context: Context,
    checked: Boolean,
    onChange: (checkbox: CheckBox, checked: Boolean) -> Unit,
): LinearLayout {
    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    container.addView(
        CheckBox(context).apply {
            text = context.getString(R.string.ando_toggle_label)
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(this, c) }
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    container.addView(
        TextView(context).apply {
            text = context.getString(R.string.ando_toggle_description)
            textSize = 12f
            alpha = 0.7f
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    return container
}
