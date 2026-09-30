package com.weig.videograbber

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 把下载完成的原始视频写入系统相册（公共视频目录），返回 MediaStore Uri。
 *
 * API 29+ 走分区存储：用 RELATIVE_PATH 放到 Movies/VideoGrabber，
 * 通过 IS_PENDING 标记写入完成，全程无需存储权限（写入的是本应用产生的文件）。
 * API <29 退化到直接写外部存储（已在 Manifest 中以 maxSdkVersion 申请）。
 */
object MediaSaver {

    fun save(context: Context, file: File, displayName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoGrabber")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            // 写入失败清理占位
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun guessMime(name: String): String = when {
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".m3u8", true) -> "application/vnd.apple.mpegurl"
        name.endsWith(".ts", true) -> "video/mp2t"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mov", true) -> "video/quicktime"
        else -> "video/mp4"
    }
}
