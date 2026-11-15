package app.tvdoctor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import app.tvdoctor.core.Parsers
import java.io.File
import java.util.zip.ZipInputStream

/**
 * "Install APK from phone to TV": the user picks .apk files (or an .apks/.xapk/.apkm bundle) in the
 * phone's file picker, we copy them to the app cache and stream them to the TV over ADB.
 */
class ApkInstaller(private val a: MainActivity) {

    companion object {
        const val REQUEST = 4201
    }

    fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "application/x-zip-compressed"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { a.startActivityForResult(i, REQUEST) } catch (e: Exception) { a.toast("Не найден выбор файлов на телефоне") }
    }

    fun onResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return
        a.work("Готовлю файлы…", { progress -> prepare(uris, progress) }) { prepared -> confirm(prepared) }
    }

    data class Prepared(val apks: List<File>, val pkg: String?, val label: String?, val version: String?, val sizeBytes: Long, val names: List<String>)

    private fun prepare(uris: List<Uri>, progress: (String) -> Unit): Prepared {
        val dir = File(a.cacheDir, "install").apply { deleteRecursively(); mkdirs() }
        val apks = ArrayList<File>()
        val names = ArrayList<String>()
        uris.forEachIndexed { idx, uri ->
            val name = displayName(uri) ?: "file$idx.apk"
            names += name
            progress("Копирую $name…")
            val local = File(dir, "in_${idx}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            a.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть $name" }
                local.outputStream().use { input.copyTo(it) }
            }
            val lower = name.lowercase()
            if (lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") || (!lower.endsWith(".apk") && isZipBundle(local))) {
                progress("Распаковываю $name…")
                apks += unpackBundle(local, File(dir, "b$idx"))
            } else apks += local
        }
        require(apks.isNotEmpty()) { "В выбранных файлах нет APK" }
        val base = apks.firstOrNull { it.name.contains("base") } ?: apks.maxByOrNull { it.length() }!!
        val info = try { a.packageManager.getPackageArchiveInfo(base.path, 0) } catch (_: Exception) { null }
        val label = try { info?.applicationInfo?.let { ai -> ai.sourceDir = base.path; ai.publicSourceDir = base.path; ai.loadLabel(a.packageManager).toString() } } catch (_: Exception) { null }
        return Prepared(apks, info?.packageName, label, info?.versionName, apks.sumOf { it.length() }, names)
    }

    /** True if the file is a zip that contains .apk entries (a split-APK bundle), not an APK itself. */
    private fun isZipBundle(f: File): Boolean = try {
        ZipInputStream(f.inputStream()).use { z ->
            var e = z.nextEntry; var hasApk = false; var hasManifest = false
            while (e != null) { if (e.name.endsWith(".apk")) hasApk = true; if (e.name == "AndroidManifest.xml") hasManifest = true; e = z.nextEntry }
            hasApk && !hasManifest
        }
    } catch (_: Exception) { false }

    private fun unpackBundle(bundle: File, out: File): List<File> {
        out.mkdirs()
        val res = ArrayList<File>()
        ZipInputStream(bundle.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.substringAfterLast('/')
                if (!e.isDirectory && n.endsWith(".apk")) {
                    val f = File(out, n.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    f.outputStream().use { z.copyTo(it) }
                    res += f
                }
                e = z.nextEntry
            }
        }
        bundle.delete()
        require(res.isNotEmpty()) { "В архиве ${bundle.name} нет APK-файлов" }
        return res
    }

    private fun displayName(uri: Uri): String? = try {
        a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun confirm(p: Prepared) {
        val title = p.label ?: p.pkg ?: p.names.first()
        val already = p.pkg?.let { a.snapshot?.packages?.get(it)?.installed } == true
        val msg = buildString {
            append("Установить на телевизор:\n\n$title")
            p.version?.let { append(" $it") }
            p.pkg?.let { append("\n$it") }
            append("\n\nФайлов: ${p.apks.size}, размер ${app.tvdoctor.core.Scanner.bytes(p.sizeBytes)}")
            if (already) append("\n\nПриложение уже есть на ТВ — будет обновлено.")
            if (p.names.any { it.lowercase().endsWith(".xapk") }) append("\n\nДополнительные файлы игр (OBB) из .xapk не копируются.")
        }
        AlertDialog.Builder(a).setTitle("Установка APK").setMessage(msg)
            .setPositiveButton("Установить") { _, _ -> install(p, title, already) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun install(p: Prepared, title: String, already: Boolean) {
        a.work("Устанавливаю $title на телевизор…\n(${app.tvdoctor.core.Scanner.bytes(p.sizeBytes)}, по Wi‑Fi может занять минуту)", { a.adb.install(p.apks) }) { error ->
            File(a.cacheDir, "install").deleteRecursively()
            if (error == null) {
                val pkg = p.pkg
                val undo = if (pkg != null && !already && Parsers.isPackageName(pkg)) listOf("pm uninstall $pkg") else emptyList()
                a.journal.add(a.snapshot?.device?.title ?: "${a.adb.host}", "Установить $title", listOf("install ${p.names.joinToString()}"), undo)
                AlertDialog.Builder(a).setTitle("Установлено").setMessage("$title установлено на телевизор.")
                    .setPositiveButton("OK", null)
                    .apply { if (pkg != null && Parsers.isPackageName(pkg)) setNeutralButton("Запустить на ТВ") { _, _ -> a.work(null, { a.adb.run(app.tvdoctor.core.Commands.launch(pkg).run) }) {} } }
                    .show()
                a.refreshPackages { a.screen(a.currentId())?.onShow() }
            } else {
                AlertDialog.Builder(a).setTitle("Не установилось").setMessage(explain(error)).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun explain(e: String): String {
        val hint = when {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in e -> "На ТВ стоит эта же программа с другой подписью. Удалите её на ТВ (Приложения → … → Удалить для пользователя) и повторите."
            "INSTALL_FAILED_VERSION_DOWNGRADE" in e -> "На ТВ уже стоит более новая версия."
            "INSTALL_FAILED_NO_MATCHING_ABIS" in e -> "APK собран для другого процессора. Найдите версию для armeabi-v7a/arm64-v8a, подходящую вашему ТВ."
            "INSTALL_FAILED_OLDER_SDK" in e -> "Приложению нужна более новая версия Android, чем на ТВ."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in e -> "На ТВ не хватает места. Очистите кэш («Ещё → Очистить кэш»)."
            "INSTALL_PARSE_FAILED" in e || "INSTALL_FAILED_INVALID_APK" in e -> "Файл повреждён или это не APK. Для разделённых APK выберите все части сразу или архив .apks/.xapk."
            "INSTALL_FAILED_MISSING_SPLIT" in e -> "Это разделённое приложение: выберите все его части (base.apk и config.*.apk) или архив .apks/.xapk."
            "INSTALL_FAILED_USER_RESTRICTED" in e -> "Телевизор запретил установку по ADB. В «Для разработчиков» включите «Установка через USB» / «Проверять приложения при установке» отключите."
            else -> null
        }
        return (hint?.let { "$it\n\n" } ?: "") + "Ответ телевизора:\n" + e.take(800)
    }
}
