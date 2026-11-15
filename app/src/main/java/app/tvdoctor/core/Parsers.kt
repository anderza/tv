package app.tvdoctor.core

/** Pure text parsers for the output of Android shell commands. No Android APIs, unit-tested on the JVM. */
object Parsers {

    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    fun isPackageName(s: String) = PKG_RE.matches(s)

    /** `pm list packages [-f]`: lines `package:com.foo` or `package:/path/base.apk=com.foo`. Returns pkg -> apk path ("" if not given). */
    fun packageList(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.forEach { line ->
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            val (path, pkg) = if (eq >= 0 && body.startsWith("/")) body.substring(0, eq) to body.substring(eq + 1) else "" to body
            val p = pkg.trim().substringBefore(' ')
            if (isPackageName(p)) out[p] = path
        }
        return out
    }

    /** /proc/meminfo -> (MemTotal kB, MemAvailable kB). */
    fun procMeminfo(text: String): Pair<Long, Long> {
        var total = 0L; var avail = -1L; var free = 0L; var cached = 0L; var buffers = 0L
        text.lineSequence().forEach { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val v = parts[1].toLongOrNull() ?: return@forEach
            when (parts[0]) {
                "MemTotal:" -> total = v
                "MemAvailable:" -> avail = v
                "MemFree:" -> free = v
                "Cached:" -> cached = v
                "Buffers:" -> buffers = v
            }
        }
        if (avail < 0) avail = free + cached + buffers
        return total to avail
    }

    /** `df -k /data` -> (total kB, available kB). Handles wrapped lines from long device names. */
    fun df(text: String): Pair<Long, Long> {
        val tokens = text.lineSequence().drop(1).joinToString(" ").trim().split(Regex("\\s+"))
        val nums = tokens.mapNotNull { it.toLongOrNull() }
        if (nums.size >= 3) return nums[0] to nums[2]
        return 0L to 0L
    }

    private val MEM_LINE = Regex("^\\s*([\\d,]+)K: (\\S+) \\(pid (\\d+)")

    /** `dumpsys meminfo`: the "Total PSS by process" (or RSS on newer Android) section. */
    fun dumpsysMeminfo(text: String): List<ProcessMem> {
        val lines = text.lines()
        fun section(title: String): List<ProcessMem>? {
            val start = lines.indexOfFirst { it.trim().startsWith(title) }
            if (start < 0) return null
            val res = ArrayList<ProcessMem>()
            for (i in start + 1 until lines.size) {
                val l = lines[i]
                if (l.isBlank()) { if (res.isNotEmpty()) break else continue }
                val m = MEM_LINE.find(l) ?: if (res.isNotEmpty()) break else continue
                res += ProcessMem(m.groupValues[3].toInt(), m.groupValues[2], m.groupValues[1].replace(",", "").toLong())
            }
            return res
        }
        return section("Total PSS by process") ?: section("Total RSS by process") ?: emptyList()
    }

    /** Sum process memory per package (process "com.foo:remote" belongs to "com.foo"). */
    fun memByPackage(procs: List<ProcessMem>): Map<String, Long> {
        val m = HashMap<String, Long>()
        procs.forEach { p -> val pkg = p.name.substringBefore(':'); m[pkg] = (m[pkg] ?: 0L) + p.kb }
        return m
    }

    private val CPU_LINE = Regex("^\\s*([\\d.]+)%\\s+(\\d+)/(.+?):\\s")
    private val LOAD_LINE = Regex("Load:\\s*([\\d.]+)")

    /** `dumpsys cpuinfo` -> per-process CPU % and the 1-minute load average. */
    fun cpuinfo(text: String): Pair<List<ProcessCpu>, Double?> {
        val procs = text.lineSequence().mapNotNull { CPU_LINE.find(it) }.map {
            ProcessCpu(it.groupValues[2].toInt(), it.groupValues[3], it.groupValues[1].toDoubleOrNull() ?: 0.0)
        }.toList()
        val load = LOAD_LINE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return procs to load
    }

    fun cpuByPackage(procs: List<ProcessCpu>): Map<String, Double> {
        val m = HashMap<String, Double>()
        procs.forEach { p -> val pkg = p.name.substringBefore(':'); m[pkg] = (m[pkg] ?: 0.0) + p.percent }
        return m
    }

