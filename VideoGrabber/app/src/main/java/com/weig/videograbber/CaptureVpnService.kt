package com.weig.videograbber

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统级流量嗅探：本地 VPN 透明转发 + 媒体直链提取。
 *
 * 工作原理：
 * 1. 建立本地 VPN（路由 0.0.0.0/0），设备所有 TCP/UDP 流量进入本服务。
 * 2. 对每个 TCP 连接，本服务作为「中间人」用真实 Socket 连到目标服务器并双向转发，
 *    因此能读到明文 HTTP 请求里的 GET 路径与 Host，拼出完整媒体直链。
 * 3. 命中视频直链时，直接交给 [DownloadService] 下载【源文件】（非录屏）。
 * 4. UDP 53 走 DNS 转发，保证被嗅探的 App 仍能解析域名。
 *
 * 重要限制（务必知悉）：
 * - HTTPS（443）内容是加密的，本服务【拿不到完整直链】，只能解析 ClientHello 里的
 *   SNI 域名（仅日志）。证书绑定（cert pinning）的 App（抖音/快手等）无法提取直链。
 * - 因此本模块对「明文 HTTP 视频」最有效；对于 HTTPS 视频，请用内置浏览器嗅探（WebView 自己完成 TLS）。
 * - 透明 TCP 转发涉及自实现序列号/校验和，需在真机迭代验证。
 */
class CaptureVpnService : VpnService() {

    private val tag = "CaptureVpn"
    private val MTU = 1500
    private val TCP = 6
    private val UDP = 17

