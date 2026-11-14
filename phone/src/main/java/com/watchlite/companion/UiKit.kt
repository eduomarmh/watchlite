package com.watchlite.companion

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Small programmatic UI kit shared by the companion screens (dark, violet accent, Material Symbols icons). */
class UiKit(private val a: Activity) {
    val accent = 0xFF7B6CFF.toInt()
    val card = 0xFF17171F.toInt()
    val card2 = 0xFF20202A.toInt()
    val text2 = 0xFF9A9AA6.toInt()
    val white = 0xFFFFFFFF.toInt()

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()
    fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.resources.displayMetrics)

    fun tv(s: CharSequence, size: Float = 15f, color: Int = white, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun bg(color: Int, r: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    fun icon(res: Int, tint: Int, sizeDp: Int = 22): Drawable =
        a.getDrawable(res)!!.mutate().apply { setTint(tint); setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }

    fun iconView(res: Int, tint: Int, sizeDp: Int = 22) = ImageView(a).apply {
        setImageDrawable(icon(res, tint, sizeDp)); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun vbox() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    fun hbox() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    fun button(label: String, iconRes: Int? = null, primary: Boolean = false, onClick: () -> Unit) = Button(a).apply {
        text = label; isAllCaps = false; textSize = 14f
        val fg = if (primary) white else accent
        setTextColor(fg)
        if (iconRes != null) { setCompoundDrawablesRelative(icon(iconRes, fg, 20), null, null, null); compoundDrawablePadding = dp(8) }
        gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        background = bg(if (primary) accent else 0xFF262633.toInt(), 14); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun row(vararg views: View) = hbox().apply {
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) }) }
    }

    /** Toolbar: back arrow + title (+ optional trailing view). */
    fun toolbar(title: String, trailing: View? = null): LinearLayout = hbox().apply {
        addView(ImageView(a).apply {
            setImageDrawable(icon(R.drawable.ic_arrow_back, white, 24)); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = bg(card, 20); setOnClickListener { a.finish() }
            if (a.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(tv(title, 21f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        if (trailing != null) addView(trailing)
    }

    fun sectionTitle(title: String, iconRes: Int) = hbox().apply {
        addView(iconView(iconRes, accent, 18))
        addView(tv(title, 13f, accent, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    fun cardBox() = vbox().apply { background = bg(card); setPadding(dp(16), dp(14), dp(16), dp(14)) }

    /** Segmented control: returns the row; calls [onPick] with the chosen key. */
    fun segmented(items: List<Triple<String, String, Int?>>, selected: String, onPick: (String) -> Unit): LinearLayout {
        val r = hbox().apply { background = bg(card2, 14); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val views = LinkedHashMap<String, TextView>()
        fun paint(sel: String) = views.forEach { (k, b) ->
            val on = k == sel
            b.background = if (on) bg(accent, 11) else null
            b.setTextColor(if (on) white else text2)
            b.compoundDrawablesRelative[0]?.setTint(if (on) white else text2)
        }
        for ((k, label, ic) in items) {
            val b = tv(label, 13f, text2).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), dp(10)); maxLines = 1
                if (ic != null) { compoundDrawablePadding = dp(6); setCompoundDrawablesRelative(icon(ic, text2, 18), null, null, null) }
                setOnClickListener { paint(k); onPick(k) }
            }
            views[k] = b
            r.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint(selected)
        return r
    }

    /** Horizontal list of round color swatches. */
    fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): View {
        val r = hbox()
        val views = ArrayList<View>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(if (colors[i] == sel) dp(3) else dp(1), if (colors[i] == sel) white else 0x33FFFFFF)
        }
        for (c in colors) {
            val v = View(a).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { paint(c); onPick(c) }
            }
            views += v
            r.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        }
        paint(selected)
        return android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(r); setPadding(0, dp(4), 0, dp(4)) }
    }

    fun slider(label: String, iconRes: Int, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val box = vbox().apply { setPadding(0, dp(6), 0, dp(2)) }
        val head = hbox()
        head.addView(iconView(iconRes, accent, 20))
        head.addView(tv(label, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        val v = tv(fmt(value), 13f, text2); head.addView(v)
        box.addView(head)
        box.addView(SeekBar(a).apply {
            this.max = max - min; progress = value - min
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3A48.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { v.text = fmt(p + min); if (fromUser) onChange(p + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(36)))
        return box
    }

    fun switchRow(label: String, iconRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = hbox().apply {
        setPadding(0, dp(6), 0, dp(6))
        addView(iconView(iconRes, accent, 20))
        addView(tv(label, 14.5f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        addView(android.widget.Switch(a).apply {
            isChecked = checked; thumbTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    fun badge(res: Int, tint: Int, size: Int = 40) = FrameLayout(a).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((tint and 0x00FFFFFF) or 0x2A000000) }
        addView(iconView(res, tint, size / 2), FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
    }

    fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show()
}
