package com.weig.videograbber

import android.util.Log

/**
 * 媒体地址判定：根据扩展名或路径特征识别视频源。
 * 仅做"是否可能是视频"的粗筛，真正能否下载由下载器在运行时判定。
 */
object MediaDetector {

    private val EXTENSIONS = listOf(
        ".mp4", ".webm", ".m3u8", ".m3u", ".ts", ".m4v",
        ".mov", ".avi", ".flv", ".mkv", ".3gp", ".mpd"
    )

    fun isMediaUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val u = rawUrl.lowercase()
        if (!u.startsWith("http")) return false
        val path = u.substringBefore('?').substringBefore('#')
        // 直链：路径以视频扩展名结尾
        if (EXTENSIONS.any { path.endsWith(it) }) return true
        // 常见视频资源路径特征（部分站点用动态名，但路径含关键字）
        if (u.contains("/video") || u.contains("media") || u.contains(".flv?") || u.contains(".mp4?")) return true
        return false
    }

    /** 由 URL 推断文件名；无法推断时回退默认名。 */
    fun fileNameFromUrl(rawUrl: String): String {
        val u = rawUrl.substringBefore('?').substringBefore('#')
        val tail = u.substringAfterLast('/')
        return if (tail.isNotBlank() && tail.contains('.')) tail else "video_${System.currentTimeMillis()}.mp4"
    }
}
