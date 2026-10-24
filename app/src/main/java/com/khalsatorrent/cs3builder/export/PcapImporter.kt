package com.khalsatorrent.cs3builder.export

import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.util.UUID

/**
 * Parses a `.pcap` / `.pcapng` capture (e.g. from PCAPdroid with TLS decryption OFF — no certificate,
 * per-app, so banking apps are never involved) into [NetworkLog]s.
 *
 * Without TLS decryption the bytes of HTTPS are encrypted, so this extracts what is visible in the
 * clear: the destination host of each HTTPS connection (from the TLS ClientHello SNI), full plaintext
 * HTTP requests/responses, and DNS lookups. That is enough to learn an app's API endpoints; capture
 * those hosts in the in-app browser to get the response bodies.
 */
object PcapImporter {

    class Result(val logs: List<NetworkLog>, val format: String)

    fun isPcap(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val m = be32(bytes, 0)
        return m == 0xa1b2c3d4.toInt() || m == 0xd4c3b2a1.toInt() ||    // pcap (us / swapped)
            m == 0xa1b23c4d.toInt() || m == 0x4d3cb2a1.toInt() ||        // pcap (ns / swapped)
            m == 0x0a0d0d0a                                             // pcapng SHB
    }

    fun parse(bytes: ByteArray): Result {
        val packets = when {
            be32(bytes, 0) == 0x0a0d0d0a -> parsePcapng(bytes)
            else -> parseClassic(bytes)
        }
        val logs = ArrayList<NetworkLog>()
        val seenHttps = HashSet<String>()   // one entry per (host -> dstIp) HTTPS connection
        val seenDns = HashSet<String>()
        for (p in packets) {
            val frame = if (p.offset == 0 && p.length == p.data.size) p.data
                else p.data.copyOfRange(p.offset, minOf(p.offset + p.length, p.data.size))
            val ip = parseIp(frame, p.linkType) ?: continue
            when (ip.protocol) {
                6 -> {
                    val tcp = parseTcp(ip) ?: continue
                    if (tcp.payload.isEmpty()) continue
                    val sni = tlsSni(tcp.payload)
                    if (sni != null) {
                        val key = "$sni|${ip.dst}"
                        if (seenHttps.add(key)) logs += httpsLog(sni, ip, tcp, p.timeMs)
                        continue
                    }
                    val http = parseHttp(tcp.payload, ip, tcp)
                    if (http != null) logs += http.copy(timestamp = p.timeMs)
                }
                17 -> {
                    val udp = ip.payload
                    if (udp.size < 8) continue
                    ip.srcPort = ((udp[0].toInt() and 0xff) shl 8) or (udp[1].toInt() and 0xff)
                    ip.dstPort = ((udp[2].toInt() and 0xff) shl 8) or (udp[3].toInt() and 0xff)
                    if (ip.dstPort == 53) {
                        val q = dnsQuery(udp.copyOfRange(8, udp.size)) ?: continue
                        if (seenDns.add(q)) logs += dnsLog(q, ip, p.timeMs)
                    }
                }
            }
        }
        return Result(logs, if (be32(bytes, 0) == 0x0a0d0d0a) "PCAPNG" else "PCAP")
    }

    // ---------------------------------------------------------------- container formats

    private class Pkt(val data: ByteArray, val offset: Int, val length: Int, val linkType: Int, val timeMs: Long) {
        inline fun u8(i: Int) = data[offset + i].toInt() and 0xff
    }

    private fun parseClassic(b: ByteArray): List<Pkt> {
        val magic = be32(b, 0)
        val little = magic == 0xd4c3b2a1.toInt() || magic == 0x4d3cb2a1.toInt()
        val nano = magic == 0xa1b23c4d.toInt() || magic == 0x4d3cb2a1.toInt()
        val linkType = u32(b, 20, little)
        val out = ArrayList<Pkt>()
        var o = 24
        while (o + 16 <= b.size) {
            val tsSec = u32(b, o, little).toLong() and 0xffffffffL
            val tsFrac = u32(b, o + 4, little).toLong() and 0xffffffffL
            val inclLen = u32(b, o + 8, little)
            o += 16
            if (inclLen < 0 || o + inclLen > b.size) break
            val ms = tsSec * 1000 + (if (nano) tsFrac / 1_000_000 else tsFrac / 1000)
            out += Pkt(b, o, inclLen, linkType, if (tsSec > 0) ms else System.currentTimeMillis())
            o += inclLen
        }
        return out
    }

