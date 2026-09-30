package com.weig.videograbber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ExecuteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * 原始视频下载服务。
 *
 * 关键设计：
 * 1. 下载的是「源文件字节」，不是录屏、不是重新编码 —— 这是与录屏方案的本质区别。
 * 2. 支持两类源：
 *    - 渐进式直链（.mp4/.webm/...）：直接流式写入原始字节。
 *    - HLS（.m3u8）：交由 ffmpeg-kit 拉取并以 `-c copy` 零重编码转封装为 mp4（保留原始画质，
 *      支持未加密与 AES-128 加密分片），进度按 m3u8 总时长估算。
 * 3. 全程通过 [DownloadRepository] 推送状态，UI 据此显示「排队/转封装中 %/写入相册/完成」。
 * 4. 完成后调用 [MediaSaver] 写入系统相册。
 */
class DownloadService : Service() {

    private val executor = Executors.newFixedThreadPool(2)
    private val tag = "DownloadService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFY_ID, buildNotification("视频下载器运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_DOWNLOAD) {
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val fileName = intent.getStringExtra(EXTRA_NAME)
            ?: MediaDetector.fileNameFromUrl(url)
        val source = DownloadItem.SourceType.valueOf(
            intent.getStringExtra(EXTRA_SOURCE) ?: "MANUAL"
        )
        val cookies = intent.getStringExtra(EXTRA_COOKIES)

        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = fileName,
            source = source,
            cookies = cookies
        )
        DownloadRepository.add(item)
        executor.submit { runDownload(item) }
        return START_NOT_STICKY
    }

    private fun runDownload(item: DownloadItem) {
        try {
            val dir = File(getExternalFilesDir("downloads")
                ?: cacheDir, "videograbber").apply { mkdirs() }
            val isHls = item.url.substringBefore('?').endsWith(".m3u8", true)

            item.status = DownloadItem.Status.DOWNLOADING
            DownloadRepository.update(item)

            val outFile = if (isHls) {
                downloadHls(item, dir)
            } else {
                downloadProgressive(item, dir)
            }

            item.status = DownloadItem.Status.SAVING
            item.info = "写入相册"
            item.filePath = outFile.absolutePath
            DownloadRepository.update(item)

            val mime = MediaSaver.guessMime(outFile.name)
            val uri = MediaSaver.save(this, outFile, outFile.name, mime)
            if (uri != null) {
                item.galleryUri = uri.toString()
                item.status = DownloadItem.Status.COMPLETED
                item.progress = 100
                // 相册已留存副本，删除 App 私有暂存以省空间
                outFile.delete()
            } else {
                item.status = DownloadItem.Status.FAILED
                item.error = "写入相册失败"
            }
        } catch (e: Exception) {
            Log.e(tag, "download failed: ${e.message}", e)
            item.status = DownloadItem.Status.FAILED
            item.error = e.message ?: "未知错误"
        } finally {
            DownloadRepository.update(item)
        }
    }

    private fun downloadProgressive(item: DownloadItem, dir: File): File {
        val out = File(dir, sanitize(item.fileName))
        val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            item.cookies?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VideoGrabber/1.0")
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1
        item.totalBytes = total
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var soFar = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    soFar += read
                    item.bytesDownloaded = soFar
                    if (total > 0) item.progress = (soFar * 100 / total).toInt()
                    DownloadRepository.update(item)
                }
            }
        }
        conn.disconnect()
        return out
    }

    /**
     * 下载 HLS（.m3u8）源文件并转封装为 mp4。
     *
     * 用 ffmpeg-kit 直接拉取 m3u8（支持未加密与 AES-128 加密分片），
     * 通过 `-c copy` 流复制，**零重编码、完全保留原始画质**，仅做容器转换（ts→mp4）。
     * 进度依据 m3u8 总时长（EXTINF 之和）与 ffmpeg 统计的已处理时间估算。
     */
    private fun downloadHls(item: DownloadItem, dir: File): File {
        val out = File(dir, sanitize(item.fileName).replaceSuffixToMp4())
        val totalDur = parseHlsDuration(item.url, item.cookies)
        val cookies = item.cookies
        val cmd = buildString {
            append("ffmpeg")
            if (!cookies.isNullOrBlank()) append(" -headers \"Cookie: $cookies\"")
            append(" -i \"${item.url}\"")
            append(" -c copy")
            append(" -movflags +faststart")
            append(" -y")
            append(" \"${out.absolutePath}\"")
        }
        item.status = DownloadItem.Status.DOWNLOADING
        item.info = "转封装中"
        DownloadRepository.update(item)

        val session = FFmpegKit.executeAsync(cmd,
            ExecuteCallback { },
            LogCallback { },
            StatisticsCallback { stats ->
                if (totalDur != null && totalDur > 0 && stats.time > 0) {
                    val pct = (stats.time / 1_000_000.0 / totalDur * 100).toInt().coerceIn(0, 100)
                    item.progress = pct
                    item.bytesDownloaded = stats.size
                    DownloadRepository.update(item)
                }
            })
        session.waitFor()
        val rc = session.returnCode
        if (!ReturnCode.isSuccess(rc)) {
            out.delete()
            val lastLog = session.allLogsAsString
                ?.takeIf { it.isNotBlank() }
                ?.lines()?.takeLast(8)?.joinToString("\n") ?: ""
            throw IllegalStateException("ffmpeg 转封装失败(rc=$rc)\n$lastLog")
        }
        item.progress = 100
        return out
    }

    /** 解析 m3u8 总时长（EXTINF 累加，递归跟随 #EXT-X-STREAM-INF 变体），用于进度估算。 */
    private fun parseHlsDuration(playlistUrl: String, cookies: String?, depth: Int = 0): Double? {
        if (depth > 4) return null
        val content = fetchText(playlistUrl, cookies)
        val lines = content.lines().map { it.trim() }
        var pendingVariant = false
        var variantUrl: String? = null
        var total = 0.0
        var hasSegments = false
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) { pendingVariant = true; continue }
            if (line.startsWith("#EXTINF")) {
                val m = Regex("""#EXTINF:([\d.]+)""").find(line)
                if (m != null) { total += m.groupValues[1].toDoubleOrNull() ?: 0.0; hasSegments = true }
                continue
            }
            if (line.startsWith("#")) { pendingVariant = false; continue }
            if (pendingVariant) { variantUrl = resolve(line, playlistUrl); pendingVariant = false }
        }
        return if (variantUrl != null) {
            parseHlsDuration(variantUrl, cookies, depth + 1) ?: if (hasSegments) total else null
        } else if (hasSegments) total else null
    }

    private fun fetchText(url: String, cookies: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            cookies?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VideoGrabber/1.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun resolve(relative: String, base: String): String {
        return if (relative.startsWith("http")) relative else {
            val u = URL(URL(base), relative)
            u.toString()
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if (it.length > 120) it.take(120) else it }

    private fun String.replaceSuffixToMp4(): String {
        val lower = this.lowercase()
        return when {
            lower.endsWith(".m3u8") -> this.replaceRange(length - 4, length, "mp4")
            lower.endsWith(".ts") -> this.replaceRange(length - 2, length, "mp4")
            lower.endsWith(".mp4") -> this
            else -> "$this.mp4"
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "videograbber_dl"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "视频下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("视频源下载器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.weig.videograbber.action.DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_COOKIES = "extra_cookies"
        const val NOTIFY_ID = 2001

        fun start(context: Context, url: String, name: String?, source: DownloadItem.SourceType, cookies: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SOURCE, source.name)
                putExtra(EXTRA_COOKIES, cookies)
            }
            context.startForegroundService(intent)
        }
    }
}
