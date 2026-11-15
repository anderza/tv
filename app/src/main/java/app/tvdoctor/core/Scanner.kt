package app.tvdoctor.core

import java.util.Locale

/** Turns a [Snapshot] into a list of problems with ready fixes. */
object Scanner {

    fun mb(kb: Long) = String.format(Locale.US, "%.0f МБ", kb / 1024.0)
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f ГБ", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f МБ", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.0f КБ", b / 1024.0)
    }

    fun findings(s: Snapshot): List<Finding> {
        val out = ArrayList<Finding>()
        val launcher = s.currentLauncher
        val active = s.packages.values.filter { it.installed && it.enabled }

        // 1. System health
        val d = s.device
        if (d.memTotalKb > 0) {
            val freePct = d.memAvailableKb * 100 / d.memTotalKb
            if (freePct < 20) {
                val victims = s.memByPkg.entries
                    .filter { (p, kb) -> kb > 30_000 && s.packages[p]?.isSystem == false && p != launcher && !KnownPackages.isProtected(p, launcher) }
                    .sortedByDescending { it.value }.take(10)
                out += Finding(
                    "ram_low", "Мало свободной памяти: $freePct%",
                    "Свободно ${mb(d.memAvailableKb)} из ${mb(d.memTotalKb)}. Телевизор начинает тормозить и перезапускать приложения." +
                        if (victims.isNotEmpty()) " Можно закрыть фоновые приложения: ${victims.joinToString { it.key }}." else "",
                    Severity.HIGH,
                    victims.takeIf { it.isNotEmpty() }?.let { v -> Fix("Закрыть фоновые приложения", v.map { Commands.forceStop(it.key) }) },
                    safe = false,
                )
            }
        }
        if (d.dataTotalKb > 0) {
            val freePct = d.dataFreeKb * 100 / d.dataTotalKb
            if (freePct < 12) out += Finding(
                "storage_low", "Почти нет места: свободно $freePct%",
                "Свободно ${mb(d.dataFreeKb)} из ${mb(d.dataTotalKb)}. При нехватке места система сильно замедляется и не ставит обновления.",
                Severity.HIGH, Fix("Очистить кэш всех приложений", listOf(Commands.trimCaches())), safe = true,
            )
        }
        val totalCache = s.sizes.values.sumOf { it.cacheBytes }
        if (totalCache > 300L shl 20) out += Finding(
            "cache_big", "Кэш приложений занимает ${bytes(totalCache)}",
            "Кэш безопасно удаляется, приложения создадут его заново. Больше всего: " +
                s.sizes.entries.sortedByDescending { it.value.cacheBytes }.take(4).joinToString { "${it.key} (${bytes(it.value.cacheBytes)})" },
            Severity.MEDIUM, Fix("Очистить кэш всех приложений", listOf(Commands.trimCaches())), safe = true,
        )
        d.temperatureC?.let { t ->
            if (t >= 70) out += Finding("hot", "Высокая температура: ${t.toInt()} °C",
                "Процессор сбрасывает частоту при перегреве, и всё тормозит. Проверьте вентиляцию приставки и закройте тяжёлые приложения.",
                Severity.HIGH, null, safe = false)
        }
        if (d.load1 != null && d.cpuCores > 0 && d.load1 > d.cpuCores * 1.5) out += Finding(
            "load", "Процессор перегружен (нагрузка ${d.load1})",
            "Средняя нагрузка выше числа ядер (${d.cpuCores}). Смотрите самые «прожорливые» приложения ниже.",
            Severity.MEDIUM, null, safe = false,
        )

        // 2. Animations
        val (w, t, a) = s.animationScales
        fun scale(v: String) = v.toDoubleOrNull() ?: 1.0
        if (listOf(w, t, a).any { scale(it) > 0.5 }) out += Finding(
            "anim", "Анимации интерфейса на 1x",
            "Если уменьшить анимации до 0.5x, меню и переходы будут ощущаться заметно быстрее. Это обратимо.",
            Severity.LOW, Fix("Анимации 0.5x", Commands.animations("0.5", s.animationScales)), safe = true,
        )

        // 3. Ads via DNS
        if (s.privateDnsMode != "hostname") out += Finding(
            "dns", "Не включена блокировка рекламы через DNS",
            "Private DNS с фильтром (AdGuard) убирает рекламу и трекеры во многих приложениях и на главном экране. " +
                "Если после включения пропадёт интернет — ваш провайдер блокирует порт 853, отключите в разделе «Настройки».",
            Severity.MEDIUM, Fix("Включить AdGuard DNS", Commands.privateDns("dns.adguard-dns.com", s.privateDnsMode, s.privateDnsHost)), safe = false,
        )

        // 4. Heavy apps
        s.cpuByPkg.entries.filter { it.value >= 15 && s.packages.containsKey(it.key) }.sortedByDescending { it.value }.forEach { (p, pct) ->
            if (KnownPackages.isProtected(p, launcher)) return@forEach
            out += Finding("cpu_$p", "Грузит процессор: $p (${pct.toInt()}%)",
                "Приложение активно расходует процессор. Если вы им сейчас не пользуетесь, его можно остановить и запретить работу в фоне.",
                Severity.HIGH, Fix("Остановить и ограничить фон: $p", listOf(Commands.forceStop(p), Commands.restrictBackground(p))), safe = false, pkg = p)
        }
        s.memByPkg.entries.filter { it.value >= 180_000 && s.packages.containsKey(it.key) }.sortedByDescending { it.value }.forEach { (p, kb) ->
            if (KnownPackages.isProtected(p, launcher) || out.any { it.id == "cpu_$p" }) return@forEach
            out += Finding("mem_$p", "Занимает много памяти: $p (${mb(kb)})",
                "Если приложение не нужно постоянно, остановите его и ограничьте работу в фоне.",
                Severity.MEDIUM, Fix("Остановить и ограничить фон: $p", listOf(Commands.forceStop(p), Commands.restrictBackground(p))), safe = false, pkg = p)
        }

        // 5. Ads, telemetry, bloat
        active.forEach { st ->
            val p = st.pkg
            if (p == launcher || KnownPackages.isProtected(p, launcher)) return@forEach
            val k = KnownPackages.get(p)
            val memNote = s.memByPkg[p]?.let { " Сейчас в памяти: ${mb(it)}." }.orEmpty()
            if (k != null) {
                if (k.category == Category.LAUNCHER) return@forEach
                val sev = when (k.category) {
                    Category.ADS -> Severity.HIGH
                    Category.TELEMETRY -> Severity.MEDIUM
                    Category.BLOAT -> Severity.LOW
                    else -> Severity.INFO
                }
                out += Finding("pkg_$p", "${k.category.label}: $p", k.note + "." + memNote + if (!k.safe) " Отключайте, только если точно не пользуетесь этой функцией." else "",
                    sev, Fix("Отключить $p", listOf(Commands.disable(p))), safe = k.safe && k.category in setOf(Category.ADS, Category.TELEMETRY, Category.BLOAT), pkg = p)
            } else if (KnownPackages.heuristic(p)) {
                out += Finding("heur_$p", "Похоже на рекламу/телеметрию: $p",
                    "Название пакета похоже на рекламный или следящий модуль. Проверьте перед отключением.$memNote",
                    Severity.LOW, Fix("Отключить $p", listOf(Commands.disable(p))), safe = false, pkg = p)
            }
        }

        // 6. Launcher with ads
        launcher?.let { l ->
            val k = KnownPackages.get(l)
            if (k?.category == Category.LAUNCHER) out += Finding("launcher", "Стандартный лаунчер: $l",
                "${k.note}. Поставьте другой лаунчер (например FLauncher или Projectivy Launcher из Google Play) и выберите его в «Настройки → Лаунчер».",
                Severity.INFO, null, safe = false, pkg = l)
        }

        return out.sortedWith(compareBy({ it.severity.rank }, { it.title }))
    }
}
