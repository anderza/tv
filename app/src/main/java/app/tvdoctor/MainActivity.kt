package app.tvdoctor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tvdoctor.adb.AdbClient
import app.tvdoctor.core.Fix
import app.tvdoctor.core.Repository
import app.tvdoctor.core.Snapshot
import app.tvdoctor.ui.AppSheet
import app.tvdoctor.ui.AppsScreen
import app.tvdoctor.ui.ConnectScreen
import app.tvdoctor.ui.MoreScreen
import app.tvdoctor.ui.Pal
import app.tvdoctor.ui.PrivacyScreen
import app.tvdoctor.ui.RemoteScreen
import app.tvdoctor.ui.ScanScreen
import app.tvdoctor.ui.SettingsScreen
import app.tvdoctor.ui.WRAP
import app.tvdoctor.ui.MATCH
import app.tvdoctor.ui.add
import app.tvdoctor.ui.dp
import app.tvdoctor.ui.makeButton
import app.tvdoctor.ui.rounded
import app.tvdoctor.ui.text
import app.tvdoctor.ui.vertical
import java.util.concurrent.Executors

interface Screen {
    val view: View
    fun onShow() {}
}

/** Process-wide state that survives activity recreation (rotation). */
object AppState {
    var adb: AdbClient? = null
    var snapshot: Snapshot? = null
    var lastPrivacy: Repository.Privacy? = null
}

class MainActivity : Activity() {

    lateinit var adb: AdbClient; private set
    lateinit var repo: Repository; private set
    lateinit var journal: Journal; private set
    lateinit var prefs: SharedPreferences; private set

    var snapshot: Snapshot?
        get() = AppState.snapshot
        set(v) { AppState.snapshot = v }

    val expert: Boolean get() = prefs.getBoolean("expert", false)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var busy: LinearLayout
    private lateinit var busyText: TextView
    private var busyCount = 0

    private var screens = HashMap<String, Screen>()
    private var current = "scan"
    private lateinit var content: FrameLayout
    private val navButtons = HashMap<String, Button>()
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        installCrashLog()
        prefs = getSharedPreferences("tvdoctor", Context.MODE_PRIVATE)
        journal = Journal(java.io.File(filesDir, "journal.json"))
        adb = AppState.adb ?: AdbClient(filesDir).also { AppState.adb = it }
        repo = Repository(adb)

