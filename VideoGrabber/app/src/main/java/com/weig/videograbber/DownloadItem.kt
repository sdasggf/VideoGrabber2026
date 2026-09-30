package com.weig.videograbber

/**
 * 一次下载任务的领域模型。
 * 注意：data class 用于展示快照；运行时状态变化通过 [DownloadRepository] 的 LiveData 推送。
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val source: SourceType,        // 来源：浏览器嗅探 / 系统嗅探 / 手动输入
    var status: Status = Status.QUEUED,
    var progress: Int = 0,         // 0..100
    var bytesDownloaded: Long = 0,
    var totalBytes: Long = 0,
    var filePath: String? = null,  // 暂存目录里的成品文件路径
    var galleryUri: String? = null,// 存相册后的 MediaStore Uri
    var error: String? = null,
    var info: String? = null,       // 阶段性文案，如「转封装中」「写入相册」
    val cookies: String? = null    // WebView 来源时带入，用于通过鉴权
) {
    enum class SourceType { BROWSER, CAPTURE, MANUAL }
    enum class Status { QUEUED, DOWNLOADING, MERGING, SAVING, COMPLETED, FAILED }
}
