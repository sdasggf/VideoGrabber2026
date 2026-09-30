package com.weig.videograbber

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weig.videograbber.databinding.FragmentDownloadsBinding
import com.weig.videograbber.databinding.ItemDownloadBinding
import com.weig.videograbber.databinding.ItemSniffBinding
import com.weig.videograbber.SniffRepository
import com.weig.videograbber.SniffItem

/**
 * 下载页：提供三类「下载入口」与统一的状态反馈。
 * 1) 系统嗅探开关（CaptureVpnService）——抓取正在播放视频的流量直链。
 * 2) 手动直链输入框——任意 .mp4/.m3u8 地址直接下载（兜底入口）。
 * 列表实时显示 排队/下载中 %/合并/存相册/完成/失败。
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val adapter = DownloadAdapter()
    private var capturing = false

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) startCapture() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.downloadList.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadList.adapter = adapter

        binding.sniffList.layoutManager = LinearLayoutManager(requireContext())
        val sniffAdapter = SniffAdapter()
        binding.sniffList.adapter = sniffAdapter
        SniffRepository.items.observe(viewLifecycleOwner) { sniffAdapter.submit(it) }

        DownloadRepository.items.observe(viewLifecycleOwner) { adapter.submit(it) }

        binding.btnCapture.setOnClickListener {
            if (capturing) stopCapture() else requestCapture()
        }

        binding.btnDownload.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                DownloadService.start(requireContext(), url, null, DownloadItem.SourceType.MANUAL)
                binding.urlInput.text?.clear()
            }
        }
    }

    private fun requestCapture() {
        val prepare = VpnService.prepare(requireContext())
        if (prepare != null) vpnPermission.launch(prepare)
        else startCapture()
    }

    private fun startCapture() {
        requireContext().startForegroundService(Intent(requireContext(), CaptureVpnService::class.java))
        capturing = true
        binding.btnCapture.text = getString(R.string.action_stop_capture)
    }

    private fun stopCapture() {
        requireContext().stopService(Intent(requireContext(), CaptureVpnService::class.java))
        capturing = false
        binding.btnCapture.text = getString(R.string.action_start_capture)
    }

    private class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.VH>() {
        private var data = listOf<DownloadItem>()

        fun submit(items: List<DownloadItem>) {
            data = items.reversed() // 最新在上
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

        class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: DownloadItem) {
                b.tvName.text = item.fileName
                b.tvStatus.text = statusText(item)
                b.progress.progress = item.progress
                b.tvDetail.text = detailText(item)
            }

            private fun statusText(item: DownloadItem): String = when (item.status) {
                DownloadItem.Status.QUEUED -> "排队中"
                DownloadItem.Status.DOWNLOADING -> "${item.info ?: "下载中"} ${item.progress}%"
                DownloadItem.Status.MERGING -> "合并分片中"
                DownloadItem.Status.SAVING -> item.info ?: "写入相册中"
                DownloadItem.Status.COMPLETED -> "已完成 · 已存相册"
                DownloadItem.Status.FAILED -> "失败"
            }

            private fun detailText(item: DownloadItem): String {
                if (item.status == DownloadItem.Status.FAILED) return "错误：${item.error ?: "未知"}"
                if (item.galleryUri != null) return "相册：${item.galleryUri}"
                val got = item.bytesDownloaded / 1048576.0
                val total = if (item.totalBytes > 0) " / ${item.totalBytes / 1048576} MB" else ""
                return "已下载 ${"%.1f".format(got)} MB$total"
            }
        }
    }

    private class SniffAdapter : RecyclerView.Adapter<SniffAdapter.VH>() {
        private var data = listOf<SniffItem>()
        fun submit(items: List<SniffItem>) { data = items; notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSniffBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
        class VH(private val b: ItemSniffBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: SniffItem) {
                b.tvValue.text = item.value
                if (item.type == SniffItem.Type.MEDIA_URL) {
                    b.tvType.text = "媒体直链 · 点击下载"
                    b.root.setOnClickListener {
                        DownloadService.start(b.root.context, item.value, null, DownloadItem.SourceType.CAPTURE)
                    }
                } else {
                    b.tvType.text = "HTTPS 域名线索（证书绑定无法取直链）"
                    b.root.setOnClickListener(null)
                }
            }
        }
    }
}
