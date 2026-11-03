package com.khalsatorrent.cs3builder.monitor

/**
 * Minimal IPv4/UDP/DNS packet helpers for [AppMonitorService]. Pure Kotlin, no Android types,
 * so it can be tested off-device.
 */
object DnsPacket {

    /** A DNS query that arrived on the VPN interface. */
    class Query(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val dns: ByteArray
    )

    /** Parses an IPv4/UDP packet; returns null for anything else. */
    fun parseIpv4Udp(p: ByteArray, len: Int): Query? {
        if (len < 28) return null
        if ((p[0].toInt() ushr 4) != 4) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((p[9].toInt() and 0xff) != 17) return null            // UDP
        val total = u16(p, 2).coerceAtMost(len)
        val udp = ihl
        val srcPort = u16(p, udp)
        val dstPort = u16(p, udp + 2)
        val udpLen = u16(p, udp + 4)
        val dataStart = udp + 8
        val dataEnd = minOf(total, udp + udpLen, len)
        if (dataEnd <= dataStart) return null
        return Query(
            p.copyOfRange(12, 16), p.copyOfRange(16, 20),
            srcPort, dstPort, p.copyOfRange(dataStart, dataEnd)
        )
    }

    /** Wraps a DNS answer in IPv4/UDP, addressed back to the app that asked. */
    fun buildIpv4Udp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 4, 0)                 // id
        put16(b, 6, 0x4000)            // don't fragment
        b[8] = 64                      // ttl
        b[9] = 17                      // UDP
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20))
        put16(b, 20, srcPort)
        put16(b, 22, dstPort)
        put16(b, 24, 8 + payload.size)
        put16(b, 26, 0)                // UDP checksum is optional over IPv4
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    /** The first question's name and type ("A", "AAAA", "HTTPS"…), or null. */
    fun question(dns: ByteArray): Pair<String, String>? {
        if (dns.size < 17) return null
        if (u16(dns, 4) < 1) return null
        val (name, end) = readName(dns, 12) ?: return null
        if (end + 4 > dns.size) return null
        return name to typeName(u16(dns, end))
    }

    /** A / AAAA addresses and CNAME targets in an answer. */
    fun answers(dns: ByteArray): List<String> {
        val out = ArrayList<String>()
        try {
            if (dns.size < 12) return out
            val qd = u16(dns, 4); val an = u16(dns, 6)
            var o = 12
            repeat(qd) { o = (readName(dns, o) ?: return out).second + 4 }
            repeat(an) {
                o = (readName(dns, o) ?: return out).second
                if (o + 10 > dns.size) return out
                val type = u16(dns, o); val rdLen = u16(dns, o + 8)
                val rd = o + 10
                if (rd + rdLen > dns.size) return out
                when (type) {
                    1 -> if (rdLen == 4) out += (0..3).joinToString(".") { (dns[rd + it].toInt() and 0xff).toString() }
                    28 -> if (rdLen == 16) out += (0..7).joinToString(":") { Integer.toHexString(u16(dns, rd + it * 2)) }
                    5 -> readName(dns, rd)?.let { out += "→ " + it.first }
                }
                o = rd + rdLen
            }
        } catch (_: Exception) { }
        return out
    }

    /** SERVFAIL reply for a query we could not forward, so the app fails fast instead of hanging. */
    fun servfail(query: ByteArray): ByteArray {
        val b = query.copyOf()
        if (b.size >= 4) {
            b[2] = (b[2].toInt() or 0x80).toByte()                // QR = response
            b[3] = ((b[3].toInt() and 0xf0) or 0x02).toByte()      // RCODE = SERVFAIL
        }
        return b
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var o = start
        var end = -1
        var jumps = 0
        while (true) {
            if (o >= b.size) return null
            val len = b[o].toInt() and 0xff
            when {
                len == 0 -> { if (end < 0) end = o + 1; break }
                len and 0xc0 == 0xc0 -> {
                    if (o + 1 >= b.size || ++jumps > 20) return null
                    if (end < 0) end = o + 2
                    o = ((len and 0x3f) shl 8) or (b[o + 1].toInt() and 0xff)
                }
                else -> {
                    if (o + 1 + len > b.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(b, o + 1, len, Charsets.US_ASCII))
                    o += 1 + len
                }
            }
        }
        return sb.toString().lowercase() to end
    }

    private fun typeName(t: Int) = when (t) {
        1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; 65 -> "HTTPS"; 64 -> "SVCB"; 16 -> "TXT"; 12 -> "PTR"; 33 -> "SRV"
        else -> "TYPE$t"
    }

    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += u16(b, i); i += 2 }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }
}