    private fun parsePcapng(b: ByteArray): List<Pkt> {
        val out = ArrayList<Pkt>()
        val linkTypes = HashMap<Int, Int>()   // interface id -> linktype
        var ifaceCount = 0
        var o = 0
        // Section Header Block declares byte order via the 0x1A2B3C4D magic
        var little = false
        while (o + 12 <= b.size) {
            // The SHB block type (0x0A0D0D0A) is endian-independent; it carries the byte order for
            // every following block, whose type must then be read with that endianness.
            val type = u32(b, o, little)
            if (type == 0x0a0d0d0a) little = (be32(b, o + 8) == 0x4d3c2b1a)
            val total = u32(b, o + 4, little)
            if (total < 12 || o + total > b.size) break
            when (type) {
                0x00000001 -> {  // Interface Description Block
                    linkTypes[ifaceCount++] = u16(b, o + 8, little)
                }
                0x00000006 -> {  // Enhanced Packet Block
                    val ifaceId = u32(b, o + 8, little)
                    val tsHigh = u32(b, o + 12, little).toLong() and 0xffffffffL
                    val tsLow = u32(b, o + 16, little).toLong() and 0xffffffffL
                    val capLen = u32(b, o + 20, little)
                    val dataOff = o + 28
                    if (capLen in 0..(total - 28) && dataOff + capLen <= b.size) {
                        val ts = (tsHigh shl 32) or tsLow   // units are usually microseconds
                        out += Pkt(b, dataOff, capLen, linkTypes[ifaceId] ?: 1, if (ts > 0) ts / 1000 else System.currentTimeMillis())
                    }
                }
                0x00000003 -> {  // Simple Packet Block
                    val capLen = u32(b, o + 12, little)
                    val dataOff = o + 12
                    if (capLen in 0..(total - 16) && dataOff + capLen <= b.size)
                        out += Pkt(b, dataOff, capLen, linkTypes[0] ?: 1, System.currentTimeMillis())
                }
            }
            o += total
        }
        return out
    }

    // ---------------------------------------------------------------- link + IP

    private class IpInfo(
        val protocol: Int, val src: String, val dst: String,
        val data: ByteArray, val l4Offset: Int, val l4Length: Int
    ) {
        var srcPort = 0; var dstPort = 0
        val payload: ByteArray get() = data.copyOfRange(l4Offset, l4Offset + l4Length)
    }

    /** Strips the link-layer header, then parses the IPv4/IPv6 header. */
    private fun parseIp(data: ByteArray, linkType: Int): IpInfo? {
        val start = when (linkType) {
            1 -> if (data.size >= 14) 14 else return null                       // Ethernet
            113 -> if (data.size >= 16) 16 else return null                     // Linux SLL
            276 -> if (data.size >= 20) 20 else return null                     // Linux SLL2
            101, 12, 14 -> 0                                                    // raw IP
            else -> 0
        }
        if (start >= data.size) return null
        return when (data[start].toInt() and 0xf0) {
            0x40 -> parseIpv4(data, start)
            0x60 -> parseIpv6(data, start)
            else -> if (linkType == 1) {   // mis-guessed ethertype? try raw
                when (data[0].toInt() and 0xf0) { 0x40 -> parseIpv4(data, 0); 0x60 -> parseIpv6(data, 0); else -> null }
            } else null
        }
    }

    private fun parseIpv4(b: ByteArray, o: Int): IpInfo? {
        if (o + 20 > b.size) return null
        val ihl = (b[o].toInt() and 0x0f) * 4
        val total = u16(b, o + 2, false)
        val proto = b[o + 9].toInt() and 0xff
        val src = "${b[o+12].toInt() and 0xff}.${b[o+13].toInt() and 0xff}.${b[o+14].toInt() and 0xff}.${b[o+15].toInt() and 0xff}"
        val dst = "${b[o+16].toInt() and 0xff}.${b[o+17].toInt() and 0xff}.${b[o+18].toInt() and 0xff}.${b[o+19].toInt() and 0xff}"
        val l4 = o + ihl
        val end = (if (total in (ihl + 1)..(b.size - o)) o + total else b.size)
        if (l4 > end) return null
        return IpInfo(proto, src, dst, b, l4, end - l4)
    }

