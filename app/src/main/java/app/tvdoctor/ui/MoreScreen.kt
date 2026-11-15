package app.tvdoctor.ui

import android.app.AlertDialog
import android.widget.EditText
import android.widget.TextView
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.core.Commands
import app.tvdoctor.core.Fix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MoreScreen(private val a: MainActivity) : Screen {
    private val page = Page(a)
    override val view get() = page.root
    private var consoleOut: TextView? = null
    private var consoleIn: EditText? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onShow() = render()

    fun render(): Unit = page.reset {
        val entries = a.journal.all()
        card {
            h2("Журнал изменений")
            sub("Каждое действие можно отменить. Записи хранятся на телефоне.", top = 4)
            val pending = entries.filter { !it.undone && it.undo.isNotEmpty() }
            if (pending.isNotEmpty()) button("Отменить всё (${pending.size})", Kind.DANGER, top = 8) {
                AlertDialog.Builder(a).setTitle("Отменить все изменения?")
                    .setMessage("Будут выполнены обратные команды для ${pending.size} записей, начиная с последней.")
                    .setPositiveButton("Отменить всё") { _, _ -> undoAll(pending) }.setNegativeButton("Нет", null).show()
            }
            if (entries.isEmpty()) sub("Пока ничего не менялось.", top = 8)
            entries.take(60).forEach { e ->
                divider(top = 8)
                text(e.title, 14f, if (e.undone) Pal.SUB else Pal.TEXT, top = 6)
                sub("${fmt.format(Date(e.time))} · ${e.device}${if (e.undone) " · отменено" else ""}")
                if (!e.undone && e.undo.isNotEmpty()) button("Отменить", Kind.GHOST, top = 2) { a.undo(e) { render() } }
            }
            if (entries.isNotEmpty()) button("Очистить журнал", Kind.GHOST, top = 8) {
                AlertDialog.Builder(a).setMessage("Удалить записи журнала? Отменить изменения через него будет уже нельзя.")
                    .setPositiveButton("Удалить") { _, _ -> a.journal.clear(); render() }.setNegativeButton("Нет", null).show()
            }
        }

        card {
            h2("Инструменты")
            button("Установить APK с телефона на ТВ", Kind.PRIMARY, top = 8) { a.installApk() }
            button("Очистить кэш всех приложений", Kind.NORMAL, top = 8) { a.confirmAndApply(listOf(Fix("Очистить кэш всех приложений", listOf(Commands.trimCaches())))) }
            button("Вернуть все отключённые и удалённые приложения", Kind.NORMAL) { restoreAll() }
            button("Перезагрузить телевизор", Kind.DANGER) {
                a.confirmAndApply(listOf(Fix("Перезагрузить телевизор", listOf(Commands.reboot()), note = "После перезагрузки подключитесь заново.")))
            }
        }

        card {
            h2("Консоль ADB")
            sub("Любая команда adb shell. Для опытных пользователей — выполняется без журнала.", top = 4)
            consoleIn = input("Например: dumpsys battery", consoleIn?.text?.toString() ?: "", top = 8)
            button("Выполнить", Kind.PRIMARY) { runConsole() }
            consoleOut = text(consoleOut?.text ?: "", 11.5f, Pal.SUB, mono = true, top = 8).apply { setTextIsSelectable(true) }
        }

        card {
            h2("Экспертный режим")
            sub("Разрешает отключать и удалять важные системные компоненты. Включайте, только если понимаете последствия.", top = 4)
            check("Экспертный режим", a.expert) { on -> a.prefs.edit().putBoolean("expert", on).apply() }
        }

        card {
            h2("О приложении")
            sub("TV Doctor работает через ADB по сети. Ничего не отправляет в интернет: всё, что видно в приложении, читается прямо с телевизора.\n" +
                "Отключение (disable-user) и удаление для пользователя (uninstall --user 0) не трогают прошивку — после сброса ТВ всё вернётся.", top = 4)
            button("Отключиться от телевизора", Kind.GHOST, top = 8) { a.disconnect() }
        }
    }

    private fun undoAll(list: List<app.tvdoctor.Journal.Entry>) {
        if (list.isEmpty()) { render(); return }
        a.undo(list.first()) { undoAll(list.drop(1)) }
    }

    private fun restoreAll() {
        val s = a.snapshot ?: return a.toast("Сначала выполните скан")
        val off = s.packages.values.filter { !it.installed || !it.enabled }
        if (off.isEmpty()) return a.toast("Отключённых и удалённых приложений нет")
        val fixes = off.map { p -> if (!p.installed) Fix("Восстановить ${p.pkg}", listOf(Commands.restoreForUser(p.pkg))) else Fix("Включить ${p.pkg}", listOf(Commands.enable(p.pkg))) }
        a.confirmAndApply(fixes)
    }

    private fun runConsole() {
        val cmd = consoleIn?.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        a.work("Выполняю…", { a.adb.run(cmd) }) { r ->
            consoleOut?.text = "$ $cmd\n" + r.all.take(20_000) + if (r.exitCode != 0) "\n[код выхода ${r.exitCode}]" else ""
        }
    }
}
