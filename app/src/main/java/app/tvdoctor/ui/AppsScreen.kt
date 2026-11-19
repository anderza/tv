package app.tvdoctor.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.tvdoctor.IconCache
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.core.Category
import app.tvdoctor.core.KnownPackages
import app.tvdoctor.core.PackageState
import app.tvdoctor.core.Scanner

class AppsScreen(private val a: MainActivity) : Screen {

    private enum class Filter(val label: String) {
        ALL("Все"), USER("Мои"), SYSTEM("Системные"), RUNNING("В памяти"), SUSPECT("Реклама и лишнее"), OFF("Отключённые и удалённые")
    }
    // короткие подписи, чтобы кнопка сортировки была узкой
    private enum class Sort(val label: String) { MEM("память"), SIZE("размер"), NAME("имя") }

    private var filter = Filter.ALL
    private var sort = Sort.MEM
    private var query = ""
    private var items: List<PackageState> = emptyList()
    private val filterButtons = HashMap<Filter, Button>()
    private lateinit var sortButton: Button
    private lateinit var countText: TextView
    private val adapter = Adapter()
    private var renderedFor = -1L

    /** Делает кнопку компактной: ниже и уже обычной. */
    private fun compact(b: Button): Button = b.apply {
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
    }

    override val view: View = vertical(a).apply {
        val top = vertical(context, 12)
        top.button("📲  Установить APK с телефона на ТВ", Kind.PRIMARY, top = 0) { a.installApk() }
        top.input("Поиск по имени пакета", top = 6).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); rebuild() }
        })
        top.hscroll(top = 6) {
            Filter.values().forEach { f ->
                filterButtons[f] = this@AppsScreen.compact(button(f.label, Kind.NORMAL) { filter = f; rebuild() })
            }
        }
        top.hbox(top = 4) {
            countText = add(TextView(context).apply { textSize = 12f; setTextColor(Pal.SUB) }, w = 0, weight = 1f)
            sortButton = this@AppsScreen.compact(button("", Kind.GHOST) { sort = Sort.values()[(sort.ordinal + 1) % Sort.values().size]; rebuild() })
            this@AppsScreen.compact(button("Обновить", Kind.GHOST) { a.loadSnapshot { rebuild() } })
        }
        add(top)
        val list = ListView(context).apply {
            divider = null; dividerHeight = 0; setPadding(context.dp(12), 0, context.dp(12), context.dp(12)); clipToPadding = false
            this.adapter = this@AppsScreen.adapter
            setOnItemClickListener { _, _, pos, _ -> a.openApp(items[pos].pkg) }
        }
        add(list, h = 0, weight = 1f)
    }

    override fun onShow() {
        if (a.snapshot == null) a.loadSnapshot { rebuild() } else rebuild()
    }

    fun rebuild() {
        val s = a.snapshot ?: return
        renderedFor = s.takenAt
        val launcher = s.currentLauncher
        val q = query.lowercase()
        items = s.packages.values.filter { p ->
            (q.isEmpty() || p.pkg.lowercase().contains(q) || IconCache.label(p.pkg)?.lowercase()?.contains(q) == true) && when (filter) {
                Filter.ALL -> p.installed
                Filter.USER -> p.installed && !p.isSystem
                Filter.SYSTEM -> p.installed && p.isSystem
                Filter.RUNNING -> (s.memByPkg[p.pkg] ?: 0L) > 0L
                Filter.SUSPECT -> p.installed && p.enabled && p.pkg != launcher &&
                    (KnownPackages.get(p.pkg)?.category.let { it == Category.ADS || it == Category.TELEMETRY || it == Category.BLOAT } || KnownPackages.heuristic(p.pkg))
                Filter.OFF -> !p.enabled || !p.installed
            }
        }.let { list ->
            when (sort) {
                Sort.MEM -> list.sortedWith(compareByDescending<PackageState> { s.memByPkg[it.pkg] ?: 0L }.thenBy { it.pkg })
                Sort.SIZE -> list.sortedWith(compareByDescending<PackageState> { s.sizes[it.pkg]?.let { z -> z.codeBytes + z.dataBytes + z.cacheBytes } ?: 0L }.thenBy { it.pkg })
                Sort.NAME -> list.sortedBy { (IconCache.label(it.pkg) ?: it.pkg).lowercase() }
            }
        }
        filterButtons.forEach { (f, b) ->
            b.background = rounded(a, if (f == filter) Pal.ACCENT_DARK else Pal.SURFACE2, 10, if (f == filter) Pal.ACCENT else null, 1)
            b.setTextColor(if (f == filter) Pal.ACCENT else Pal.TEXT)
            compact(b)
        }
        sortButton.text = "↕ ${sort.label}"
        countText.text = "Показано: ${items.size}"
        adapter.notifyDataSetChanged()
        IconCache.load(a, s) { adapter.notifyDataSetChanged() }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
                add(android.widget.ImageView(context).apply { tag = "icon"; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
                    w = context.dp(40), h = context.dp(40), end = 12)
                val texts = vertical(context)
                texts.hbox {
                    add(TextView(context).apply { textSize = 14.5f; setTextColor(Pal.TEXT); tag = "name" }, w = 0, weight = 1f)
                    add(TextView(context).apply { textSize = 11.5f; tag = "tag"; setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2)) })
                }
                texts.add(TextView(context).apply { textSize = 12.5f; setTextColor(Pal.SUB); tag = "info" }, top = 3)
                add(texts, w = 0, weight = 1f)
            }
            val wrapper = row
            val p = items[position]
            val s = a.snapshot
            val iconView = wrapper.findViewWithTag<android.widget.ImageView>("icon")
            val icon = IconCache.icon(p.pkg)
            iconView.visibility = if (p.isSystem) View.GONE else View.VISIBLE
            if (icon != null) { iconView.setImageBitmap(icon); iconView.background = null }
            else { iconView.setImageDrawable(null); iconView.background = rounded(a, Pal.SURFACE2, 10) }
            val appLabel = IconCache.label(p.pkg)
            (wrapper.findViewWithTag<TextView>("name")).text = appLabel ?: p.pkg
            val info = ArrayList<String>()
            if (appLabel != null) info += p.pkg
            info += if (p.isSystem) "системное" else "установлено вами"
            if (!p.installed) info += "удалено для пользователя"
            else if (!p.enabled) info += "отключено"
            s?.memByPkg?.get(p.pkg)?.let { info += "ОЗУ ${Scanner.mb(it)}" }
            s?.cpuByPkg?.get(p.pkg)?.takeIf { it >= 1 }?.let { info += "ЦП ${it.toInt()}%" }
            s?.sizes?.get(p.pkg)?.let { z -> info += "размер ${Scanner.bytes(z.codeBytes + z.dataBytes)}"; if (z.cacheBytes > 1 shl 20) info += "кэш ${Scanner.bytes(z.cacheBytes)}" }
            wrapper.findViewWithTag<TextView>("info").text = info.joinToString(" · ")
            val tagView = wrapper.findViewWithTag<TextView>("tag")
            val k = KnownPackages.get(p.pkg)
            val (label, color) = when {
                p.pkg == s?.currentLauncher -> "лаунчер" to Pal.ACCENT
                KnownPackages.isProtected(p.pkg) -> "системное ядро" to Pal.SUB
                k != null -> k.category.label.lowercase() to when (k.category) { Category.ADS -> Pal.RED; Category.TELEMETRY -> Pal.ORANGE; else -> Pal.YELLOW }
                KnownPackages.heuristic(p.pkg) -> "подозрительно" to Pal.ORANGE
                else -> "" to Pal.SUB
            }
            tagView.text = label
            tagView.setTextColor(color)
            tagView.background = if (label.isEmpty()) null else rounded(a, android.graphics.Color.argb(40, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)), 8)
            return wrapperWithGap(wrapper)
        }
    }

    /** ListView rows can't have margins; draw the gap with a transparent top border instead. */
    private fun wrapperWithGap(v: LinearLayout): View {
        v.background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(a, Pal.SURFACE, 12))).apply { setLayerInset(0, 0, a.dp(6), 0, 0) }
        v.setPadding(a.dp(12), a.dp(16), a.dp(12), a.dp(10))
        return v
    }
}