    private var running = false
    private var pfd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private val conns = ConcurrentHashMap<String, TcpConn>()
    private val rand = Random()

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotify("流量嗅探运行中"))
        try {
            startVpn()
        } catch (e: Exception) {
            Log.e(tag, "vpn establish failed: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotify(text: String): android.app.Notification {
        val id = "videograbber_capture"
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                android.app.NotificationChannel(id, "流量嗅探", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        return androidx.core.app.NotificationCompat.Builder(this, id)
            .setContentTitle("视频源下载器 · 嗅探")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(MTU)
            .setSession("VideoGrabber 嗅探")
        // 排除自身，避免 DownloadService 的下载流量被再次抓回造成死循环
        builder.addDisallowedApplication(packageName)
        pfd = builder.establish() ?: run { stopSelf(); return }
        running = true
        tunOut = FileOutputStream(pfd!!.fileDescriptor)
        Thread { readLoop() }.start()
    }

    private fun readLoop() {
        val `in` = FileInputStream(pfd!!.fileDescriptor)
        val buf = ByteArray(MTU)
        while (running) {
            val n = try { `in`.read(buf) } catch (e: Exception) { -1 }
            if (n <= 0) { if (n < 0) break; continue }
            val pkt = buf.copyOf(n)
            try { dispatch(pkt) } catch (e: Exception) { Log.w(tag, "dispatch err: ${e.message}") }
        }
    }

    private fun dispatch(pkt: ByteArray) {
        if ((pkt[0].toInt() ushr 4 and 0x0f) != 4) return
        val ihl = (pkt[0].toInt() and 0x0f) * 4
        val totalLen = (pkt[2].toInt() and 0xff shl 8) or (pkt[3].toInt() and 0xff)
        val proto = pkt[9].toInt() and 0xff
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        when (proto) {
            TCP -> handleTcp(pkt, ihl, totalLen, srcIp, dstIp)
            UDP -> handleDns(pkt, ihl, totalLen, srcIp, dstIp)
        }
    }

    // -------------------- TCP 透明转发 --------------------

    private fun handleTcp(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        var off = ihl
        val srcPort = readShort(pkt, off)
        val dstPort = readShort(pkt, off + 2)
        val seq = readInt(pkt, off + 4).toLong() and 0xffffffffL
        val ack = readInt(pkt, off + 8).toLong() and 0xffffffffL
        val dataOff = ((pkt[off + 12].toInt() and 0xf0) ushr 4) * 4
        val flags = pkt[off + 13].toInt() and 0xff
        val payloadLen = totalLen - ihl - dataOff
        val payload = if (payloadLen > 0) pkt.copyOfRange(ihl + dataOff, totalLen) else ByteArray(0)

        val key = connKey(srcIp, srcPort, dstIp, dstPort)
        var conn = conns[key]

        if ((flags and SYN) != 0 && conn == null) {
            conn = TcpConn(srcIp, srcPort, dstIp, dstPort)
            conns[key] = conn
            val clientSeq0 = seq
            // 在独立线程里连真实服务器（protect 让 Socket 绕过 VPN，避免回环），
            // 连上后再回读线程发 SYN-ACK，避免阻塞抓包主循环。
            Thread {
                try {
                    val s = Socket()
                    protect(s)
                    s.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10000)
                    conn.socket = s
                    conn.serverIss = (rand.nextInt().toLong() and 0x7fffffffL)
                    conn.clientNextSeq = (clientSeq0 + 1) and 0xffffffffL
                    sendTcp(conn, conn.serverIss, conn.clientNextSeq, SYN or ACK, null)
                    conn.serverSeq = (conn.serverIss + 1) and 0xffffffffL
                    startSocketReader(conn, key)
                } catch (e: Exception) {
                    Log.w(tag, "connect failed: ${e.message}")
                    conns.remove(key)
                    sendTcp(conn, 0, (clientSeq0 + 1) and 0xffffffffL, RST or ACK, null)
                }
            }.start()
            return
        }
        if (conn == null) return

        if ((flags and RST) != 0) { cleanup(key, conn); return }
        if ((flags and FIN) != 0) {
            try { conn.socket?.shutdownOutput() } catch (_: Exception) {}
            sendTcp(conn, conn.serverSeq, (seq + 1) and 0xffffffffL, FIN or ACK, null)
            return
        }
        if (payloadLen > 0) {
            try { conn.socket?.getOutputStream()?.write(payload) } catch (e: Exception) { cleanup(key, conn); return }
            conn.clientNextSeq = (seq + payloadLen) and 0xffffffffL
            sendTcp(conn, conn.serverSeq, conn.clientNextSeq, PSH or ACK, null)
            if (dstPort == 80) tryParseHttpRequest(conn, payload)
            else if (dstPort == 443) tryParseSni(payload)
        }
    }

    private fun startSocketReader(conn: TcpConn, key: String) {
        Thread {
            try {
                val input = conn.socket!!.getInputStream()
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    if (n <= 0) continue
                    sendTcp(conn, conn.serverSeq, conn.clientNextSeq, PSH or ACK, buf.copyOf(n))
                    conn.serverSeq = (conn.serverSeq + n) and 0xffffffffL
                }
                sendTcp(conn, conn.serverSeq, conn.clientNextSeq, FIN or ACK, null)
            } catch (e: Exception) {
                sendTcp(conn, conn.serverSeq, conn.clientNextSeq, RST or ACK, null)
            } finally {
                cleanup(key, conn)
            }
        }.start()
    }

    private fun tryParseHttpRequest(conn: TcpConn, payload: ByteArray) {
        if (conn.httpParsed) return
        conn.reqBuf.write(payload)
        val data = conn.reqBuf.toByteArray()
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("\r\n\r\n")) return
        conn.httpParsed = true
        val lines = text.lines()
        val reqLine = lines.firstOrNull { it.startsWith("GET ") || it.startsWith("POST ") }
        val host = lines.firstOrNull { it.startsWith("Host:", true) }?.substringAfter(":")?.trim()
        if (reqLine != null && host != null) {
            val uri = reqLine.substringAfter(" ").substringBefore(" ")
            val full = if (uri.startsWith("http")) uri else "http://$host$uri"
            if (MediaDetector.isMediaUrl(full)) {
                Log.i(tag, "捕获媒体直链: $full")
                SniffRepository.add(SniffItem(full, SniffItem.Type.MEDIA_URL))
                DownloadService.start(applicationContext, full, null, DownloadItem.SourceType.CAPTURE)
            }
        }
    }

    /**
     * 从 TLS ClientHello 中提取 SNI 域名（HTTPS 流量唯一能在不解密情况下看到的信息）。
     * 证书绑定（cert pinning）的 App 无法得到完整直链，但至少能暴露"正在跟哪个视频域名通信"，
     * 对第三方独立 App 的排查很有用。仅解析首包（以 0x16 开头）的 ClientHello。
     */
    private fun tryParseSni(payload: ByteArray) {
        if (payload.size < 5) return
        if ((payload[0].toInt() and 0xff) != 0x16) return            // TLS handshake record
        var p = 5                                                     // 跳过 record header
        if (p >= payload.size) return
        if ((payload[p].toInt() and 0xff) != 0x01) return            // ClientHello
        p += 4                                                       // 跳过 handshake 类型+长度
        p += 2                                                       // version
        p += 32                                                      // random
        if (p >= payload.size) return
        val sidLen = payload[p].toInt() and 0xff; p += 1 + sidLen
        if (p + 2 > payload.size) return
        val csLen = readShort(payload, p); p += 2 + csLen
        if (p >= payload.size) return
        val cmLen = payload[p].toInt() and 0xff; p += 1 + cmLen
        if (p + 2 > payload.size) return
        val extTotal = readShort(payload, p); p += 2
        val extEnd = p + extTotal
        while (p + 4 <= extEnd && p + 4 <= payload.size) {
            val extType = readShort(payload, p)
            val extLen = readShort(payload, p + 2)
            p += 4
            if (extType == 0x0000) {                                 // SNI 扩展
                if (p + 2 > payload.size) return
                val listLen = readShort(payload, p); p += 2
                val entryEnd = p + listLen
                if (p >= entryEnd) return
                val nameType = payload[p].toInt() and 0xff; p += 1
                if (nameType != 0) return
                if (p + 2 > payload.size) return
                val nameLen = readShort(payload, p); p += 2
                if (p + nameLen > payload.size) return
                val domain = String(payload.copyOfRange(p, p + nameLen), Charsets.UTF_8)
                if (domain.isNotEmpty()) {
                    Log.i(tag, "嗅探到 HTTPS 域名: $domain")
                    SniffRepository.add(SniffItem(domain, SniffItem.Type.HTTPS_DOMAIN))
                }
                return
            }
            p += extLen
        }
    }

    private fun sendTcp(conn: TcpConn, seq: Long, ack: Long, flags: Int, payload: ByteArray?) {
        val pkt = buildTcp(conn.serverIp, conn.clientIp, conn.serverPort, conn.clientPort, seq, ack, flags, payload)
        writeTun(pkt)
    }

    private fun buildTcp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
                         seq: Long, ack: Long, flags: Int, payload: ByteArray?): ByteArray {
        val plen = payload?.size ?: 0
        val tcpLen = 20 + plen
        val totalLen = 20 + tcpLen
        val pkt = ByteArray(totalLen)
        // IP 头
        pkt[0] = 0x45
        writeShort(pkt, 2, totalLen)
        pkt[8] = 64 // TTL
        pkt[9] = TCP.toByte()
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        writeShort(pkt, 10, ipChecksum(pkt.copyOfRange(0, 20)))
        // TCP 头
        var o = 20
        writeShort(pkt, o, srcPort); writeShort(pkt, o + 2, dstPort)
        writeInt(pkt, o + 4, seq.toInt()); writeInt(pkt, o + 8, ack.toInt())
        pkt[o + 12] = 0x50 // data offset 5
        pkt[o + 13] = flags.toByte()
        writeShort(pkt, o + 14, 0x4000) // window
        if (payload != null) System.arraycopy(payload, 0, pkt, 40, plen)
        val tcpSeg = pkt.copyOfRange(20, totalLen)
        writeShort(pkt, o + 16, tcpChecksum(srcIp, dstIp, tcpSeg))
        return pkt
    }

    // -------------------- UDP / DNS 转发 --------------------

    private fun handleDns(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val off = ihl
        val srcPort = readShort(pkt, off)
        val dstPort = readShort(pkt, off + 2)
        val udpLen = readShort(pkt, off + 4)
        if (dstPort != 53) return // 仅处理 DNS
        val payload = pkt.copyOfRange(ihl + 8, ihl + 8 + (udpLen - 8).coerceAtLeast(0))
        Thread {
            try {
                val ds = DatagramSocket()
                protect(ds)
                ds.soTimeout = 5000
                ds.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), 53))
                val resp = ByteArray(1024)
                val rp = DatagramPacket(resp, resp.size)
                ds.receive(rp)
                ds.close()
                val out = resp.copyOf(rp.length)
                writeTun(buildUdp(dstIp, srcIp, 53, srcPort, out))
            } catch (e: Exception) {
                Log.w(tag, "dns relay failed: ${e.message}")
            }
        }.start()
    }

    private fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        writeShort(pkt, 2, total)
        pkt[8] = 64
        pkt[9] = UDP.toByte()
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        writeShort(pkt, 10, ipChecksum(pkt.copyOfRange(0, 20)))
        writeShort(pkt, 20, srcPort); writeShort(pkt, 22, dstPort)
        writeShort(pkt, 24, 8 + payload.size)
        writeShort(pkt, 26, 0) // UDP 校验和置 0（允许）
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return pkt
    }

    // -------------------- 工具 --------------------

    private fun writeTun(pkt: ByteArray) {
        try { synchronized(this) { tunOut?.write(pkt) } } catch (e: Exception) { Log.w(tag, "tun write err") }
    }

    private fun cleanup(key: String, conn: TcpConn) {
        conns.remove(key)
        try { conn.socket?.close() } catch (_: Exception) {}
    }

    private fun connKey(ip: ByteArray, port: Int, ip2: ByteArray, port2: Int): String =
        "${ip.contentToString()}:$port->${ip2.contentToString()}:$port2"

    private fun readShort(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun readInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    private fun writeShort(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun ipChecksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < header.size) {
            sum += ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, seg: ByteArray): Int {
        val pseudo = ByteArray(12)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0; pseudo[9] = TCP.toByte()
        pseudo[10] = (seg.size ushr 8).toByte(); pseudo[11] = seg.size.toByte()
        val total = ByteArray(pseudo.size + seg.size + if (seg.size % 2 == 1) 1 else 0)
        System.arraycopy(pseudo, 0, total, 0, pseudo.size)
        System.arraycopy(seg, 0, total, pseudo.size, seg.size)
        var sum = 0
        var i = 0
        while (i < total.size - 1) {
            sum += ((total[i].toInt() and 0xff) shl 8) or (total[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    override fun onDestroy() {
        running = false
        conns.values.forEach { try { it.socket?.close() } catch (_: Exception) {} }
        conns.clear()
        try { pfd?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private class TcpConn(
        val clientIp: ByteArray, val clientPort: Int,
        val serverIp: ByteArray, val serverPort: Int
    ) {
        var socket: Socket? = null
        var serverIss: Long = 0
        var serverSeq: Long = 0
        var clientNextSeq: Long = 0
        var httpParsed = false
        val reqBuf = ByteArrayOutputStream()
    }

    companion object {
        const val NOTIFY_ID = 2002
        const val SYN = 0x02
        const val ACK = 0x10
        const val PSH = 0x08
        const val FIN = 0x01
        const val RST = 0x04
    }
}