    private fun parseIpv6(b: ByteArray, o: Int): IpInfo? {
        if (o + 40 > b.size) return null
        val proto = b[o + 6].toInt() and 0xff           // next header (extension headers not followed)
        val payLen = u16(b, o + 4, false)
        fun hex(i: Int) = ((b[o+i].toInt() and 0xff) shl 8 or (b[o+i+1].toInt() and 0xff))
        val src = (0..7).joinToString(":") { Integer.toHexString(hex(8 + it*2)) }
        val dst = (0..7).joinToString(":") { Integer.toHexString(hex(24 + it*2)) }
        val l4 = o + 40
        val end = (if (payLen in 1..(b.size - l4)) l4 + payLen else b.size)
        return IpInfo(proto, src, dst, b, l4, end - l4)
    }

    private class TcpInfo(val payload: ByteArray)

    private fun parseTcp(ip: IpInfo): TcpInfo? {
        val b = ip.data; val o = ip.l4Offset
        if (o + 20 > b.size) return null
        ip.srcPort = u16(b, o, false); ip.dstPort = u16(b, o + 2, false)
        val dataOff = ((b[o + 12].toInt() and 0xf0) ushr 4) * 4
        val payStart = o + dataOff
        val payEnd = o + ip.l4Length
        if (dataOff < 20 || payStart > payEnd || payStart > b.size) return null
        return TcpInfo(b.copyOfRange(payStart, minOf(payEnd, b.size)))
    }

    // ---------------------------------------------------------------- app protocols

    /** Extracts the SNI host from a TLS ClientHello record, or null. */
    private fun tlsSni(p: ByteArray): String? {
        try {
            if (p.size < 43) return null
            if (p[0].toInt() != 0x16) return null                 // handshake
            if (p[5].toInt() != 0x01) return null                 // ClientHello
            var o = 43                                            // record(5)+hs(4)+ver(2)+random(32)
            if (o > p.size) return null
            val sidLen = p[o].toInt() and 0xff; o += 1 + sidLen
            if (o + 2 > p.size) return null
            val csLen = u16(p, o, false); o += 2 + csLen
            if (o + 1 > p.size) return null
            val compLen = p[o].toInt() and 0xff; o += 1 + compLen
            if (o + 2 > p.size) return null
            val extTotal = u16(p, o, false); o += 2
            val extEnd = minOf(o + extTotal, p.size)
            while (o + 4 <= extEnd) {
                val type = u16(p, o, false); val len = u16(p, o + 2, false); o += 4
                if (type == 0x0000) {                              // server_name
                    if (o + 5 > p.size) return null
                    val nameLen = u16(p, o + 3, false)
                    val s = o + 5
                    if (s + nameLen <= p.size) return String(p, s, nameLen, Charsets.US_ASCII).takeIf { it.isNotBlank() }
                    return null
                }
                o += len
            }
        } catch (_: Exception) {}
        return null
    }

    private val HTTP_METHODS = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ")

