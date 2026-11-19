package app.tvdoctor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import app.tvdoctor.core.PackageState
import app.tvdoctor.core.Snapshot
import java.io.File
import java.util.concurrent.Executors

/**
 * Icons and names for apps on the TV — теперь и для системных приложений тоже.
 * Сначала пробуем взять иконку с самого телефона (мгновенно), иначе тянем base.apk
 * по второму ADB-соединению. Кэш по пути APK: каждое приложение качается один раз.
 */
object IconCache {

    private const val MAX_APK_BYTES = 250L * 1024 * 1024
    /** Системные APK бывают огромными (Play Services, WebView) — такие не тянем. */
    private const val MAX_SYSTEM_APK_BYTES = 60L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val icons = HashMap<String, Bitmap>()
    private val labels = HashMap<String, String>()
    /** pkg -> apk path the cached icon was made from. */
    private val source = HashMap<String, String>()
    private val failed = HashSet<String>()
    @Volatile private var running = false
    private var dir: File? = null

    @Synchronized fun icon(pkg: String): Bitmap? = icons[pkg]
    @Synchronized fun label(pkg: String): String? = labels[pkg]

    @Synchronized
    private fun init(a: MainActivity) {
        if (dir != null) return
        val d = File(a.filesDir, "icons").apply { mkdirs() }
        dir = d
        File(d, "index.tsv").takeIf { it.exists() }?.readLines()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@forEach
            val (pkg, path, label) = parts
            val png = File(d, "$pkg.png")
            if (png.exists()) BitmapFactory.decodeFile(png.path)?.let { icons[pkg] = it }
            source[pkg] = path
            if (label.isNotBlank()) labels[pkg] = label
        }
    }

    @Synchronized
    private fun store(pkg: String, path: String, label: String?, bmp: Bitmap?) {
        val d = dir ?: return
        if (bmp != null) {
            icons[pkg] = bmp
            File(d, "$pkg.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        if (!label.isNullOrBlank()) labels[pkg] = label
        source[pkg] = path
        File(d, "index.tsv").writeText(source.entries.joinToString("\n") { (p, src) -> "$p\t$src\t${labels[p].orEmpty().replace('\t', ' ').replace('\n', ' ')}" })
    }

    /** Тянет недостающие иконки: сначала «Мои», потом системные. [onUpdate] — в главном потоке. */
    fun load(a: MainActivity, s: Snapshot, onUpdate: () -> Unit) {
        init(a)
        if (running) return
        val todo = synchronized(this) {
            s.packages.values
                .filter { it.installed && it.apkPath.endsWith(".apk") && source[it.pkg] != it.apkPath && it.pkg !in failed }
                .sortedBy { it.isSystem }
        }
        if (todo.isEmpty()) return
        running = true
        val tmp = File(a.cacheDir, "iconpull").apply { mkdirs() }
        exec.execute {
            var fresh = 0
            try {
                val pm = a.packageManager
                val pending = ArrayList<PackageState>()
                // 1) Быстрый путь: то же приложение установлено на телефоне — берём иконку локально.
                for (p in todo) {
                    var ok = false
                    if (p.isSystem) {
                        try {
                            val ai = pm.getApplicationInfo(p.pkg, 0)
                            val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { null }
                            val bmp = try { render(ai.loadIcon(pm)) } catch (_: Exception) { null }
                            if (bmp != null) {
                                store(p.pkg, p.apkPath, label, bmp)
                                fresh++
                                if (fresh % 8 == 0) main.post(onUpdate)
                                ok = true
                            }
                        } catch (_: Exception) { }
                    }
                    if (!ok) pending += p
                }
                // 2) Остальные — качаем APK с телевизора.
                if (pending.isNotEmpty()) {
                    a.adb.openSecondary().use { d ->
                        for (p in pending) {
                            try {
                                val limit = if (p.isSystem) MAX_SYSTEM_APK_BYTES else MAX_APK_BYTES
                                val size = d.shell("stat -c %s '${p.apkPath}'").output.trim().toLongOrNull() ?: 0L
                                if (size > limit) { synchronized(this) { failed += p.pkg }; continue }
                                val f = File(tmp, "${p.pkg}.apk")
                                d.pull(f, p.apkPath)
                                val info = pm.getPackageArchiveInfo(f.path, 0)
                                val ai = info?.applicationInfo
                                var bmp: Bitmap? = null
                                var label: String? = null
                                if (ai != null) {
                                    ai.sourceDir = f.path; ai.publicSourceDir = f.path
                                    label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { null }
                                    bmp = try { render(ai.loadIcon(pm)) } catch (_: Exception) { null }
                                }
                                f.delete()
                                if (bmp == null && label == null) synchronized(this) { failed += p.pkg }
                                else {
                                    store(p.pkg, p.apkPath, label, bmp)
                                    fresh++
                                    if (fresh % 8 == 0) main.post(onUpdate)
                                }
                            } catch (_: Exception) {
                                synchronized(this) { failed += p.pkg }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // No second connection (TV refused it): icons simply stay empty.
            } finally {
                tmp.deleteRecursively()
                running = false
                main.post(onUpdate)
            }
        }
    }

    private fun render(d: android.graphics.drawable.Drawable): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        return b
    }
}
