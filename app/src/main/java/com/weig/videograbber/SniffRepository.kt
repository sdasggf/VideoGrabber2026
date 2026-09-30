package com.weig.videograbber

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 嗅探发现中心：系统抓流（CaptureVpnService）捕获到的「媒体直链」与「HTTPS 域名」都在这里留痕，
 * 下载页据此展示，让用户看清"嗅探到底抓到了什么"。
 *
 * 两类发现：
 * - MEDIA_URL：明文 HTTP 下抠出的真实视频直链，可直接点下载【源文件】。
 * - HTTPS_DOMAIN：HTTPS 流量只能拿到 SNI 域名（证书绑定导致无法拿完整直链），
 *   仅作线索展示，提示"这个 App 正在跟某视频域名通信"。
 */
object SniffRepository {

    private val _items = MutableLiveData<List<SniffItem>>(emptyList())
    val items: LiveData<List<SniffItem>> = _items

    private val list = CopyOnWriteArrayList<SniffItem>()

    fun add(item: SniffItem) {
        // 去重：相同 value 不重复插入（保留最新时间）
        list.removeIf { it.value == item.value }
        list.add(0, item)
        while (list.size > 200) list.removeAt(list.lastIndex)
        publish()
    }

    fun publish() {
        _items.postValue(ArrayList(list))
    }
}

data class SniffItem(
    val value: String,
    val type: Type,
    val time: Long = System.currentTimeMillis()
) {
    enum class Type { MEDIA_URL, HTTPS_DOMAIN }
}
