package app.tvdoctor.ui

import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.core.Cmd
import app.tvdoctor.core.Commands
import app.tvdoctor.core.Fix
import app.tvdoctor.core.KnownPackages

class SettingsScreen(private val a: MainActivity) : Screen {
    private val page = Page(a)
    override val view get() = page.root
    private var ns = "global"
    private val nsButtons = HashMap<String, Button>()
    private lateinit var keyIn: EditText
    private lateinit var valueIn: EditText
    private lateinit var listBox: LinearLayout

    override fun onShow() { if (a.snapshot == null) a.loadSnapshot { render() } else render() }

    fun render(): Unit = page.reset {
        val s = a.snapshot ?: return@reset

        card {
            h2("Скорость анимаций")
            val (w, t, an) = s.animationScales
            sub("Сейчас: окна ${w.ifEmpty { "1" }}, переходы ${t.ifEmpty { "1" }}, аниматор ${an.ifEmpty { "1" }}. Меньше — интерфейс отзывчивее.", top = 4)
            hbox(top = 8) {
                listOf("0" to "Выкл", "0.5" to "0.5x", "1.0" to "1x (стандарт)").forEach { (v, l) ->
                    button(l, if (v == "0.5") Kind.PRIMARY else Kind.NORMAL, weight = 1f) {
                        a.confirmAndApply(listOf(Fix("Анимации: $l", Commands.animations(v, s.animationScales)))) { render() }
                    }
                }
            }
        }

        card {
            h2("Блокировка рекламы (Private DNS)")
            val on = s.privateDnsMode == "hostname"
            sub(if (on) "Включено: ${s.privateDnsHost}" else "Сейчас: ${s.privateDnsMode.ifEmpty { "по умолчанию" }} — реклама не фильтруется.", top = 4)
            sub("DNS-фильтр блокирует рекламные и следящие домены для всех приложений сразу. Если пропадёт интернет — провайдер блокирует DNS-over-TLS, выключите.", top = 4)
            KnownPackages.adBlockDns.forEach { (host, label) ->
                button(label, if (on && s.privateDnsHost == host) Kind.PRIMARY else Kind.NORMAL) { setDns(host) }
            }
            val custom = input("Свой сервер, например xxxx.dns.nextdns.io")
            hbox(top = 6) {
                button("Применить свой", Kind.NORMAL, weight = 1f) {
                    val h = custom.text.toString().trim()
                    if (Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(h)) setDns(h) else a.toast("Введите имя хоста DNS-over-TLS")
                }
                button("Выключить", Kind.DANGER, weight = 1f) { setDns(null) }
            }
        }

        card {
            h2("Лаунчер (главный экран)")
            if (s.homeActivities.isEmpty()) sub("Не удалось получить список лаунчеров.", top = 4)
            val current = s.homeActivities.firstOrNull { it.isCurrent }
            s.homeActivities.forEach { h ->
                divider(top = 8)
                text(h.pkg, 14.5f, if (h.isCurrent) Pal.ACCENT else Pal.TEXT, bold = h.isCurrent, top = 6)
                sub(listOfNotNull(if (h.isCurrent) "текущий" else null, KnownPackages.get(h.pkg)?.note).joinToString(" · ").ifEmpty { h.component })
                if (!h.isCurrent) hbox(top = 4) {
                    button("Сделать основным", Kind.PRIMARY) {
                        a.confirmAndApply(listOf(Fix("Лаунчер по умолчанию: ${h.pkg}", listOf(Commands.setHome(h.component, current?.component), Commands.pressHome())))) { a.refreshPackages { render() } }
                    }
                }
            }
            val stock = current?.pkg?.takeIf { KnownPackages.get(it)?.category == app.tvdoctor.core.Category.LAUNCHER }
            val alternatives = s.homeActivities.filter { !it.isCurrent && KnownPackages.get(it.pkg) == null && it.pkg != "com.android.tv.settings" }
            if (stock != null) {
                sub("Стандартный лаунчер Google TV/Android TV показывает рекламу. Установите другой (FLauncher, Projectivy Launcher и т.п. из Google Play) и сделайте его основным.", top = 10)
                if (alternatives.isNotEmpty()) button("Отключить стандартный лаунчер ($stock)", Kind.DANGER, top = 8) {
                    a.confirmAndApply(listOf(Fix("Отключить стандартный лаунчер $stock", listOf(Commands.disable(stock), Commands.pressHome()), dangerous = true,
                        note = "На некоторых прошивках кнопка «Домой» или настройки могут перестать открываться. Включить обратно: журнал или «Приложения → Отключённые»."))) { a.refreshPackages { render() } }
                }
            }
            button("Нажать «Домой» на ТВ", Kind.GHOST, top = 8) { a.work(null, { a.adb.run(Commands.pressHome().run) }) {} }
        }

        card {
            h2("Редактор системных настроек")
            sub("Чтение и изменение любых ключей settings (global / secure / system). Старое значение сохраняется в журнал.", top = 4)
            hbox(top = 8) {
                listOf("global", "secure", "system").forEach { n -> nsButtons[n] = button(n, Kind.NORMAL, weight = 1f) { ns = n; paintNs() } }
            }
            paintNs()
            keyIn = input("Ключ, например screen_off_timeout")
            valueIn = input("Значение")
            hbox(top = 6) {
                button("Прочитать", Kind.NORMAL, weight = 1f) { read() }
                button("Записать", Kind.PRIMARY, weight = 1f) { write() }
                button("Удалить", Kind.DANGER, weight = 1f) { delete() }
            }
            button("Показать все ключи (фильтр по полю «Ключ»)", Kind.GHOST) { listAll() }
            listBox = vbox(top = 4)
        }
    }

