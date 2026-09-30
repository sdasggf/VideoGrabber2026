package com.weig.videograbber

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跨组件（Service / Fragment）共享的下载任务状态中心。
 * 同进程内用单例 + LiveData 即可，无需广播或数据库。
 */
object DownloadRepository {

    private val _items = MutableLiveData<List<DownloadItem>>(emptyList())
    val items: LiveData<List<DownloadItem>> = _items

    private val list = CopyOnWriteArrayList<DownloadItem>()

    fun add(item: DownloadItem) {
        list.add(item)
        publish()
    }

    /** 按 id 取出可变对象（在 Service 内部更新后调用 [publish] 推送给 UI）。 */
    fun get(id: String): DownloadItem? = list.firstOrNull { it.id == id }

    fun update(item: DownloadItem) = publish()

    fun publish() {
        _items.postValue(ArrayList(list))
    }
}
