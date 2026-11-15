package app.tvdoctor.core

/** Reads the TV's state through a [Shell]. Batches commands with `echo @@name` markers to save round trips. */
class Repository(private val shell: Shell) {

    private fun batch(parts: List<Pair<String, String>>): Map<String, String> {
        val script = parts.joinToString("; ") { (name, cmd) -> "echo @@$name; $cmd 2>&1" }
        return Parsers.splitMarked(shell.run(script).output)
    }

    fun snapshot(progress: (String) -> Unit = {}): Snapshot {
        progress("Читаю систему и список приложений…")
        val b = batch(
            listOf(
                "props" to "getprop",
                "meminfo" to "cat /proc/meminfo",
                "df" to "df -k /data",
                "thermal" to "cat /sys/class/thermal/thermal_zone*/temp",
                "cpus" to "ls /sys/devices/system/cpu",
                "uptime" to "uptime",
                "pkgall" to "pm list packages -f -u",
                "pkginst" to "pm list packages",
                "pkgsys" to "pm list packages -s",
                "pkgdis" to "pm list packages -d",
                "anim" to "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale",
                "dnsmode" to "settings get global private_dns_mode",
                "dnshost" to "settings get global private_dns_specifier",
                "homeq" to "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                "homer" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            )
        )
        progress("Измеряю память и нагрузку процессора…")
        val heavy = batch(listOf("cpu" to "dumpsys cpuinfo", "mem" to "dumpsys meminfo"))
        progress("Считаю размеры приложений и кэша…")
        val disk = batch(listOf("disk" to "dumpsys diskstats"))

        val props = props(b["props"].orEmpty())
        val (memTotal, memAvail) = Parsers.procMeminfo(b["meminfo"].orEmpty())
        val (dataTotal, dataFree) = Parsers.df(b["df"].orEmpty())
        val temps = b["thermal"].orEmpty().lines().mapNotNull { it.trim().toDoubleOrNull() }
            .map { if (it > 200) it / 1000.0 else it }.filter { it in 1.0..150.0 }
        val cores = b["cpus"].orEmpty().lines().count { Regex("^cpu\\d+$").matches(it.trim()) }
        val (cpuProcs, load) = Parsers.cpuinfo(heavy["cpu"].orEmpty())
        val memProcs = Parsers.dumpsysMeminfo(heavy["mem"].orEmpty())

        val device = DeviceInfo(
            manufacturer = props["ro.product.manufacturer"].orEmpty(),
            model = props["ro.product.model"].orEmpty(),
            device = props["ro.product.device"].orEmpty(),
            androidVersion = props["ro.build.version.release"].orEmpty(),
            sdk = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
            isTv = props["ro.build.characteristics"].orEmpty().contains("tv"),
            memTotalKb = memTotal, memAvailableKb = memAvail,
            dataTotalKb = dataTotal, dataFreeKb = dataFree,
            temperatureC = temps.maxOrNull(), load1 = load, cpuCores = cores,
            uptime = b["uptime"].orEmpty().trim(),
        )

        val packages = packages(b)
        val anim = b["anim"].orEmpty().lines().map { Parsers.clean(it) }
        return Snapshot(
            device = device,
            packages = packages,
            memByPkg = Parsers.memByPackage(memProcs),
            cpuByPkg = Parsers.cpuByPackage(cpuProcs),
            sizes = Parsers.diskstats(disk["disk"].orEmpty()),
            homeActivities = Parsers.homeActivities(b["homeq"].orEmpty(), b["homer"].orEmpty()),
            animationScales = Triple(anim.getOrElse(0) { "" }, anim.getOrElse(1) { "" }, anim.getOrElse(2) { "" }),
            privateDnsMode = Parsers.clean(b["dnsmode"]),
            privateDnsHost = Parsers.clean(b["dnshost"]),
        )
    }

    /** Fast refresh of package states only (after enable/disable/uninstall). */
    fun refreshPackages(old: Snapshot): Snapshot {
        val b = batch(
            listOf(
                "pkgall" to "pm list packages -f -u", "pkginst" to "pm list packages",
                "pkgsys" to "pm list packages -s", "pkgdis" to "pm list packages -d",
                "homeq" to "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                "homer" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                "anim" to "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale",
                "dnsmode" to "settings get global private_dns_mode", "dnshost" to "settings get global private_dns_specifier",
            )
        )
        val anim = b["anim"].orEmpty().lines().map { Parsers.clean(it) }
        return old.copy(
            packages = packages(b),
            homeActivities = Parsers.homeActivities(b["homeq"].orEmpty(), b["homer"].orEmpty()),
            animationScales = Triple(anim.getOrElse(0) { "" }, anim.getOrElse(1) { "" }, anim.getOrElse(2) { "" }),
            privateDnsMode = Parsers.clean(b["dnsmode"]),
            privateDnsHost = Parsers.clean(b["dnshost"]),
        )
    }

    private fun packages(b: Map<String, String>): Map<String, PackageState> {
        val all = Parsers.packageList(b["pkgall"].orEmpty())
        val inst = Parsers.packageList(b["pkginst"].orEmpty()).keys
        val sys = Parsers.packageList(b["pkgsys"].orEmpty()).keys
        val dis = Parsers.packageList(b["pkgdis"].orEmpty()).keys
        val packages = LinkedHashMap<String, PackageState>()
        (all.keys + inst).sorted().forEach { p ->
            packages[p] = PackageState(p, all[p].orEmpty(), p in sys, p !in dis, p in inst || all.isEmpty())
        }
        return packages
    }

    private val PROP = Regex("^\\[([^\\]]+)\\]: \\[(.*)\\]$")
    private fun props(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { PROP.find(it.trim()) }.associate { it.groupValues[1] to it.groupValues[2] }

    data class Privacy(val apps: List<PrivacyApp>, val special: SpecialAccess)

    fun privacy(): Privacy {
        val b = batch(
            listOf(
                "perms" to "dumpsys package packages | grep -E '^  Package \\[|android\\.permission\\.(CAMERA|RECORD_AUDIO|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION): granted=true'",
                "overlay" to "cmd appops query-op --user 0 SYSTEM_ALERT_WINDOW allow",
                "a11y" to "settings get secure enabled_accessibility_services",
                "notif" to "settings get secure enabled_notification_listeners",
                "ime" to "settings get secure default_input_method",
                "imes" to "settings get secure enabled_input_methods",
                "admins" to "dumpsys device_policy | grep -E 'ComponentInfo|admin='",
            )
        )
        val granted = Parsers.grantedPermissions(b["perms"].orEmpty())
        val overlay = Parsers.packageList(b["overlay"].orEmpty().lines().joinToString("\n") { l ->
            val t = l.trim(); if (Parsers.isPackageName(t)) "package:$t" else t
        }).keys

        val watched = granted.filter { "CAMERA" in it.value || "RECORD_AUDIO" in it.value }.keys.toList()
        val ops = HashMap<String, List<OpUse>>()
        watched.chunked(25).forEach { chunk ->
            val r = batch(chunk.map { p -> p to "appops get $p CAMERA; appops get $p RECORD_AUDIO" })
            r.forEach { (p, text) -> ops[p] = listOfNotNull(Parsers.appOp(text, "CAMERA"), Parsers.appOp(text, "RECORD_AUDIO")) }
        }
        val apps = (granted.keys + overlay).distinct().map { p ->
            val g = granted[p].orEmpty()
            PrivacyApp(
                pkg = p,
                camera = "CAMERA" in g,
                microphone = "RECORD_AUDIO" in g,
                location = "ACCESS_FINE_LOCATION" in g || "ACCESS_COARSE_LOCATION" in g,
                overlay = p in overlay,
                ops = ops[p].orEmpty(),
            )
        }.filter { it.camera || it.microphone || it.location || it.overlay }

        val special = SpecialAccess(
            accessibility = Parsers.colonComponents(b["a11y"]),
            notificationListeners = Parsers.colonComponents(b["notif"]),
            deviceAdmins = Parsers.deviceAdmins(b["admins"].orEmpty()),
            keyboards = Parsers.colonComponents(b["imes"]),
            defaultKeyboard = Parsers.clean(b["ime"]).ifEmpty { null },
        )
        return Privacy(apps, special)
    }

    fun settingsList(ns: String): List<Pair<String, String>> {
        require(ns in setOf("global", "secure", "system"))
        return Parsers.settingsList(shell.run("settings list $ns").output)
    }

    fun getSetting(ns: String, key: String): String = Parsers.clean(shell.run(Commands.settingGet(ns, key)).output)
}
