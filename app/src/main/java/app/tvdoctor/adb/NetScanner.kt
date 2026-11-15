package app.tvdoctor.adb

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Finds devices on the local /24 networks that listen on the ADB port. */
object NetScanner {

    fun localPrefixes(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        out += ip.substringBeforeLast('.')
                    }
                }
            }
        } catch (_: Exception) {}
        return out.distinct()
    }

    fun ownAddresses(): Set<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }

    fun scan(port: Int = 5555, timeoutMs: Int = 350, onFound: (String) -> Unit = {}): List<String> {
        val found = Collections.synchronizedList(ArrayList<String>())
        val own = ownAddresses()
        val pool = Executors.newFixedThreadPool(48)
        for (prefix in localPrefixes()) for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip in own) continue
            pool.execute {
                try {
                    Socket().use { s -> s.connect(InetSocketAddress(ip, port), timeoutMs) }
                    found += ip
                    onFound(ip)
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return found.sorted()
    }
}