        root = FrameLayout(this).apply { setBackgroundColor(Pal.BG) }
        busy = vertical(this, 24).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            val box = vertical(context, 20).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(context, Pal.SURFACE, 16)
                add(ProgressBar(context), w = WRAP)
            }
            busyText = box.text("", 15f, top = 12).apply { gravity = Gravity.CENTER }
            add(box, w = MATCH)
        }
        setContentView(root)
        if (adb.isConnected) showMain() else showConnect()
        root.addView(busy, FrameLayout.LayoutParams(MATCH, MATCH))
        showPreviousCrash()
    }

    private val crashFile get() = java.io.File(filesDir, "last_crash.txt")

    /** If the app ever crashes, keep the stack trace and show it on the next start so it can be reported. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(java.io.File(filesDir, "last_crash.txt"), previous))
    }

    private fun showPreviousCrash() {
        val f = crashFile
        if (!f.exists()) return
        val text = try { f.readText().take(4000) } catch (_: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        AlertDialog.Builder(this).setTitle("Прошлый запуск завершился с ошибкой")
            .setMessage("Пришлите этот текст разработчику:\n\n$text")
            .setPositiveButton("OK", null).show()
    }

    // ---------- navigation ----------

    fun showConnect() {
        screens.clear()
        val s = ConnectScreen(this)
        setBody(s.view)
        s.onShow()
    }

    fun onConnected() {
        prefs.edit().putString("host", adb.host).putInt("port", adb.port).apply()
        val recent = (listOf("${adb.host}:${adb.port}") + (prefs.getString("recent", "") ?: "").split(',')).filter { it.isNotBlank() }.distinct().take(6)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
        snapshot = null
        AppState.lastPrivacy = null
        showMain()
        loadSnapshot { (screens["scan"] as? ScanScreen)?.render() }
    }

    fun disconnect() {
        worker.execute { adb.disconnect() }
        snapshot = null
        showConnect()
    }

    private fun setBody(v: View) {
        val idx = if (busy.parent == root) 1 else 0
        while (root.childCount > idx) root.removeViewAt(0)
        root.addView(v, 0, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showMain() {
        val col = vertical(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(10), dp(10))
            background = rounded(context, Pal.SURFACE, 0)
        }
        val titles = vertical(this)
        headerTitle = titles.text("TV Doctor", 18f, bold = true)
        headerSub = titles.text("", 12.5f, Pal.SUB)
        header.add(titles, w = 0, weight = 1f)
        header.add(makeButton(this, "Отключиться", app.tvdoctor.ui.Kind.GHOST) { disconnect() })
        col.add(header)
        content = FrameLayout(this)
        col.add(content, h = 0, weight = 1f)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Pal.SURFACE)
        }
        navButtons.clear()
         listOf("scan" to "Скан", "apps" to "Прил.", "remote" to "Пульт", "privacy" to "Слежка", "settings" to "Настр.", "more" to "Ещё").forEach { (id, label) ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 11.5f; maxLines = 1; stateListAnimator = null
                setPadding(0, dp(10), 0, dp(10)); minHeight = 0; minimumHeight = 0
                setOnClickListener { go(id) }
            }
            navButtons[id] = b
            nav.add(b, w = 0, weight = 1f, start = 2, end = 2)
        }
        col.add(nav)
        setBody(col)
        updateHeader()
        go(current)
    }

    fun updateHeader() {
        if (!::headerTitle.isInitialized) return
        val d = snapshot?.device
        headerTitle.text = d?.title ?: "TV Doctor"
        headerSub.text = buildString {
            append("${adb.host}:${adb.port}")
            if (d != null) append(" · Android ${d.androidVersion} (API ${d.sdk})")
        }
    }

    fun go(id: String) {
        try { goUnsafe(id) } catch (e: Throwable) { showError(e) }
    }

    private fun goUnsafe(id: String) {
        current = id
        val s = screens.getOrPut(id) {
            when (id) {
                "apps" -> AppsScreen(this)
                "remote" -> RemoteScreen(this)
                "privacy" -> PrivacyScreen(this)
                "settings" -> SettingsScreen(this)
                "more" -> MoreScreen(this)
                else -> ScanScreen(this)
            }
        }
        content.removeAllViews()
        (s.view.parent as? android.view.ViewGroup)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        navButtons.forEach { (k, b) ->
            val sel = k == id
            b.background = rounded(this, if (sel) Pal.ACCENT_DARK else Color.TRANSPARENT, 10)
            b.setTextColor(if (sel) Pal.ACCENT else Pal.SUB)
            b.setTypeface(Typeface.DEFAULT, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        s.onShow()
    }

    fun screen(id: String): Screen? = screens[id]
    fun currentId(): String = current

    @Deprecated("framework API")
    override fun onBackPressed() {
        if (busy.visibility == View.VISIBLE) return
        if (adb.isConnected && ::content.isInitialized && current != "scan") go("scan") else super.onBackPressed()
    }

    // ---------- background work ----------

    fun setBusy(text: String?) {
        if (text != null) { busyCount++; busyText.text = text; busy.visibility = View.VISIBLE; busy.bringToFront() }
        else { busyCount = maxOf(0, busyCount - 1); if (busyCount == 0) busy.visibility = View.GONE }
    }

    /** Runs [task] on the single ADB worker thread; shows a blocking overlay when [busyText] is set. */
    fun <T> work(busyText: String?, task: ((String) -> Unit) -> T, done: (T) -> Unit) {
        if (busyText != null) setBusy(busyText)
        worker.execute {
            val progress: (String) -> Unit = { msg -> main.post { this.busyText.text = msg } }
            val result = try { Result.success(task(progress)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (busyText != null) setBusy(null)
                if (isFinishing || isDestroyed) return@post
                try { result.fold(done) { e -> showError(e) } } catch (e: Throwable) { showError(e) }
            }
        }
    }

    fun showError(e: Throwable) {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val main = ConnectScreen.explain(chain.firstOrNull { it is dadb.AdbException } ?: chain.last())
        val details = chain.joinToString("\n← ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
        android.util.Log.e("TVDoctor", "error", e)
        AlertDialog.Builder(this).setTitle("Ошибка")
            .setMessage("$main\n\nПодробности (пришлите разработчику):\n$details")
            .setPositiveButton("OK", null).show()
        if (!adb.isConnected && ::content.isInitialized) toast("Соединение с ТВ потеряно")
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun loadSnapshot(then: () -> Unit = {}) {
        work("Сканирую телевизор…", { p -> repo.snapshot(p) }) { s ->
            snapshot = s
            updateHeader()
            then()
            (screens[current])?.onShow()
        }
    }

    /** Re-reads only package lists (fast) after enable/disable/uninstall. */
    fun refreshPackages(then: () -> Unit = {}) {
        val old = snapshot ?: return then()
        work(null, { repo.refreshPackages(old) }) { s -> snapshot = s; then() }
    }

    // ---------- applying fixes ----------

    fun confirmAndApply(fixes: List<Fix>, after: () -> Unit = {}) {
        if (fixes.isEmpty()) return
        val dangerous = fixes.any { it.dangerous }
        val body = vertical(this, 18)
        body.text(if (fixes.size == 1) fixes[0].title else "Будет выполнено действий: ${fixes.size}", 16f, bold = true)
        fixes.forEach { f ->
            if (fixes.size > 1) body.text("• ${f.title}", 14f, top = 8)
            f.note?.let { body.text(it, 13f, Pal.ORANGE, top = 4) }
            f.cmds.forEach { c -> body.text(c.run, 11.5f, Pal.SUB, mono = true, top = 2) }
        }
        val undoable = fixes.all { f -> f.cmds.all { it.undo != null } }
        body.text(
            if (undoable) "Всё можно будет отменить в разделе «Ещё → Журнал»." else "Часть действий нельзя отменить (например, очистку данных или кэша).",
            13f, if (undoable) Pal.GREEN else Pal.ORANGE, top = 12,
        )
        if (dangerous) body.text("Внимание: действие может повлиять на работу телевизора.", 13f, Pal.RED, top = 6)
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Выполнить") { _, _ -> apply(fixes, after) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun apply(fixes: List<Fix>, after: () -> Unit) {
        val device = snapshot?.device?.title ?: "${adb.host}"
        work("Применяю изменения…", { progress ->
            val errors = ArrayList<String>()
            var ok = 0
            fixes.forEachIndexed { i, f ->
                progress("(${i + 1}/${fixes.size}) ${f.title}")
                val done = ArrayList<String>(); val undo = ArrayList<String>()
                for (c in f.cmds) {
                    val r = adb.run(c.run)
                    if (r.looksFailed) errors += "${f.title}: ${r.all.lines().firstOrNull { it.isNotBlank() } ?: "ошибка"}"
                    else { done += c.run; c.undo?.let { undo.add(0, it) } }
                }
                if (done.isNotEmpty()) { journal.add(device, f.title, done, undo); ok++ }
            }
            ok to errors
        }) { (ok, errors) ->
            if (errors.isEmpty()) toast("Готово: $ok из ${fixes.size}")
            else AlertDialog.Builder(this).setTitle("Выполнено $ok из ${fixes.size}")
                .setMessage("Не получилось:\n\n" + errors.joinToString("\n\n")).setPositiveButton("OK", null).show()
            refreshPackages(after)
        }
    }

    fun undo(entry: Journal.Entry, after: () -> Unit) {
        work("Отменяю: ${entry.title}", {
            entry.undo.map { adb.run(it) }.filter { it.looksFailed }.map { it.all }
        }) { errors ->
            journal.markUndone(entry.id)
            if (errors.isEmpty()) toast("Отменено") else showError(Exception(errors.joinToString("\n")))
            refreshPackages(after)
        }
    }

    fun openApp(pkg: String) = AppSheet(this, pkg).show()

    private val apkInstaller by lazy { ApkInstaller(this) }
    fun installApk() = if (adb.isConnected) apkInstaller.pick() else toast("Сначала подключитесь к телевизору")

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ApkInstaller.REQUEST) apkInstaller.onResult(resultCode, data)
    }
}

private class CrashLogger(private val file: java.io.File, private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            file.writeText("TV Doctor ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}\n$sw")
        } catch (_: Throwable) {}
        next?.uncaughtException(t, e)
    }
}
