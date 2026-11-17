package com.retroportlab.nuondroid

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * The look shared with JagDroid: dark panels, an accent outline on whatever the
 * gamepad has focused, bold-italic two-tone wordmark. Everything is built in code.
 */
class Ui(private val ctx: Context) {
    companion object {
        const val BG = 0xFF0D0E12.toInt()
        const val PANEL = 0xFF181A21.toInt()
        const val PANEL_FOCUS = 0xFF262A35.toInt()
        const val ACCENT = 0xFF1FC8E0.toInt()     // NUONDroid cyan (JagDroid uses red)
        const val TEXT = 0xFFF2F2F5.toInt()
        const val SUBTEXT = 0xFF9AA0AE.toInt()
        const val BADGE = 0xFF2E7D4F.toInt()
        const val FOOTER = "A  select     B  back     Select + Start  in-game menu"
    }

    fun dp(v: Float): Int = Math.round(v * ctx.resources.displayMetrics.density)

    fun rounded(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10f).toFloat()
        if (stroke != 0) setStroke(dp(2f), stroke)
    }

    /** Card background that lights up with an accent outline when focused by the gamepad. */
    fun cardBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(PANEL_FOCUS, ACCENT))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(PANEL_FOCUS, ACCENT))
        addState(intArrayOf(), rounded(PANEL, 0))
    }

    fun text(s: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** A focusable row with a title, optional subtitle and optional value on the right. */
    fun row(title: String, subtitle: String?, value: String?, click: (View) -> Unit): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        r.background = cardBg()
        r.isFocusable = true
        r.isClickable = true
        r.setOnClickListener(click)
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(text(title, 17f, TEXT, true).apply { tag = "title" })
        if (!subtitle.isNullOrEmpty()) texts.addView(text(subtitle, 13f, SUBTEXT, false).apply { tag = "subtitle" })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(text(value, 15f, ACCENT, true).apply { tag = "value" })
        r.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) }
        return r
    }

    fun setRowValue(row: View, value: String) { (row.findViewWithTag<View>("value") as? TextView)?.text = value }
    fun setRowSubtitle(row: View, s: String) { (row.findViewWithTag<View>("subtitle") as? TextView)?.text = s }
    fun setRowTitle(row: View, title: String) { (row.findViewWithTag<View>("title") as? TextView)?.text = title }

    /** Turns a row's value into a filled pill (update banner, badges). */
    fun pillValue(row: View, fill: Int) {
        val b = row.findViewWithTag<View>("value") as? TextView ?: return
        b.setTextColor(TEXT)
        b.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        b.background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(6f).toFloat() }
    }

    fun sectionTitle(s: String): TextView = text(s.uppercase(Locale.US), 12f, SUBTEXT, true).apply {
        letterSpacing = 0.12f
        setPadding(dp(4f), dp(14f), 0, dp(6f))
    }

    /** Header bar: wordmark, a subtitle and a few action buttons. */
    fun header(subtitle: String, vararg actions: View): LinearLayout {
        val h = LinearLayout(ctx)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(dp(24f), dp(16f), dp(24f), dp(8f))
        val italic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        h.addView(text("NUON", 26f, ACCENT, true).apply { typeface = italic })
        h.addView(text("DROID", 26f, TEXT, true).apply { typeface = italic })
        h.addView(text("   $subtitle", 14f, SUBTEXT, false), LinearLayout.LayoutParams(0, -2, 1f))
        for (a in actions) h.addView(a, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        return h
    }

    fun pill(label: String, click: (View) -> Unit): TextView = text(label, 14f, TEXT, true).apply {
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        background = cardBg()
        isFocusable = true
        isClickable = true
        setOnClickListener(click)
    }

    /** Replaces the screen with header + scrolling list + controller hint footer. */
    fun page(root: FrameLayout, subtitle: String, list: LinearLayout, vararg actions: View) {
        root.removeAllViews()
        root.setBackgroundColor(BG)
        val page = LinearLayout(ctx)
        page.orientation = LinearLayout.VERTICAL
        page.addView(header(subtitle, *actions))
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(24f), 0, dp(24f), dp(16f))
            clipToPadding = false
            addView(list)
        }
        page.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(text(FOOTER, 12f, SUBTEXT, false).apply { setPadding(dp(28f), dp(4f), 0, dp(10f)) })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
    }
}
