package app.tvdoctor.ui

import android.app.AlertDialog
import android.widget.ScrollView
import app.tvdoctor.MainActivity
import app.tvdoctor.core.Category
import app.tvdoctor.core.Commands
import app.tvdoctor.core.Fix
import app.tvdoctor.core.KnownPackages
import app.tvdoctor.core.Scanner

/** Details and actions for one package. */
class AppSheet(private val a: MainActivity, private val pkg: String) {

    fun show() {
        val s = a.snapshot
        val st = s?.packages?.get(pkg)
        val launcher = s?.currentLauncher
        val known = KnownPackages.get(pkg)
        val locked = KnownPackages.isProtected(pkg, launcher) && !a.expert
        var dialog: AlertDialog? = null
        fun run(fix: Fix) { dialog?.dismiss(); a.confirmAndApply(listOf(fix)) { a.screen(a.currentId())?.onShow() } }

        val col = vertical(a, 18)
        val label = app.tvdoctor.IconCache.label(pkg)
        col.hbox {
            app.tvdoctor.IconCache.icon(pkg)?.let { bmp ->
                add(android.widget.ImageView(context).apply { setImageBitmap(bmp) }, w = context.dp(48), h = context.dp(48), end = 12)
            }
            val names = vertical(context)
            names.text(label ?: pkg, 17f, bold = true)
            if (label != null) names.sub(pkg)
            add(names, w = 0, weight = 1f)
        }
        val facts = ArrayList<String>()
        if (st == null) facts += "Нет в списке пакетов (обновите скан)"
        else {
            facts += if (st.isSystem) "Системное приложение" else "Установлено пользователем"
            facts += when { !st.installed -> "Удалено для пользователя (можно восстановить)"; !st.enabled -> "Отключено"; else -> "Включено" }
        }
        s?.memByPkg?.get(pkg)?.let { facts += "В памяти: ${Scanner.mb(it)}" }
        s?.cpuByPkg?.get(pkg)?.let { facts += "Процессор: ${"%.1f".format(it)}%" }
        s?.sizes?.get(pkg)?.let { facts += "APK ${Scanner.bytes(it.codeBytes)} · данные ${Scanner.bytes(it.dataBytes)} · кэш ${Scanner.bytes(it.cacheBytes)}" }
        if (st?.apkPath?.isNotEmpty() == true) facts += st.apkPath
        col.sub(facts.joinToString("\n"), top = 6)
        known?.let { col.text("${it.category.label}: ${it.note}", 14f, if (it.safe) Pal.GREEN else Pal.ORANGE, top = 10) }
        if (known == null && KnownPackages.heuristic(pkg)) col.text("Имя похоже на рекламный/следящий модуль — проверьте перед отключением.", 14f, Pal.ORANGE, top = 10)
        if (pkg == launcher) col.text("Это текущий лаунчер (главный экран). Сначала выберите другой в «Настройки → Лаунчер».", 14f, Pal.ACCENT, top = 10)
        if (locked) col.text("Важный системный компонент — изменения заблокированы, чтобы не сломать ТВ. Разблокировать: «Ещё → Экспертный режим».", 14f, Pal.SUB, top = 10)

        col.button("Запустить", Kind.NORMAL, top = 14) { run(Fix("Запустить $pkg", listOf(Commands.launch(pkg)))) }
        col.button("Остановить (force-stop)") { run(Fix("Остановить $pkg", listOf(Commands.forceStop(pkg)))) }
        if (!locked) {
            col.button("Запретить работу в фоне") { run(Fix("Ограничить фон: $pkg", listOf(Commands.forceStop(pkg), Commands.restrictBackground(pkg)))) }
            if (st == null || st.installed) {
                if (st?.enabled != false) col.button("Отключить", Kind.DANGER) { run(Fix("Отключить $pkg", listOf(Commands.disable(pkg)), dangerous = known?.safe != true)) }
                else col.button("Включить обратно", Kind.PRIMARY) { run(Fix("Включить $pkg", listOf(Commands.enable(pkg)))) }
                col.button("Очистить данные", Kind.DANGER) {
                    run(Fix("Очистить данные $pkg", listOf(Commands.clearData(pkg)), dangerous = true, note = "Удалятся настройки и вход в аккаунт этого приложения. Отменить нельзя."))
                }
                col.button("Удалить для пользователя", Kind.DANGER) {
                    run(Fix("Удалить для пользователя: $pkg", listOf(Commands.uninstallForUser(pkg)), dangerous = true,
                        note = if (st?.isSystem == true) "Системное приложение остаётся в прошивке, его можно вернуть кнопкой «Восстановить» или из журнала."
                        else "Приложение, установленное вами, вернуть можно только пока его APK есть в системе; надёжнее — переустановить из Google Play."))
                }
            } else {
                col.button("Восстановить", Kind.PRIMARY) { run(Fix("Восстановить $pkg", listOf(Commands.restoreForUser(pkg)))) }
            }
        }

        // ——— НОВОЕ 1: убрать рекламу и слежку на всём ТВ ———
        val adPkgs = s?.packages?.values?.filter { p ->
            p.installed && p.enabled && p.pkg != launcher && !KnownPackages.isProtected(p.pkg, launcher) &&
                (KnownPackages.get(p.pkg)?.category.let { it == Category.ADS || it == Category.TELEMETRY } || KnownPackages.heuristic(p.pkg))
        }?.map { it.pkg }?.distinct().orEmpty()

        if (adPkgs.isNotEmpty()) {
            col.button("🚫  Убрать рекламу на ТВ (${adPkgs.size})", Kind.PRIMARY, top = 14) {
                val shown = adPkgs.take(6).joinToString(", ")
                val tail = if (adPkgs.size > 6) " и ещё ${adPkgs.size - 6}" else ""
                run(Fix("Убрать рекламу и слежку: ${adPkgs.size} пакетов",
                    adPkgs.flatMap { listOf(Commands.forceStop(it), Commands.disable(it)) },
                    dangerous = true,
                    note = "Будут отключены рекламные и следящие модули: $shown$tail. Прошивка не меняется, всё возвращается из журнала или кнопкой «Вернуть все отключённые приложения»."))
            }
        } else {
            col.sub("Известных рекламных модулей на ТВ не найдено.", top = 14)
        }

        // ——— НОВОЕ 2: покупки Google ———
        val vending = "com.android.vending"
        val vState = s?.packages?.get(vending)
        val billingOn = vState == null || (vState.installed && vState.enabled)
        if (billingOn) {
            col.button("💳  Отключить покупки Google", Kind.DANGER, top = 10) {
                run(Fix("Отключить покупки Google (Play Маркет)",
                    listOf(Commands.forceStop(vending), Commands.disable(vending)),
                    dangerous = true,
                    note = "Все покупки внутри приложений идут через Google Play Маркет. Пока он отключён, случайно купить что-то не получится — в том числе детям в играх. Минус: приложения перестанут обновляться и сам Play Маркет не откроется. Вернуть можно кнопкой ниже или из журнала. Если кнопка не сработает — включите «Ещё → Экспертный режим»."))
            }
        } else {
            col.button("💳  Вернуть покупки Google", Kind.PRIMARY, top = 10) {
                val cmds = if (vState?.installed == false) listOf(Commands.restoreForUser(vending), Commands.enable(vending)) else listOf(Commands.enable(vending))
                run(Fix("Вернуть покупки Google (Play Маркет)", cmds))
            }
        }

        dialog = AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(col) }).setNegativeButton("Закрыть", null).show()
    }
}
