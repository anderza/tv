package app.tvdoctor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test fun packageList() {
        val m = Parsers.packageList(
            """
            package:/system/priv-app/TvLauncher/TvLauncher.apk=com.google.android.tvlauncher
            package:/data/app/~~abc==/com.foo.bar-xyz==/base.apk=com.foo.bar
            package:com.plain.pkg
            garbage line
            """.trimIndent()
        )
        assertEquals(setOf("com.google.android.tvlauncher", "com.foo.bar", "com.plain.pkg"), m.keys)
        assertEquals("/data/app/~~abc==/com.foo.bar-xyz==/base.apk", m["com.foo.bar"])
    }

    @Test fun meminfoAndDf() {
        assertEquals(2048000L to 512000L, Parsers.procMeminfo("MemTotal:        2048000 kB\nMemFree:  100 kB\nMemAvailable:     512000 kB\n"))
        assertEquals(1000L to 300L, Parsers.procMeminfo("MemTotal: 1000 kB\nMemFree: 100 kB\nCached: 150 kB\nBuffers: 50 kB\n"))
        val df = "Filesystem       1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-6   5000000 4000000   1000000  80% /data\n"
        assertEquals(5000000L to 1000000L, Parsers.df(df))
        val wrapped = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/bootdevice/by-name/userdata\n 5000 4000 1000 80% /data\n"
        assertEquals(5000L to 1000L, Parsers.df(wrapped))
    }

    @Test fun dumpsysMeminfo() {
        val text = """
            Applications Memory Usage (in Kilobytes):
            Uptime: 123 Realtime: 123

            Total PSS by process:
                245,376K: system (pid 812)
                123,456K: com.google.android.apps.tv.launcherx (pid 3567 / activities)
                 12,000K: com.foo:remote (pid 4000)
                  8,000K: com.foo (pid 4001)

            Total PSS by OOM adjustment:
                245,376K: System
        """.trimIndent()
        val procs = Parsers.dumpsysMeminfo(text)
        assertEquals(4, procs.size)
        assertEquals(123456L, procs[1].kb)
        val byPkg = Parsers.memByPackage(procs)
        assertEquals(20000L, byPkg["com.foo"])
    }

    @Test fun cpuinfo() {
        val text = """
            Load: 3.5 / 2.1 / 1.9
            CPU usage from 60000ms to 0ms ago (2024-01-01 10:00:00.000 to 2024-01-01 10:01:00.000):
              25% 1234/com.google.android.katniss:search: 20% user + 5% kernel / faults: 10 minor
              4.5% 812/system_server: 3% user + 1.5% kernel
              0.1% 1/init: 0% user + 0.1% kernel
            30% TOTAL: 20% user + 10% kernel
        """.trimIndent()
        val (procs, load) = Parsers.cpuinfo(text)
        assertEquals(3.5, load!!, 0.001)
        assertEquals(3, procs.size)
        assertEquals("com.google.android.katniss:search", procs[0].name)
        assertEquals(25.0, Parsers.cpuByPackage(procs)["com.google.android.katniss"]!!, 0.001)
    }

    @Test fun diskstats() {
        val text = """
            Latency: 1ms [512B Data Write]
            Package Names: ["com.a","com.b"]
            App Sizes: [100,200]
            App Data Sizes: [10,20]
            Cache Sizes: [1,2]
        """.trimIndent()
        val m = Parsers.diskstats(text)
        assertEquals(PackageSizes(200, 20, 2), m["com.b"])
    }

    @Test fun homeActivities() {
        val q = """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.HomeActivity
              Activity #1:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                me.efesser.flauncher/me.efesser.flauncher.MainActivity
        """.trimIndent()
        val r = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.google.android.apps.tv.launcherx/.home.HomeActivity"
        val list = Parsers.homeActivities(q, r)
        assertEquals(2, list.size)
        assertTrue(list[0].isCurrent)
        assertEquals("com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity", list[0].component)
        assertFalse(list[1].isCurrent)
    }

    @Test fun grantedPermissionsAndOps() {
        val text = """
              Package [com.google.android.katniss] (1a2b3c):
                  android.permission.RECORD_AUDIO: granted=true, flags=[ GRANTED_BY_DEFAULT ]
              Package [com.spy.app] (4d5e):
                  android.permission.CAMERA: granted=true, flags=[ USER_SET ]
                  android.permission.RECORD_AUDIO: granted=true
        """.trimIndent()
        val g = Parsers.grantedPermissions(text)
        assertEquals(setOf("RECORD_AUDIO"), g["com.google.android.katniss"])
        assertEquals(setOf("CAMERA", "RECORD_AUDIO"), g["com.spy.app"])

        val op = Parsers.appOp("Uid mode: RECORD_AUDIO: allow\nRECORD_AUDIO: allow; time=+2h3m10s5ms ago; duration=+5s\nCAMERA: ignore\n", "RECORD_AUDIO")!!
        assertEquals("allow", op.mode)
        assertEquals("2h3m10s5ms", op.lastAccess)
        assertEquals("2 ч 3 мин назад", Parsers.humanAgo(op.lastAccess!!))
        assertNull(Parsers.appOp("No operations.", "CAMERA"))
    }

    @Test fun marked() {
        val m = Parsers.splitMarked("@@a\nline1\nline2\n@@b\n@@c\nx\n")
        assertEquals("line1\nline2", m["a"]!!.trim()); assertEquals("", m["b"]!!.trim()); assertEquals("x", m["c"]!!.trim())
    }

    @Test fun commandsAreSafe() {
        assertEquals("pm disable-user --user 0 com.foo", Commands.disable("com.foo").run)
        assertEquals("pm enable --user 0 com.foo", Commands.disable("com.foo").undo)
        val c = Commands.settingPut("global", "private_dns_specifier", "a'b", "null")
        assertEquals("settings put global private_dns_specifier 'a'\\''b'", c.run)
        assertEquals("settings delete global private_dns_specifier", c.undo)
        var threw = false
        try { Commands.disable("com.foo; reboot") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun shellFailureDetection() {
        assertTrue(ShellResult("Failure [DELETE_FAILED_INTERNAL_ERROR]", "", 0).looksFailed)
        assertTrue(ShellResult("", "java.lang.SecurityException: Shell cannot change component state", 255).looksFailed)
        assertFalse(ShellResult("Package com.foo new state: disabled-user", "", 0).looksFailed)
    }

    @Test fun scannerEndToEnd() {
        val pkgs = listOf(
            PackageState("com.google.android.tvrecommendations", isSystem = true),
            PackageState("com.google.android.apps.tv.launcherx", isSystem = true),
            PackageState("com.android.systemui", isSystem = true),
            PackageState("com.vendor.adservice", isSystem = true),
            PackageState("com.heavy.game"),
        ).associateBy { it.pkg }
        val s = Snapshot(
            device = DeviceInfo(memTotalKb = 2_000_000, memAvailableKb = 200_000, dataTotalKb = 8_000_000, dataFreeKb = 400_000),
            packages = pkgs,
            memByPkg = mapOf("com.heavy.game" to 300_000L, "com.android.systemui" to 400_000L),
            cpuByPkg = emptyMap(),
            sizes = mapOf("com.heavy.game" to PackageSizes(1, 1, 400L shl 20)),
            homeActivities = listOf(HomeActivity("com.google.android.apps.tv.launcherx/x.Home", true)),
            animationScales = Triple("1.0", "1.0", "1.0"),
            privateDnsMode = "off", privateDnsHost = "",
        )
        val ids = Scanner.findings(s).map { it.id }
        assertTrue(ids.containsAll(listOf("ram_low", "storage_low", "cache_big", "anim", "dns", "pkg_com.google.android.tvrecommendations", "heur_com.vendor.adservice", "mem_com.heavy.game", "launcher")))
        assertFalse(ids.any { it.contains("systemui") })
        assertFalse(ids.contains("pkg_com.google.android.apps.tv.launcherx"))
    }
}
