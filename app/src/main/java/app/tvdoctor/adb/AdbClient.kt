package app.tvdoctor.adb

import app.tvdoctor.core.Shell
import app.tvdoctor.core.ShellResult
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.InstallResult
import java.io.File
import java.io.IOException

/**
 * One ADB-over-TCP connection to the TV, using dadb. The RSA key lives in the app's private files,
 * so the TV only asks "Allow debugging?" once (tick "Always allow").
 */
class AdbClient(private val keyDir: File) : Shell {

    @Volatile private var dadb: Dadb? = null
    @Volatile var host: String? = null; private set
    @Volatile var port: Int = 5555; private set
    private var shellV2 = true

    val isConnected: Boolean get() = dadb != null

    private fun keyPair(): AdbKeyPair {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
            // The comment after the key is the name the TV shows in its "Allow debugging?" dialog.
            val key = pub.readText().substringBefore(' ')
            pub.writeText("$key TVDoctor@phone")
        }
        return AdbKeyPair.read(priv, pub)
    }

    /** Blocks until the TV accepts the key (the user must confirm on the TV screen). */
    @Synchronized
    fun connect(host: String, port: Int) {
        disconnect()
        val d = Dadb.create(host, port, keyPair(), connectTimeout = 6_000, socketTimeout = 180_000, keepAlive = true)
        try {
            shellV2 = d.supportsFeature("shell_v2")
            val probe = rawShell(d, "echo tvdoctor-ok")
            if (!probe.output.contains("tvdoctor-ok")) throw IOException("Устройство ответило неожиданно: ${probe.all}")
        } catch (e: Exception) {
            try { d.close() } catch (_: Exception) {}
            throw e
        }
        dadb = d
        this.host = host
        this.port = port
    }

    @Synchronized
    fun disconnect() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    private fun rawShell(d: Dadb, command: String): ShellResult {
        if (shellV2) {
            val r = d.shell(command)
            return ShellResult(r.output, r.errorOutput, r.exitCode)
        }
        // Old adbd without shell protocol v2: no separate stderr / exit code.
        d.open("shell:$command").use { s -> return ShellResult(s.source.readUtf8(), "", 0) }
    }

    /** Runs a command; if the connection dropped (TV slept, Wi‑Fi blip) reconnects once and retries. */
    @Synchronized
    override fun run(command: String): ShellResult {
        val d = dadb ?: throw IOException("Нет подключения к телевизору")
        return try {
            rawShell(d, command)
        } catch (e: Exception) {
            val h = host ?: throw e
            try { d.close() } catch (_: Exception) {}
            val fresh = Dadb.create(h, port, keyPair(), connectTimeout = 6_000, socketTimeout = 180_000, keepAlive = true)
            dadb = fresh
            try {
                rawShell(fresh, command)
            } catch (e2: Exception) {
                dadb = null
                throw IOException("Связь с телевизором потеряна: ${e2.message ?: e2.javaClass.simpleName}", e2)
            }
        }
    }

    /**
     * Installs APK files on the TV. One file = normal install; several files = one split-APK
     * session (base.apk + config.*.apk). Returns null on success or the error text from the TV.
     */
    @Synchronized
    fun install(apks: List<File>): String? {
        val d = dadb ?: throw IOException("Нет подключения к телевизору")
        val result = if (apks.size == 1) d.install(apks[0], "-r") else d.installMultiple(apks, "-r")
        return when (result) {
            is InstallResult.Success -> null
            is InstallResult.Failure -> result.reason.trim().ifEmpty { "неизвестная ошибка" }
        }
    }

    /** A second, independent ADB connection (same key) for slow background work like pulling APKs. */
    fun openSecondary(): Dadb {
        val h = host ?: throw IOException("Нет подключения к телевизору")
        return Dadb.create(h, port, keyPair(), connectTimeout = 6_000, socketTimeout = 120_000, keepAlive = true)
    }
}
