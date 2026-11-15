package app.tvdoctor.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny view DSL: the app uses plain framework views (no AndroidX) so it builds without Google Maven. */
object Pal {
    val BG = Color.parseColor("#0F1419")
    val SURFACE = Color.parseColor("#1A2129")
    val SURFACE2 = Color.parseColor("#26303B")
    val LINE = Color.parseColor("#33404D")
    val TEXT = Color.parseColor("#E8EEF2")
    val SUB = Color.parseColor("#9AA7B2")
    val ACCENT = Color.parseColor("#4FC3F7")
    val ACCENT_DARK = Color.parseColor("#0B2733")
    val RED = Color.parseColor("#EF5350")
    val ORANGE = Color.parseColor("#FFA726")
    val GREEN = Color.parseColor("#66BB6A")
    val YELLOW = Color.parseColor("#FFD54F")
}

enum class Kind { PRIMARY, NORMAL, DANGER, GHOST }

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(ctx: Context, color: Int, radiusDp: Int = 12, strokeColor: Int? = null, strokeDp: Int = 2): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(ctx.dp(strokeDp), strokeColor)
    }

private fun states(ctx: Context, normal: Int, pressed: Int, radius: Int = 10): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, pressed, radius))
    addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, pressed, radius, Pal.TEXT))
    addState(intArrayOf(-android.R.attr.state_enabled), rounded(ctx, Pal.SURFACE2, radius))
    addState(intArrayOf(), rounded(ctx, normal, radius))
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun <T : View> LinearLayout.add(v: T, w: Int = if (orientation == LinearLayout.VERTICAL) MATCH else WRAP, h: Int = WRAP,
                               weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): T {
    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    addView(v, lp)
    return v
}

fun vertical(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    if (padDp > 0) setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
}

inline fun LinearLayout.vbox(padDp: Int = 0, top: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    add(vertical(context, padDp), top = top).apply(block)

inline fun LinearLayout.hbox(top: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    add(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }, top = top).apply(block)

/** Horizontally scrollable row of buttons/chips. */
inline fun LinearLayout.hscroll(top: Int = 0, block: LinearLayout.() -> Unit): LinearLayout {
    val sv = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    sv.addView(row)
    add(sv, top = top)
    row.block()
    return row
}

fun LinearLayout.text(s: CharSequence, size: Float = 15f, color: Int = Pal.TEXT, bold: Boolean = false, top: Int = 0, mono: Boolean = false): TextView =
    add(TextView(context).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.12f)
    }, top = top)

fun LinearLayout.h1(s: String) = text(s, 22f, bold = true)
fun LinearLayout.h2(s: String, top: Int = 0) = text(s, 17f, bold = true, top = top)
fun LinearLayout.sub(s: CharSequence, top: Int = 2) = text(s, 13.5f, Pal.SUB, top = top)

fun makeButton(ctx: Context, s: String, kind: Kind = Kind.NORMAL, onClick: () -> Unit): Button = Button(ctx).apply {
    text = s; isAllCaps = false; textSize = 14.5f; minHeight = ctx.dp(44); minimumHeight = ctx.dp(44)
    setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
    stateListAnimator = null
    val (bg, pressed, fg) = when (kind) {
        Kind.PRIMARY -> Triple(Pal.ACCENT, Color.parseColor("#81D4FA"), Pal.ACCENT_DARK)
        Kind.NORMAL -> Triple(Pal.SURFACE2, Pal.LINE, Pal.TEXT)
        Kind.DANGER -> Triple(Color.parseColor("#4A1F22"), Color.parseColor("#6B2A2E"), Color.parseColor("#FF8A80"))
        Kind.GHOST -> Triple(Color.TRANSPARENT, Pal.SURFACE2, Pal.ACCENT)
    }
    background = states(ctx, bg, pressed)
    setTextColor(fg)
    isFocusable = true
    setOnClickListener { onClick() }
}

fun LinearLayout.button(s: String, kind: Kind = Kind.NORMAL, top: Int = 6, weight: Float = 0f, onClick: () -> Unit): Button {
    val horizontal = orientation == LinearLayout.HORIZONTAL
    return add(makeButton(context, s, kind, onClick), w = if (horizontal) (if (weight > 0) 0 else WRAP) else MATCH,
        weight = weight, top = if (horizontal) 0 else top, end = if (horizontal) 8 else 0)
}

inline fun LinearLayout.card(top: Int = 10, stroke: Int? = null, block: LinearLayout.() -> Unit): LinearLayout {
    val c = vertical(context, 14)
    c.background = rounded(context, Pal.SURFACE, 14, stroke, 1)
    add(c, top = top)
    c.block()
    return c
}

fun LinearLayout.chip(s: String, color: Int, end: Int = 6): TextView = add(TextView(context).apply {
    text = s; textSize = 11.5f; setTextColor(color); setTypeface(typeface, Typeface.BOLD)
    background = rounded(context, Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 8)
    setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
}, end = end)

fun LinearLayout.input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, top: Int = 6): EditText =
    add(EditText(context).apply {
        this.hint = hint; setText(value); inputType = type; setSingleLine(true); textSize = 15f
        setTextColor(Pal.TEXT); setHintTextColor(Pal.SUB)
        background = rounded(context, Pal.SURFACE2, 10)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    }, top = top)

fun LinearLayout.check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
    add(CheckBox(context).apply {
        text = label; isChecked = checked; setTextColor(Pal.TEXT); textSize = 14f
        buttonTintList = android.content.res.ColorStateList.valueOf(Pal.ACCENT)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    })

fun LinearLayout.divider(top: Int = 10) = add(View(context).apply { setBackgroundColor(Pal.LINE) }, h = context.dp(1), top = top)

/** A page: ScrollView with a padded vertical column. */
class Page(ctx: Context) {
    val col: LinearLayout = vertical(ctx, 14)
    val root: ScrollView = ScrollView(ctx).apply { isFillViewport = true; addView(col) }
    fun reset(block: LinearLayout.() -> Unit) { col.removeAllViews(); col.block() }
}

fun severityColor(rank: Int) = when (rank) { 0 -> Pal.RED; 1 -> Pal.ORANGE; 2 -> Pal.ACCENT; else -> Pal.SUB }
