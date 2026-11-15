package app.tvdoctor.ui

import android.widget.Button
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.core.Finding
import app.tvdoctor.core.Scanner
import app.tvdoctor.core.Severity

class ScanScreen(private val a: MainActivity) : Screen {
    private val page = Page(a)
    override val view get() = page.root
    private var findings: List<Finding> = emptyList()
    private val selected = HashSet<String>()
    private var fixButton: Button? = null
    private var renderedFor: Long = -1

    override fun onShow() {
        val s = a.snapshot
        if (s != null && s.takenAt != renderedFor) render() else if (s == null) render()
    }

    fun render(): Unit = page.reset {
        val s = a.snapshot
        renderedFor = s?.takenAt ?: -1
        if (s == null) {
            card {
                h2("Проверка телевизора")
                sub("Соберу список приложений, нагрузку на процессор и память, место на диске, рекламные и следящие модули.", top = 6)
                button("Сканировать", Kind.PRIMARY, top = 12) { a.loadSnapshot { render() } }
            }
            return@reset
        }
        val d = s.device
        card {
            h2(d.title)
            sub("Android ${d.androidVersion} · ${if (d.isTv) "телевизор" else "устройство"} ${d.device}".trim())
            hbox(top = 10) {
                stat(this, "Память", if (d.memTotalKb > 0) "${pct(d.memTotalKb - d.memAvailableKb, d.memTotalKb)}%" else "—",
                    "занято из ${Scanner.mb(d.memTotalKb)}", if (d.memTotalKb > 0 && d.memAvailableKb * 100 / d.memTotalKb < 20) Pal.RED else Pal.GREEN)
                stat(this, "Диск", if (d.dataTotalKb > 0) "${pct(d.dataTotalKb - d.dataFreeKb, d.dataTotalKb)}%" else "—",
                    "свободно ${Scanner.mb(d.dataFreeKb)}", if (d.dataTotalKb > 0 && d.dataFreeKb * 100 / d.dataTotalKb < 12) Pal.RED else Pal.GREEN)
                stat(this, "Темп.", d.temperatureC?.let { "${it.toInt()}°" } ?: "—", "процессор",
                    if ((d.temperatureC ?: 0.0) >= 70) Pal.RED else Pal.GREEN)
            }
            val active = s.packages.values.count { it.installed && it.enabled }
            val third = s.packages.values.count { !it.isSystem && it.installed }
            val off = s.packages.values.count { !it.enabled || !it.installed }
            sub("Приложений: $active активных, $third установлено вами, $off отключено/удалено. Нагрузка ЦП: ${d.load1 ?: "—"} (ядер: ${d.cpuCores}).", top = 10)
            button("Сканировать заново", Kind.GHOST, top = 6) { a.loadSnapshot { render() } }
        }

        findings = Scanner.findings(s)
        selected.retainAll(findings.filter { it.fix != null }.map { it.id }.toSet())

        val top = topConsumers(s)
        if (top.isNotEmpty()) card {
            h2("Что нагружает систему")
            top.forEach { (p, line) ->
                hbox(top = 6) {
                    add(android.widget.TextView(context).apply { text = p; textSize = 13.5f; setTextColor(Pal.TEXT) }, w = 0, weight = 1f)
                    text(line, 13f, Pal.SUB)
                }.setOnClickListener { a.openApp(p) }
            }
        }

        h2(if (findings.isEmpty()) "Проблем не найдено 🎉" else "Найдено: ${findings.size}", top = 16)
        if (findings.isNotEmpty()) hbox(top = 6) {
            button("Выбрать безопасные", Kind.NORMAL, weight = 1f) {
                selected.clear(); selected.addAll(findings.filter { it.safe && it.fix != null }.map { it.id }); render()
            }
            button("Снять выбор", Kind.GHOST) { selected.clear(); render() }
        }

        Severity.values().forEach { sev ->
            val group = findings.filter { it.severity == sev }
            if (group.isEmpty()) return@forEach
            text(sev.label, 13f, severityColor(sev.rank), bold = true, top = 14)
            group.forEach { f -> findingCard(this, f) }
        }

        if (findings.any { it.fix != null }) {
            fixButton = button("", Kind.PRIMARY, top = 16) { applySelected() }
            updateFixButton()
        }
        sub("Каждое изменение записывается в журнал («Ещё»), откуда его можно отменить.", top = 8)
    }

    private fun findingCard(parent: android.widget.LinearLayout, f: Finding) = parent.card(top = 8) {
        hbox {
            chip(f.severity.label, severityColor(f.severity.rank))
            if (f.safe) chip("безопасно", Pal.GREEN)
        }
        text(f.title, 15f, bold = true, top = 6)
        sub(f.details, top = 4)
        hbox(top = 6) {
            f.fix?.let { fix ->
                check(fix.title, f.id in selected) { on -> if (on) selected.add(f.id) else selected.remove(f.id); updateFixButton() }
                    .layoutParams = android.widget.LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            f.pkg?.let { p -> button("Подробнее", Kind.GHOST) { a.openApp(p) } }
        }
    }

    private fun updateFixButton() {
        fixButton?.text = if (selected.isEmpty()) "Отметьте пункты для исправления" else "Исправить выбранное (${selected.size})"
        fixButton?.isEnabled = selected.isNotEmpty()
    }

    private fun applySelected() {
        val fixes = findings.filter { it.id in selected }.mapNotNull { it.fix }
        a.confirmAndApply(fixes) { selected.clear(); a.loadSnapshot { render() } }
    }

    private fun stat(row: android.widget.LinearLayout, label: String, value: String, hint: String, color: Int) {
        val box = vertical(row.context, 10).apply { background = rounded(context, Pal.SURFACE2, 12) }
        box.text(label, 12f, Pal.SUB)
        box.text(value, 20f, color, bold = true)
        box.text(hint, 11f, Pal.SUB)
        row.add(box, w = 0, weight = 1f, end = 6)
    }

    private fun pct(part: Long, total: Long) = if (total <= 0) 0 else (part * 100 / total).toInt()

    private fun topConsumers(s: app.tvdoctor.core.Snapshot): List<Pair<String, String>> {
        val pkgs = (s.memByPkg.keys + s.cpuByPkg.keys).filter { s.packages.containsKey(it) }
        return pkgs.sortedByDescending { (s.cpuByPkg[it] ?: 0.0) * 20_000 + (s.memByPkg[it] ?: 0L) }.take(6).map { p ->
            p to listOfNotNull(s.memByPkg[p]?.let { Scanner.mb(it) }, s.cpuByPkg[p]?.takeIf { it >= 1 }?.let { "ЦП ${it.toInt()}%" }).joinToString(" · ")
        }
    }
}
