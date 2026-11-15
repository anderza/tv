package app.tvdoctor.core

/** Result of one shell command run on the TV over ADB. */
data class ShellResult(val output: String, val error: String, val exitCode: Int) {
    val all: String get() = (output + error).trim()

    /** `pm`, `settings`, `cmd` often exit 0 even on failure, so also look at the text. */
    val looksFailed: Boolean
        get() {
            if (exitCode != 0) return true
            val t = all
            return FAIL_MARKERS.any { t.contains(it, ignoreCase = false) }
        }

    companion object {
        private val FAIL_MARKERS = listOf(
            "Exception", "Error:", "Failure", "failed", "not allowed", "Permission denied",
            "Unknown command", "Bad component", "Unknown package", "inaccessible or not found"
        )
    }
}

/** Something that can run shell commands on the TV. Implemented by the ADB client; faked in tests. */
fun interface Shell {
    fun run(command: String): ShellResult
}

data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val androidVersion: String = "",
    val sdk: Int = 0,
    val isTv: Boolean = false,
    val memTotalKb: Long = 0,
    val memAvailableKb: Long = 0,
    val dataTotalKb: Long = 0,
    val dataFreeKb: Long = 0,
    val temperatureC: Double? = null,
    val load1: Double? = null,
    val cpuCores: Int = 0,
    val uptime: String = "",
) {
    val title: String get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android-устройство" }
}

data class PackageState(
    val pkg: String,
    val apkPath: String = "",
    val isSystem: Boolean = false,
    /** false = disabled (pm disable-user / disabled by vendor). */
    val enabled: Boolean = true,
    /** false = uninstalled for user 0 but still on the system partition (restorable). */
    val installed: Boolean = true,
)

data class ProcessMem(val pid: Int, val name: String, val kb: Long)
data class ProcessCpu(val pid: Int, val name: String, val percent: Double)

data class PackageSizes(val codeBytes: Long, val dataBytes: Long, val cacheBytes: Long)

data class HomeActivity(val component: String, val isCurrent: Boolean) {
    val pkg: String get() = component.substringBefore('/')
}

/** Mode of an app-op (e.g. CAMERA) plus how long ago it was last used, as Android prints it. */
data class OpUse(val op: String, val mode: String, val lastAccess: String?, val running: Boolean)

data class PrivacyApp(
    val pkg: String,
    val camera: Boolean,
    val microphone: Boolean,
    val location: Boolean,
    val overlay: Boolean,
    val ops: List<OpUse> = emptyList(),
)

data class SpecialAccess(
    val accessibility: List<String> = emptyList(),
    val notificationListeners: List<String> = emptyList(),
    val deviceAdmins: List<String> = emptyList(),
    val keyboards: List<String> = emptyList(),
    val defaultKeyboard: String? = null,
)

/** One full look at the TV, taken by [Repository.snapshot]. */
data class Snapshot(
    val device: DeviceInfo,
    val packages: Map<String, PackageState>,
    val memByPkg: Map<String, Long>,
    val cpuByPkg: Map<String, Double>,
    val sizes: Map<String, PackageSizes>,
    val homeActivities: List<HomeActivity>,
    val animationScales: Triple<String, String, String>,
    val privateDnsMode: String,
    val privateDnsHost: String,
    val takenAt: Long = System.currentTimeMillis(),
) {
    val currentLauncher: String? get() = homeActivities.firstOrNull { it.isCurrent }?.pkg
}

/** A command with the command that reverses it (null = cannot be undone). */
data class Cmd(val run: String, val undo: String? = null)

/** A user-visible fix: one or more commands that are run together and journaled together. */
data class Fix(
    val title: String,
    val cmds: List<Cmd>,
    /** Shown in red, needs an extra confirmation. */
    val dangerous: Boolean = false,
    val note: String? = null,
)

enum class Severity(val label: String, val rank: Int) { HIGH("Важно", 0), MEDIUM("Рекомендуется", 1), LOW("По желанию", 2), INFO("Инфо", 3) }

data class Finding(
    val id: String,
    val title: String,
    val details: String,
    val severity: Severity,
    val fix: Fix?,
    /** Preselected by "select safe fixes". */
    val safe: Boolean,
    val pkg: String? = null,
)
