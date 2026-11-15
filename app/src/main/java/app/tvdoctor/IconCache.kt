package app.tvdoctor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import app.tvdoctor.core.Snapshot
import java.io.File
import java.util.concurrent.Executors

/**
 * Icons and names for apps the user installed on the TV. The TV's shell cannot render icons, so we
 * pull each app's base.apk over a second ADB connection, let the phone's PackageManager read the
 * icon and label from it, and keep only a small PNG plus the name. Cached by APK path, so each
 * app is downloaded once (again only after it is updated on the TV).
 */
object IconCache {

    private const val MAX_APK_BYTES = 250L * 1024 * 1024
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

    /** Starts fetching missing icons for user-installed apps; [onUpdate] runs on the main thread after each one. */
    fun load(a: MainActivity, s: Snapshot, onUpdate: () -> Unit) {
        init(a)
        if (running) return
        val todo = synchronized(this) {
            s.packages.values.filter { !it.isSystem && it.installed && it.apkPath.endsWith(".apk") && source[it.pkg] != it.apkPath && it.pkg !in failed }
        }
        if (todo.isEmpty()) return
        running = true
        val tmp = File(a.cacheDir, "iconpull").apply { mkdirs() }
        exec.execute {
            try {
                a.adb.openSecondary().use { d ->
                    for (p in todo) {
                        try {
                            val size = d.shell("stat -c %s '${p.apkPath}'").output.trim().toLongOrNull() ?: 0L
                            if (size > MAX_APK_BYTES) { synchronized(this) { failed += p.pkg }; continue }
                            val f = File(tmp, "${p.pkg}.apk")
                            d.pull(f, p.apkPath)
                            val pm = a.packageManager
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
                            else { store(p.pkg, p.apkPath, label, bmp); main.post(onUpdate) }
                        } catch (_: Exception) {
                            synchronized(this) { failed += p.pkg }
                        }
                    }
                }
            } catch (_: Exception) {
                // No second connection (TV refused it): icons simply stay empty.
            } finally {
                tmp.deleteRecursively()
                running = false
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