    private fun parseHttp(p: ByteArray, ip: IpInfo, tcp: TcpInfo): NetworkLog? {
        if (p.size < 16) return null
        val head = String(p, 0, minOf(p.size, 4096), Charsets.ISO_8859_1)
        val isReq = HTTP_METHODS.any { head.startsWith(it) }
        val isResp = head.startsWith("HTTP/")
        if (!isReq && !isResp) return null
        val headerEnd = head.indexOf("\r\n\r\n").let { if (it < 0) head.length else it }
        val headerText = head.substring(0, headerEnd)
        val lines = headerText.split("\r\n")
        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            val i = line.indexOf(":"); if (i > 0) headers[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val body = if (headerEnd + 4 < head.length) head.substring(headerEnd + 4) else null
        return if (isReq) {
            val first = lines.first().split(" ")
            val method = first.getOrElse(0) { "GET" }
            val path = first.getOrElse(1) { "/" }
            val host = headers.entries.firstOrNull { it.key.equals("Host", true) }?.value ?: ip.dst
            NetworkLog(
                id = UUID.randomUUID().toString(), timestamp = 0, method = method,
                url = "http://$host$path", requestHeaders = headers, requestBody = body,
                responseCode = 0, responseHeaders = emptyMap(), responseBody = null, cookies = emptyList(),
                mimeType = null, isXHR = false, duration = 0, source = "pcap",
                kind = LogKind.classify("http://$host$path", null, headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value),
                pageUrl = null
            )
        } else {
            val code = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val ctype = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            NetworkLog(
                id = UUID.randomUUID().toString(), timestamp = 0, method = "GET",
                url = "http://${ip.src}:${ip.srcPort} (response)", requestHeaders = emptyMap(), requestBody = null,
                responseCode = code, responseHeaders = headers, responseBody = body, cookies = headers.filterKeys { it.equals("Set-Cookie", true) }.values.toList(),
                mimeType = ctype?.substringBefore(';')?.trim(), isXHR = false, duration = 0, source = "pcap",
                kind = LogKind.classify("http://${ip.src}", ctype), pageUrl = null
            )
        }
    }

    private fun httpsLog(sni: String, ip: IpInfo, tcp: TcpInfo, timeMs: Long) = NetworkLog(
        id = UUID.randomUUID().toString(), timestamp = timeMs, method = "CONNECT",
        url = "https://$sni", requestHeaders = linkedMapOf("Host" to sni, "X-Dest-IP" to "${ip.dst}:${ip.dstPort}"),
        requestBody = null, responseCode = 0, responseHeaders = emptyMap(),
        responseBody = "[HTTPS connection — encrypted. Open https://$sni in the browser tab to capture the response.]",
        cookies = emptyList(), mimeType = null, isXHR = false, duration = 0, source = "pcap", kind = LogKind.OTHER, pageUrl = null
    )

    private fun dnsQuery(p: ByteArray): String? {
        try {
            if (p.size < 13) return null
            var o = 12
            val sb = StringBuilder()
            while (o < p.size) {
                val len = p[o].toInt() and 0xff
                if (len == 0) break
                if (len and 0xc0 != 0) return null
                if (o + 1 + len > p.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(p, o + 1, len, Charsets.US_ASCII))
                o += 1 + len
            }
            return sb.toString().takeIf { it.contains('.') }
        } catch (_: Exception) { return null }
    }

    private fun dnsLog(host: String, ip: IpInfo, timeMs: Long) = NetworkLog(
        id = UUID.randomUUID().toString(), timestamp = timeMs, method = "DNS",
        url = "dns://$host", requestHeaders = emptyMap(), requestBody = null, responseCode = 0,
        responseHeaders = emptyMap(), responseBody = "[DNS lookup for $host]", cookies = emptyList(),
        mimeType = null, isXHR = false, duration = 0, source = "pcap", kind = LogKind.OTHER, pageUrl = null
    )

    // ---------------------------------------------------------------- byte helpers

    private fun be32(b: ByteArray, o: Int) =
        if (o + 4 > b.size) 0 else ((b[o].toInt() and 0xff) shl 24) or ((b[o+1].toInt() and 0xff) shl 16) or ((b[o+2].toInt() and 0xff) shl 8) or (b[o+3].toInt() and 0xff)

    private fun u32(b: ByteArray, o: Int, little: Boolean): Int {
        if (o + 4 > b.size) return 0
        return if (little) (b[o].toInt() and 0xff) or ((b[o+1].toInt() and 0xff) shl 8) or ((b[o+2].toInt() and 0xff) shl 16) or ((b[o+3].toInt() and 0xff) shl 24)
        else ((b[o].toInt() and 0xff) shl 24) or ((b[o+1].toInt() and 0xff) shl 16) or ((b[o+2].toInt() and 0xff) shl 8) or (b[o+3].toInt() and 0xff)
    }

    /** u16: network byte order unless [little]. */
    private fun u16(b: ByteArray, o: Int, little: Boolean): Int {
        if (o + 2 > b.size) return 0
        return if (little) (b[o].toInt() and 0xff) or ((b[o+1].toInt() and 0xff) shl 8)
        else ((b[o].toInt() and 0xff) shl 8) or (b[o+1].toInt() and 0xff)
    }
}
