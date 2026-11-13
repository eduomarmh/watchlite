package com.watchlite.launcher.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.watchlite.launcher.App

/** Design tokens + view factories. Everything is sized from the screen's short side. */
class Ui(val ctx: Context, val sp: ScreenProfile = ScreenProfile.of(ctx)) {

    companion object {
        const val BG = 0xFF000000.toInt()
        const val CARD = 0xFF1C1C1E.toInt()
        const val CARD2 = 0xFF2C2C2E.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val TEXT2 = 0xFF8E8E93.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val BLUE = 0xFF0A84FF.toInt()
        const val RED = 0xFFFF453A.toInt()
        val ACCENTS = intArrayOf(
            0xFFFF9F0A.toInt(), 0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(),
            0xFFFF453A.toInt(), 0xFFFFD60A.toInt(), 0xFF64D2FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8C77A.toInt()
        )
    }

    val u = sp.u
    val accent get() = App.prefs.accent
    fun px(units: Float) = (units * u).toInt()

    fun text(s: CharSequence, size: Float = 6f, color: Int = TEXT, weight: Int = 1, lines: Int = 1): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
            typeface = when (weight) { 0 -> Fonts.uiLight(ctx); 2 -> Fonts.uiBold(ctx); else -> Fonts.ui(ctx) }
            includeFontPadding = false
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    fun rounded(color: Int, radius: Float = 5f * u): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun ripple(content: Drawable?, mask: Drawable? = content): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask ?: rounded(Color.WHITE))

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 6f * u)
        setPadding(px(5f), px(4f), px(5f), px(4f))
    }

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun icon(res: Int, sizeU: Float, tint: Int = TEXT): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(px(sizeU), px(sizeU))
    }

    /** Circular icon button. */
    fun circleButton(res: Int, sizeU: Float, bg: Int = CARD2, tint: Int = TEXT, onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            background = ripple(oval(bg), oval(Color.WHITE))
            val pad = px(sizeU * 0.24f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(px(sizeU), px(sizeU))
            isClickable = true
            setOnClickListener { Haptics.tick(ctx); onClick(it) }
        }

    /** Pill text button. */
    fun pill(label: CharSequence, bg: Int = CARD2, fg: Int = TEXT, onClick: (View) -> Unit): TextView =
        text(label, 5.6f, fg, 2).apply {
            gravity = Gravity.CENTER
            background = ripple(rounded(bg, 100f * u))
            setPadding(px(5f), px(3f), px(5f), px(3f))
            isClickable = true
            setOnClickListener { Haptics.tick(ctx); onClick(it) }
        }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           top: Float = 0f, bottom: Float = 0f, start: Float = 0f, end: Float = 0f, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = px(top); bottomMargin = px(bottom); marginStart = px(start); marginEnd = px(end)
        }

    fun frameLp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.MATCH_PARENT, gravity: Int = Gravity.NO_GRAVITY) =
        FrameLayout.LayoutParams(w, h, gravity)

    fun space(units: Float): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, px(units)) }
}
