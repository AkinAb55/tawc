package me.phie.tawc.terminal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.phie.tawc.R

/**
 * Compact tab strip for [TerminalActivity]:
 * `[ scrollable tabs ……… ][+]`. The strip scrolls horizontally; the
 * `+` button is pinned outside it at the right edge, always visible.
 * Each tab is an ellipsized label plus a small `×` close button.
 *
 * Imperative custom view, no XML layout (app style). Indices map 1:1
 * to `TerminalSessions.list(distroId)` — the bar never reorders; the
 * activity drives all mutations and supplies the callbacks. Click
 * handlers resolve the index at click time (`indexOfChild`) so
 * removals don't stale captured positions.
 *
 * Fixed dark palette regardless of day/night theme: the bar sits
 * against the always-black terminal/extra-keys surface, so
 * theme-following tonal colors would clash in light mode.
 */
internal class TerminalTabBar(context: Context) : LinearLayout(context) {

    var onTabSelected: (Int) -> Unit = {}
    var onTabCloseClicked: (Int) -> Unit = {}
    var onNewTabClicked: () -> Unit = {}

    private val scroller: HorizontalScrollView
    private val strip: LinearLayout
    private var selectedIndex = -1

    init {
        orientation = HORIZONTAL
        setBackgroundColor(BAR_BG)

        strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        addView(scroller, LayoutParams(0, MATCH_PARENT, 1f))

        val newTab = ImageButton(context).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(FG_UNSELECTED)
            setBackgroundColor(Color.TRANSPARENT)
            // ImageView's FIT_CENTER upscales the icon to the button
            // bounds; pad it back down to a small glyph.
            setPadding(dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP))
            contentDescription = context.getString(R.string.terminal_new_tab)
            setOnClickListener { onNewTabClicked() }
        }
        addView(newTab, LayoutParams(dp(NEW_TAB_WIDTH_DP), MATCH_PARENT))
    }

    /** Append a tab and scroll it into view; returns its index. */
    fun addTab(label: CharSequence): Int {
        val tab = buildTab(label)
        strip.addView(tab, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        scrollIntoView(tab)
        return strip.childCount - 1
    }

    fun removeTab(index: Int) {
        strip.removeViewAt(index)
        if (selectedIndex >= strip.childCount) selectedIndex = strip.childCount - 1
    }

    /** Highlight [index] and scroll it into view. */
    fun setSelected(index: Int) {
        selectedIndex = index
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i) as LinearLayout
            val selected = i == index
            tab.setBackgroundColor(if (selected) TAB_BG_SELECTED else Color.TRANSPARENT)
            (tab.getChildAt(0) as TextView)
                .setTextColor(if (selected) FG_SELECTED else FG_UNSELECTED)
            (tab.getChildAt(1) as ImageView)
                .imageTintList = ColorStateList.valueOf(if (selected) FG_SELECTED else FG_UNSELECTED)
        }
        strip.getChildAt(index)?.let { scrollIntoView(it) }
    }

    fun setLabel(index: Int, label: CharSequence) {
        val tab = strip.getChildAt(index) as? LinearLayout ?: return
        (tab.getChildAt(0) as TextView).text = label
    }

    private fun buildTab(label: CharSequence): View {
        val tab = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(TAB_PAD_H_DP), 0, 0, 0)
        }
        val text = TextView(context).apply {
            this.text = label
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(TAB_MAX_LABEL_DP)
            textSize = TAB_TEXT_SP
            setTextColor(FG_UNSELECTED)
        }
        tab.addView(text, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(FG_UNSELECTED)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP), dp(ICON_PAD_DP))
            contentDescription = context.getString(R.string.terminal_close_tab)
            setOnClickListener { onTabCloseClicked(strip.indexOfChild(tab)) }
        }
        tab.addView(close, LayoutParams(dp(CLOSE_WIDTH_DP), MATCH_PARENT))
        tab.setOnClickListener { onTabSelected(strip.indexOfChild(tab)) }
        return tab
    }

    private fun scrollIntoView(tab: View) {
        // Post: a freshly added/restyled tab has no geometry until the
        // next layout pass.
        scroller.post {
            tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), false)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BAR_BG = Color.parseColor("#1A1A1A")
        val TAB_BG_SELECTED = Color.parseColor("#404040")
        val FG_SELECTED = Color.parseColor("#FFFFFF")
        val FG_UNSELECTED = Color.parseColor("#9E9E9E")
        const val TAB_TEXT_SP = 13f
        const val TAB_MAX_LABEL_DP = 180
        const val TAB_PAD_H_DP = 12
        const val CLOSE_WIDTH_DP = 36
        const val NEW_TAB_WIDTH_DP = 44
        const val ICON_PAD_DP = 10
    }
}
