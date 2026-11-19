package app.tvdoctor.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen

/** Виртуальный пульт: каждая кнопка sends `input keyevent` на телевизор. */
class RemoteScreen(private val a: MainActivity) : Screen {

    private lateinit var status: TextView
    private lateinit var field: TextView

    private fun key(code: Int) = send("input keyevent $code")

    private fun send(cmd: String) {
        if (!a.adb.isConnected) { a.toast("Сначала подключитесь к телевизору"); return }
        a.work(null, { a.adb.run(cmd) }) { r ->
            if (r.looksFailed) a.toast("ТВ не принял команду: " + (r.all.lines().firstOrNull { it.isNotBlank() } ?: "ошибка"))
        }
    }

    private fun LinearLayout.pad(label: String, code: Int, h: Int = 54, kind: Kind = Kind.NORMAL) {
        add(makeButton(context, label, kind) { key(code) }, w = 0, h = context.dp(h), weight = 1f, start = 3, end = 3)
    }

    private fun LinearLayout.gap() {
        add(View(context), w = 0, weight = 1f, start = 3, end = 3)
    }

    override val view: View = android.widget.ScrollView(a).apply {
        isFillViewport = true
        val col = vertical(context, 14)
        col.text("Пульт ДУ", 17f, bold = true)
        status = col.text("", 12.5f, Pal.SUB, top = 4)

        col.hbox(top = 12) {
            pad("⏻", 26, kind = Kind.DANGER)
            pad("🏠 Домой", 3)
            pad("🔍 Поиск", 84)
        }

        col.hbox(top = 8) { gap(); pad("▲", 19, h = 58); gap() }
        col.hbox(top = 6) { pad("◀", 21, h = 58); pad("OK", 23, h = 58, kind = Kind.PRIMARY); pad("▶", 22, h = 58) }
        col.hbox(top = 6) { gap(); pad("▼", 20, h = 58); gap() }

        col.hbox(top = 10) {
            pad("↩ Назад", 4)
            pad("☰ Меню", 82)
            pad("🎤 Голос", 231)
        }
        col.hbox(top = 6) {
            pad("🔉 Тише", 25)
            pad("🔇", 164)
            pad("🔊 Громче", 24)
        }
        col.hbox(top = 6) {
            pad("⏮", 88)
            pad("⏯", 85)
            pad("⏭", 87)
        }
        col.hbox(top = 6) {
            pad("⏪", 89)
            pad("⏹", 86)
            pad("⏩", 90)
        }

        col.text("Ввод текста", 15f, bold = true, top = 16)
        col.sub("Наберите здесь — текст появится в поле на телевизоре (например, в поиске YouTube).", top = 2)
        field = col.input("Текст для телевизора", top = 8)
        col.button("Отправить текст на ТВ", Kind.PRIMARY, top = 8) {
            val t = field.text?.toString().orEmpty()
            if (t.isEmpty()) { a.toast("Сначала введите текст"); return@button }
            val safe = t.replace("\\", "\\\\").replace("'", "'\\''").replace(" ", "%s")
            send("input text '$safe'")
        }
        col.hbox(top = 6) {
            pad("⌫ Стереть символ", 67)
            pad("⏎ Enter", 66)
        }
        addView(col)
    }

    override fun onShow() {
        if (::status.isInitialized) {
            val on = a.adb.isConnected
            status.text = if (on) "Подключено: ${a.adb.host}:${a.adb.port}" else "Нет подключения к телевизору"
            status.setTextColor(if (on) Pal.GREEN else Pal.ORANGE)
        }
    }
}
