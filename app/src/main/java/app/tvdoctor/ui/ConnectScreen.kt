package app.tvdoctor.ui

import android.text.InputType
import android.widget.LinearLayout
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.adb.NetScanner
import dadb.AdbAuthException
import dadb.AdbConnectException

class ConnectScreen(private val a: MainActivity) : Screen {
    private val page = Page(a)
    override val view get() = page.root
    private lateinit var found: LinearLayout

    override fun onShow(): Unit = page.reset {
        h1("TV Doctor")
        sub("Оптимизация Android TV и ТВ-приставок по Wi‑Fi: лишние приложения, реклама, тормоза, слежка.")

        card {
            h2("1. Подготовьте телевизор")
            sub(
                "• Настройки → Об устройстве (Система → Об устройстве) → 7 раз нажмите «Сборка», чтобы включить режим разработчика.\n" +
                    "• Настройки → Для разработчиков → включите «Отладка по USB» (и «Отладка по сети», если такой пункт есть).\n" +
                    "• Узнайте IP телевизора: Настройки → Сеть → ваша Wi‑Fi сеть.\n" +
                    "• Телефон и телевизор должны быть в одной сети.",
                top = 6,
            )
        }

        card {
            h2("2. Подключение")
            val ip = input("IP телевизора, например 192.168.1.50", a.prefs.getString("host", "") ?: "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, top = 10)
            val port = input("Порт", a.prefs.getInt("port", 5555).toString(), InputType.TYPE_CLASS_NUMBER)
            button("Подключиться", Kind.PRIMARY, top = 10) { connect(ip.text.toString(), port.text.toString()) }
            button("Найти телевизор в сети") { scan() }
            found = vbox(top = 4)
            val recent = (a.prefs.getString("recent", "") ?: "").split(',').filter { it.isNotBlank() }
            if (recent.isNotEmpty()) {
                sub("Недавние:", top = 10)
                hscroll(top = 4) {
                    recent.forEach { r -> button(r) { connect(r.substringBefore(':'), r.substringAfter(':', "5555")) } }
                }
            }
            sub("Если приложение запущено на самом телевизоре, укажите 127.0.0.1 (нужна включённая «Отладка по сети»).", top = 10)
        }

        card {
            h2("3. Подтвердите на телевизоре")
            sub("На экране ТВ появится «Разрешить отладку?». Отметьте «Всегда разрешать с этого компьютера» и нажмите «ОК».", top = 6)
        }

        card {
            h2("Если не подключается")
            sub(
                "• На новых Google TV вместо «Отладки по сети» бывает только «Беспроводная отладка» с кодом сопряжения. " +
                    "Один раз подключитесь с компьютера: adb pair IP:порт, затем adb connect IP:порт и adb tcpip 5555 — после этого приложение подключится на порт 5555 (до перезагрузки ТВ).\n" +
                    "• Проверьте, что VPN на телефоне выключен.\n" +
                    "• Перезагрузите телевизор и повторите.",
                top = 6,
            )
        }
    }

    private fun connect(hostRaw: String, portRaw: String) {
        val host = hostRaw.trim()
        val port = portRaw.trim().toIntOrNull() ?: 5555
        if (host.isEmpty()) { a.toast("Введите IP телевизора"); return }
        a.work("Подключаюсь к $host:$port…\n\nЕсли на телевизоре появилось окно «Разрешить отладку?» — отметьте «Всегда разрешать» и нажмите ОК.", {
            a.adb.connect(host, port)
        }) { a.onConnected() }
    }

    private fun scan() {
        found.removeAllViews()
        found.sub("Ищу устройства с открытым портом 5555…", top = 6)
        a.work("Сканирую локальную сеть…", { NetScanner.scan() }) { list ->
            found.removeAllViews()
            if (list.isEmpty()) found.sub("Ничего не найдено. Проверьте, что на ТВ включена отладка по сети, или введите IP вручную.", top = 6)
            else {
                found.sub("Найдено:", top = 6)
                list.forEach { ip -> found.button("📺  $ip") { connect(ip, "5555") } }
            }
        }
    }

    companion object {
        fun explain(e: Throwable): String = when (e) {
            is AdbAuthException -> "Телевизор отклонил подключение. Подтвердите запрос «Разрешить отладку?» на экране ТВ и попробуйте снова."
            is AdbConnectException -> "Не удалось подключиться. Проверьте IP, что телефон и ТВ в одной сети и что на ТВ включена отладка по сети (порт 5555)."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