    private fun setDns(host: String?) {
        val s = a.snapshot ?: return
        a.confirmAndApply(listOf(Fix(if (host == null) "Выключить Private DNS" else "Private DNS: $host", Commands.privateDns(host, s.privateDnsMode, s.privateDnsHost)))) { a.refreshPackages { render() } }
    }

    private fun paintNs() = nsButtons.forEach { (n, b) ->
        b.background = rounded(a, if (n == ns) Pal.ACCENT_DARK else Pal.SURFACE2, 10, if (n == ns) Pal.ACCENT else null, 1)
        b.setTextColor(if (n == ns) Pal.ACCENT else Pal.TEXT)
    }

    private fun key(): String? = keyIn.text.toString().trim().takeIf { Regex("^[A-Za-z0-9_.:\\-]+$").matches(it) } ?: run { a.toast("Введите корректный ключ"); null }

    private fun read() {
        val k = key() ?: return
        a.work(null, { a.repo.getSetting(ns, k) }) { v -> valueIn.setText(v); a.toast(if (v.isEmpty()) "$ns/$k не задан" else "$ns/$k = $v") }
    }

    private fun write() {
        val k = key() ?: return
        val v = valueIn.text.toString()
        val n = ns
        a.work(null, { a.repo.getSetting(n, k) }) { old ->
            a.confirmAndApply(listOf(Fix("settings $n $k = $v", listOf(Commands.settingPut(n, k, v, old)), dangerous = true,
                note = "Было: ${old.ifEmpty { "не задано" }}")))
        }
    }

    private fun delete() {
        val k = key() ?: return
        val n = ns
        a.work(null, { a.repo.getSetting(n, k) }) { old ->
            a.confirmAndApply(listOf(Fix("Удалить settings $n $k", listOf<Cmd>(Commands.settingDelete(n, k, old)), dangerous = true, note = "Было: ${old.ifEmpty { "не задано" }}")))
        }
    }

    private fun listAll() {
        val n = ns
        val filter = keyIn.text.toString().trim().lowercase()
        a.work("Читаю настройки $n…", { a.repo.settingsList(n) }) { all ->
            listBox.removeAllViews()
            val shown = all.filter { filter.isEmpty() || it.first.lowercase().contains(filter) }
            listBox.sub("Найдено: ${shown.size}${if (shown.size > 300) " (показаны первые 300)" else ""}", top = 6)
            shown.take(300).forEach { (k, v) ->
                listBox.text("$k = $v", 12.5f, Pal.TEXT, mono = true, top = 4).setOnClickListener { keyIn.setText(k); valueIn.setText(v) }
            }
        }
    }
}
