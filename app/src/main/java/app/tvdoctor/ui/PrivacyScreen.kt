package app.tvdoctor.ui

import android.widget.LinearLayout
import app.tvdoctor.AppState
import app.tvdoctor.MainActivity
import app.tvdoctor.Screen
import app.tvdoctor.core.Commands
import app.tvdoctor.core.Fix
import app.tvdoctor.core.KnownPackages
import app.tvdoctor.core.Parsers
import app.tvdoctor.core.PrivacyApp
import app.tvdoctor.core.Repository

/** Who can use the camera / microphone, who used them recently, and apps with powerful special access. */
class PrivacyScreen(private val a: MainActivity) : Screen {
    private val page = Page(a)
    override val view get() = page.root

    override fun onShow() = render()

    private fun load() {
        a.work("Проверяю разрешения камеры и микрофона…", { a.repo.privacy() }) { p -> AppState.lastPrivacy = p; render() }
    }

    private fun isSystem(pkg: String) = a.snapshot?.packages?.get(pkg)?.isSystem ?: true

    fun render(): Unit = page.reset {
        val p = AppState.lastPrivacy
        card {
            h2("Слежка: камера, микрофон, спецдоступ")
            sub("Покажу приложения с доступом к камере и микрофону, когда они ими пользовались, а также службы, которые видят экран или уведомления.", top = 6)
            button(if (p == null) "Проверить" else "Проверить заново", Kind.PRIMARY, top = 10) { load() }
        }
        if (p == null) return@reset

        val active = p.apps.filter { app -> app.ops.any { it.running } }
        if (active.isNotEmpty()) card(stroke = Pal.RED) {
            text("Прямо сейчас используют камеру/микрофон", 15f, Pal.RED, bold = true)
            active.forEach { sub("• ${it.pkg}", top = 4) }
        }

        section(this, "Микрофон", p.apps.filter { it.microphone }, "RECORD_AUDIO",
            "У голосового поиска (пульт с микрофоном) это нормально. Подозрительно, если доступ есть у приложений, которым он не нужен.")
        section(this, "Камера", p.apps.filter { it.camera }, "CAMERA", "На телевизоре без камеры такие разрешения обычно не нужны.")

        val loc = p.apps.filter { it.location && !isSystem(it.pkg) }
        if (loc.isNotEmpty()) card {
            h2("Геолокация (сторонние приложения)")
            loc.forEach { app -> appRow(this, app.pkg, "есть доступ к местоположению") }
        }
        val overlay = p.apps.filter { it.overlay && !isSystem(it.pkg) }
        if (overlay.isNotEmpty()) card {
            h2("Поверх других окон")
            sub("Могут показывать рекламу поверх любого приложения.")
            overlay.forEach { app ->
                appRow(this, app.pkg, "рисует поверх окон") {
                    a.confirmAndApply(listOf(Fix("Запретить показ поверх окон: ${app.pkg}", listOf(Commands.setOp(app.pkg, "SYSTEM_ALERT_WINDOW", "ignore", "allow"))))) { load() }
                }
            }
        }
        special(this, p.special)
    }

    private fun section(parent: LinearLayout, title: String, apps: List<PrivacyApp>, op: String, hint: String) = parent.card {
        h2("$title: ${apps.size}")
        sub(hint)
        if (apps.isEmpty()) sub("Нет приложений с этим разрешением.", top = 8)
        apps.sortedWith(compareBy({ isSystem(it.pkg) }, { it.pkg })).forEach { app ->
            val use = app.ops.firstOrNull { it.op == op }
            val parts = ArrayList<String>()
            parts += if (isSystem(app.pkg)) "системное" else "стороннее"
            KnownPackages.get(app.pkg)?.let { parts += it.note }
            when {
                use?.running == true -> parts += "ИСПОЛЬЗУЕТ СЕЙЧАС"
                use?.lastAccess != null -> parts += "использовал(о) ${Parsers.humanAgo(use.lastAccess)}"
                else -> parts += "не использовалось"
            }
            if (use != null && use.mode != "allow" && use.mode != "default") parts += "режим: ${use.mode}"
            appRow(this, app.pkg, parts.joinToString(" · "), warn = !isSystem(app.pkg) || use?.running == true) {
                val perm = op
                a.confirmAndApply(listOf(Fix("Запретить ${title.lowercase()}: ${app.pkg}", listOf(
                    Commands.revokePermission(app.pkg, perm),
                    Commands.setOp(app.pkg, op, "ignore", use?.mode),
                ), note = "Если это голосовой поиск, он перестанет слышать пульт. Отменить можно в журнале."))) { load() }
            }
        }
    }

    private fun appRow(parent: LinearLayout, pkg: String, info: String, warn: Boolean = false, onBlock: (() -> Unit)? = null) {
        parent.divider(top = 10)
        parent.text(pkg, 14.5f, if (warn) Pal.ORANGE else Pal.TEXT, top = 8)
        parent.sub(info)
        parent.hbox(top = 4) {
            if (onBlock != null) button("Запретить", Kind.DANGER) { onBlock() }
            button("О приложении", Kind.GHOST) { a.openApp(pkg) }
        }
    }

    private fun special(parent: LinearLayout, sp: app.tvdoctor.core.SpecialAccess) = parent.card {
        h2("Специальный доступ")
        sub("Службы специальных возможностей видят всё на экране и могут нажимать кнопки. Слушатели уведомлений читают все уведомления.")
        fun list(title: String, items: List<String>, setting: String?) {
            text(title, 14f, bold = true, top = 12)
            if (items.isEmpty()) sub("нет") else items.forEach { comp ->
                val pkg = comp.substringBefore('/')
                text(comp, 13f, if (isSystem(pkg)) Pal.TEXT else Pal.ORANGE, top = 4)
                if (setting != null) hbox(top = 2) {
                    button("Отключить", Kind.DANGER) {
                        a.work(null, { a.repo.getSetting("secure", setting) }) { old ->
                            val rest = Parsers.colonComponents(old).filter { it != comp }.joinToString(":")
                            a.confirmAndApply(listOf(Fix("Отключить $title: $pkg", listOf(Commands.settingPut("secure", setting, rest, old))))) { load() }
                        }
                    }
                    button("О приложении", Kind.GHOST) { a.openApp(pkg) }
                }
            }
        }
        list("Спец. возможности", sp.accessibility, "enabled_accessibility_services")
        list("Доступ к уведомлениям", sp.notificationListeners, "enabled_notification_listeners")
        list("Администраторы устройства", sp.deviceAdmins, null)
        text("Клавиатура по умолчанию", 14f, bold = true, top = 12)
        sub(sp.defaultKeyboard ?: "—")
        if (sp.keyboards.size > 1) sub("Включены: " + sp.keyboards.joinToString(", "))
    }
}
