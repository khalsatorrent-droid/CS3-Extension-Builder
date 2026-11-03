package com.khalsatorrent.cs3builder.monitor

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.MutableLiveData
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.network.LogRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watches one app's network activity without a certificate and without touching any other app.
 *
 * It is a VPN that only that app is placed in (addAllowedApplication), and the only thing routed
 * into it is DNS. Every other packet of that app still goes straight to the internet, so the app
 * keeps working normally; the service just sees — and answers — each hostname lookup the app makes.
 * The result is the list of servers the app talks to (API, video CDN, ads…), which is what you need
 * to start reverse engineering it. Request bodies stay encrypted (that would need a certificate).
 */
class AppMonitorService : VpnService() {

    data class Host(
        val host: String,
        val kind: HostClassifier.Kind,
        val count: Int,
        val addresses: List<String>,
        val firstSeen: Long,
        val lastSeen: Long
    )

    data class Session(val pkg: String, val label: String, val started: Long)

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false
    private var out: FileOutputStream? = null
    private val seen = ConcurrentHashMap<String, Host>()
    private var pkg = ""
    private var label = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val p = intent.getStringExtra(EXTRA_PKG) ?: return START_NOT_STICKY
                shutdown()
                pkg = p
                label = intent.getStringExtra(EXTRA_LABEL) ?: p
                if (!startTunnel()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(): Boolean {
        val builder = Builder()
            .setSession("Monitoring $label")
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(TUN_DNS)
            .addRoute(TUN_DNS, 32)
        // Apps that hard-code a public resolver still get answered (and logged)
        PUBLIC_DNS.forEach { builder.addRoute(it, 32) }
        try {
            builder.addAllowedApplication(pkg)
        } catch (e: Exception) {
            error.postValue("Can't monitor $label: ${e.message}")
            return false
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        builder.setBlocking(true)

        val fd = try { builder.establish() } catch (e: Exception) {
            error.postValue("Could not start the monitor: ${e.message}"); null
        } ?: return false

        tun = fd
        out = FileOutputStream(fd.fileDescriptor)
        pool = Executors.newFixedThreadPool(8)
        running = true
        seen.clear()
        session.postValue(Session(pkg, label, System.currentTimeMillis()))
        hosts.postValue(emptyList())
        error.postValue(null)
        reader = Thread({ readLoop(fd) }, "cs3-monitor").apply { start() }
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n < 0) break
            if (n == 0) continue
            val q = DnsPacket.parseIpv4Udp(buf, n) ?: continue   // only DNS is routed here; drop the rest
            if (q.dstPort != 53) continue
            pool?.execute { answer(q) }
        }
    }

    private fun answer(q: DnsPacket.Query) {
        val dst = InetAddress.getByAddress(q.dstIp).hostAddress ?: return
        val upstreams = if (dst == TUN_DNS) systemResolvers() else listOf(InetAddress.getByAddress(q.dstIp))
        var reply: ByteArray? = null
        for (server in upstreams) {
            reply = try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q.dns, q.dns.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    rb.copyOf(rp.length)
                }
            } catch (_: Exception) { null }
            if (reply != null) break
        }
        val payload = reply ?: DnsPacket.servfail(q.dns)
        val packet = DnsPacket.buildIpv4Udp(q.dstIp, q.srcIp, 53, q.srcPort, payload)
        try { synchronized(this) { out?.write(packet) } } catch (_: Exception) { }
        record(q.dns, reply)
    }

    /** The real network's resolvers (our own app is outside the VPN, so this is the underlying network). */
    private fun systemResolvers(): List<InetAddress> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val found = try { cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty() } catch (_: Exception) { emptyList() }
        return (found + PUBLIC_DNS.take(2).map { InetAddress.getByName(it) }).distinct()
    }

    private fun record(query: ByteArray, reply: ByteArray?) {
        val (name, type) = DnsPacket.question(query) ?: return
        if (name.isBlank() || name.endsWith(".arpa")) return
        val addrs = reply?.let { DnsPacket.answers(it) }.orEmpty()
        val now = System.currentTimeMillis()
        val old = seen[name]
        val host = Host(
            host = name,
            kind = old?.kind ?: HostClassifier.classify(name, pkg),
            count = (old?.count ?: 0) + 1,
            addresses = ((old?.addresses ?: emptyList()) + addrs).distinct().take(12),
            firstSeen = old?.firstSeen ?: now,
            lastSeen = now
        )
        seen[name] = host
        if (old == null) {
            LogRepository.addLog(
                NetworkLog(
                    id = UUID.randomUUID().toString(), timestamp = now, method = "DNS", url = "dns://$name",
                    requestHeaders = linkedMapOf("App" to label, "Package" to pkg, "Query" to type, "Category" to host.kind.label),
                    requestBody = null, responseCode = 0, responseHeaders = emptyMap(),
                    responseBody = if (addrs.isEmpty()) "(no answer)" else addrs.joinToString("\n"),
                    cookies = emptyList(), mimeType = null, isXHR = false, duration = 0,
                    source = "monitor", kind = LogKind.OTHER, pageUrl = "app://$pkg"
                )
            )
        }
        schedulePublish()
    }

    @Volatile private var publishPending = false
    private fun schedulePublish() {
        if (publishPending) return
        publishPending = true
        main.postDelayed({
            publishPending = false
            hosts.value = seen.values.sortedWith(compareBy<Host> { it.kind.rank }.thenByDescending { it.count })
        }, 300)
    }

    private fun shutdown() {
        running = false
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        out = null
        pool?.shutdownNow()
        pool = null
        reader?.interrupt()
        reader = null
        session.postValue(null)
    }

    override fun onRevoke() { shutdown(); stopSelf() }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.khalsatorrent.cs3builder.monitor.START"
        const val ACTION_STOP = "com.khalsatorrent.cs3builder.monitor.STOP"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"

        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_DNS = "10.111.222.2"
        private val PUBLIC_DNS = listOf("8.8.8.8", "1.1.1.1", "8.8.4.4", "1.0.0.1", "9.9.9.9")

        private val main = Handler(Looper.getMainLooper())

        /** The app being monitored, or null. */
        val session = MutableLiveData<Session?>(null)
        /** Hostnames seen this session, most interesting first. */
        val hosts = MutableLiveData<List<Host>>(emptyList())
        val error = MutableLiveData<String?>(null)

        fun start(context: Context, pkg: String, label: String) {
            context.startService(
                Intent(context, AppMonitorService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_LABEL, label)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AppMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