    private fun bracketList(text: String, key: String): List<String>? {
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("$key:") } ?: return null
        val inside = line.substringAfter('[', "").substringBeforeLast(']', "")
        if (inside.isBlank()) return emptyList()
        return inside.split(',').map { it.trim().trim('"') }
    }

    /** `dumpsys diskstats`: parallel arrays of package names and sizes in bytes. */
    fun diskstats(text: String): Map<String, PackageSizes> {
        val names = bracketList(text, "Package Names") ?: return emptyMap()
        val code = bracketList(text, "App Sizes").orEmpty()
        val data = bracketList(text, "App Data Sizes").orEmpty()
        val cache = bracketList(text, "Cache Sizes").orEmpty()
        val out = HashMap<String, PackageSizes>()
        names.forEachIndexed { i, n ->
            out[n] = PackageSizes(
                code.getOrNull(i)?.toLongOrNull() ?: 0,
                data.getOrNull(i)?.toLongOrNull() ?: 0,
                cache.getOrNull(i)?.toLongOrNull() ?: 0,
            )
        }
        return out
    }

    private val COMPONENT = Regex("^\\s*([A-Za-z][\\w.]*)/([\\w.\\$]+)\\s*$")

    /** Components printed one per line by `cmd package query-activities --brief` / `resolve-activity --brief`. */
    fun components(text: String): List<String> =
        text.lineSequence().mapNotNull { COMPONENT.find(it) }.map { "${it.groupValues[1]}/${it.groupValues[2]}" }.distinct().toList()

    fun homeActivities(queryOut: String, resolveOut: String): List<HomeActivity> {
        val current = components(resolveOut).lastOrNull()
        val all = components(queryOut).filter { !it.startsWith("com.android.settings/") || it == current }
        val list = all.map { HomeActivity(normalize(it), sameComponent(it, current)) }.toMutableList()
        if (current != null && list.none { it.isCurrent }) list.add(0, HomeActivity(normalize(current), true))
        return list
    }

    /** "com.foo/.Main" -> "com.foo/com.foo.Main". */
    fun normalize(c: String): String {
        val pkg = c.substringBefore('/'); val cls = c.substringAfter('/')
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else c
    }
    private fun sameComponent(a: String, b: String?) = b != null && normalize(a) == normalize(b)

    /** `settings list <ns>` -> key/value. */
    fun settingsList(text: String): List<Pair<String, String>> =
        text.lineSequence().filter { it.contains('=') }.map { it.substringBefore('=') to it.substringAfter('=') }
            .filter { it.first.isNotBlank() }.toList()

    private val PKG_HEADER = Regex("^\\s*Package \\[([^\\]]+)\\]")
    private val GRANTED = Regex("android\\.permission\\.([A-Z_]+): granted=true")

    /** Grep of `dumpsys package packages` (Package [..] headers + granted lines) -> pkg -> granted permission short names. */
    fun grantedPermissions(text: String): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        var cur: String? = null
        text.lineSequence().forEach { line ->
            val h = PKG_HEADER.find(line)
            if (h != null) { cur = h.groupValues[1]; return@forEach }
            val g = GRANTED.find(line) ?: return@forEach
            val p = cur ?: return@forEach
            out.getOrPut(p) { LinkedHashSet() }.add(g.groupValues[1])
        }
        return out
    }

    private val OP_LINE = Regex("^(?:Uid mode: )?([A-Z_]+): ([a-z_]+)(.*)$")
    private val TIME = Regex("(?:time|access)=\\+([0-9a-z]+) ago")

    /** `appops get <pkg> <OP>` -> mode and last access. Unknown format -> null. */
    fun appOp(text: String, op: String): OpUse? {
        var best: OpUse? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            val m = OP_LINE.find(line) ?: return@forEach
            if (m.groupValues[1] != op) return@forEach
            val rest = m.groupValues[3]
            val use = OpUse(op, m.groupValues[2], TIME.find(rest)?.groupValues?.get(1), rest.contains("running", ignoreCase = true))
            // Prefer the package line (with time) over the "Uid mode" line.
            if (best == null || (best!!.lastAccess == null && use.lastAccess != null)) best = use
        }
        return best
    }

    /** "+1d2h3m4s5ms" style durations -> Russian short text. */
    fun humanAgo(raw: String): String {
        val parts = Regex("(\\d+)(ms|d|h|m|s)").findAll(raw).map { it.groupValues[1].toLong() to it.groupValues[2] }.toList()
        if (parts.isEmpty()) return raw
        val names = mapOf("d" to "д", "h" to "ч", "m" to "мин", "s" to "с")
        val shown = parts.filter { it.second != "ms" }.take(2)
        if (shown.isEmpty()) return "только что"
        return shown.joinToString(" ") { "${it.first} ${names[it.second]}" } + " назад"
    }

    /** Colon-separated component lists from secure settings (accessibility services, notification listeners). */
    fun colonComponents(value: String?): List<String> {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v == "null") return emptyList()
        return v.split(':').map { it.trim() }.filter { it.contains('/') }
    }

    private val ADMIN = Regex("ComponentInfo\\{([^\\}]+)\\}")
    fun deviceAdmins(text: String): List<String> = ADMIN.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Output of a batch of commands separated by `echo @@<name>` markers. */
    fun splitMarked(text: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        text.lineSequence().forEach { line ->
            if (line.startsWith("@@") && line.length > 2) {
                cur = StringBuilder(); out[line.substring(2).trim()] = cur!!
            } else cur?.append(line)?.append('\n')
        }
        return out.mapValues { it.value.toString() }
    }

    fun clean(v: String?): String = v?.trim()?.takeUnless { it == "null" }.orEmpty()
}
