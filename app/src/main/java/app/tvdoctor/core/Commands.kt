package app.tvdoctor.core

/** Builds shell commands with their undo command. Every argument is validated or quoted. */
object Commands {

    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun pkg(p: String): String { require(Parsers.isPackageName(p)) { "Некорректное имя пакета: $p" }; return p }
    private val KEY_RE = Regex("^[A-Za-z0-9_.:\\-]+$")
    private fun key(k: String): String { require(KEY_RE.matches(k)) { "Некорректный ключ: $k" }; return k }
    private val NS = setOf("global", "secure", "system")
    private fun ns(n: String): String { require(n in NS) { "Неизвестное пространство: $n" }; return n }
    private val OP_RE = Regex("^[A-Z_]+$")
    private val COMP_RE = Regex("^[A-Za-z][\\w.]*/[\\w.$]+$")

    fun disable(p: String) = Cmd("pm disable-user --user 0 ${pkg(p)}", "pm enable --user 0 $p")
    fun enable(p: String) = Cmd("pm enable --user 0 ${pkg(p)}", "pm disable-user --user 0 $p")
    fun uninstallForUser(p: String) = Cmd("pm uninstall -k --user 0 ${pkg(p)}", "cmd package install-existing --user 0 $p")
    fun restoreForUser(p: String) = Cmd("cmd package install-existing --user 0 ${pkg(p)}", "pm uninstall -k --user 0 $p")
    fun forceStop(p: String) = Cmd("am force-stop ${pkg(p)}")
    fun clearData(p: String) = Cmd("pm clear --user 0 ${pkg(p)}")
    fun restrictBackground(p: String) =
        Cmd("cmd appops set ${pkg(p)} RUN_ANY_IN_BACKGROUND ignore", "cmd appops set $p RUN_ANY_IN_BACKGROUND allow")
    fun launch(p: String) = Cmd("monkey -p ${pkg(p)} -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $p -c android.intent.category.LAUNCHER 1")
    fun trimCaches() = Cmd("pm trim-caches 999G")

    fun settingGet(n: String, k: String) = "settings get ${ns(n)} ${key(k)}"
    fun settingPut(n: String, k: String, value: String, old: String?): Cmd {
        val o = Parsers.clean(old)
        return Cmd(
            "settings put ${ns(n)} ${key(k)} ${q(value)}",
            if (o.isEmpty()) "settings delete $n $k" else "settings put $n $k ${q(o)}",
        )
    }
    fun settingDelete(n: String, k: String, old: String?): Cmd {
        val o = Parsers.clean(old)
        return Cmd("settings delete ${ns(n)} ${key(k)}", if (o.isEmpty()) null else "settings put $n $k ${q(o)}")
    }

    fun animations(scale: String, old: Triple<String, String, String>): List<Cmd> = listOf(
        settingPut("global", "window_animation_scale", scale, old.first),
        settingPut("global", "transition_animation_scale", scale, old.second),
        settingPut("global", "animator_duration_scale", scale, old.third),
    )

    fun privateDns(host: String?, oldMode: String, oldHost: String): List<Cmd> =
        if (host == null) listOf(settingPut("global", "private_dns_mode", "off", oldMode))
        else listOf(
            settingPut("global", "private_dns_specifier", host, oldHost),
            settingPut("global", "private_dns_mode", "hostname", oldMode),
        )

    fun revokePermission(p: String, perm: String): Cmd {
        require(OP_RE.matches(perm))
        return Cmd("pm revoke ${pkg(p)} android.permission.$perm", "pm grant $p android.permission.$perm")
    }
    fun setOp(p: String, op: String, mode: String, oldMode: String?): Cmd {
        require(OP_RE.matches(op) && mode in MODES)
        val back = oldMode?.takeIf { it in MODES } ?: "default"
        return Cmd("appops set ${pkg(p)} $op $mode", "appops set $p $op $back")
    }
    private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    fun setHome(component: String, oldComponent: String?): Cmd {
        require(COMP_RE.matches(component))
        return Cmd(
            "cmd package set-home-activity --user 0 $component",
            oldComponent?.takeIf { COMP_RE.matches(it) }?.let { "cmd package set-home-activity --user 0 $it" },
        )
    }
    fun pressHome() = Cmd("input keyevent KEYCODE_HOME")
    fun reboot() = Cmd("reboot")
}
