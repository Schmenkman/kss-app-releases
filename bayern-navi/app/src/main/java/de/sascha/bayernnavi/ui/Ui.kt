package de.sascha.bayernnavi.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
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
import kotlin.math.roundToInt

/** Kleine Helfer fuer die (bewusst XML-freie) Oberflaeche. */
object Ui {
    val BLUE = Color.parseColor("#0066B3")
    val BLUE_DARK = Color.parseColor("#004C86")
    val GREEN_DARK = Color.parseColor("#1B5E20")
    val NAV_GREEN = Color.parseColor("#0F6E3A")
    val RED = Color.parseColor("#D32F2F")
    val ORANGE = Color.parseColor("#EF6C00")
    val GREY = Color.parseColor("#5F6368")
    val LIGHT = Color.parseColor("#F1F3F4")
    val CARD = Color.WHITE
    val TEXT = Color.parseColor("#202124")
    val TEXT2 = Color.parseColor("#5F6368")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun bg(ctx: Context, color: Int, radiusDp: Int = 14, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(ctx, radiusDp.toFloat())
            if (strokeColor != null) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun clickable(ctx: Context, v: View, color: Int, radiusDp: Int = 14, strokeColor: Int? = null, strokeDp: Int = 0) {
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 0, 0, 0)), bg(ctx, color, radiusDp, strokeColor, strokeDp), null)
        v.background = ripple
        v.isClickable = true
        v.isFocusable = true
    }

    fun text(ctx: Context, s: CharSequence = "", sp: Float = 16f, color: Int = TEXT, bold: Boolean = false, maxLines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (maxLines > 0) {
                this.maxLines = maxLines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    fun button(ctx: Context, label: String, bgColor: Int = BLUE, fg: Int = Color.WHITE, onClick: (View) -> Unit): TextView =
        text(ctx, label, 16f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            clickable(ctx, this, bgColor, 24)
            setOnClickListener(onClick)
        }

    fun iconButton(ctx: Context, icon: Bitmap, bgColor: Int = Color.WHITE, sizeDp: Int = 50, desc: String? = null, onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageBitmap(icon)
            contentDescription = desc
            val p = dp(ctx, 12)
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clickable(ctx, this, bgColor, sizeDp / 2)
            elevation = dpf(ctx, 4f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)).apply { topMargin = dp(ctx, 10) }
            setOnClickListener(onClick)
        }

    fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun frameLp(w: Int, h: Int, gravity: Int): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

    fun linLp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    fun LinearLayout.LayoutParams.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
        return this
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#E0E0E0"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
    }

    fun minutes(sec: Double): String {
        val m = (sec / 60).roundToInt()
        return if (m < 60) "$m Min." else "${m / 60} Std. ${m % 60} Min."
    }
}
