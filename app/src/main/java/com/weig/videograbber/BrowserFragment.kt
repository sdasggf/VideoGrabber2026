package com.weig.videograbber

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.fragment.app.Fragment
import com.weig.videograbber.databinding.FragmentBrowserBinding

/**
 * 内置浏览器：在 App 内打开网页并播放视频时，WebView 的网络请求会暴露出
 * 原始视频直链（.mp4/.m3u8…）。我们拦截这些请求、筛选出媒体地址，
 * 并在底部面板给出「明确的下载入口」——点击即下载源文件。
 *
 * 为什么用内置浏览器而不是系统 VPN：WebView 自己完成 TLS 握手，
 * 因此能拿到 HTTPS 视频的真实直链（含鉴权 Cookie），绕开了证书绑定的限制。
 */
class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private val seen = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile"
        }

        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (MediaDetector.isMediaUrl(url)) offerDownload(url)
                return null // 不拦截，让 WebView 照常加载
            }
        }

        binding.btnGo.setOnClickListener {
            var input = binding.addressBar.text.toString().trim()
            if (!input.startsWith("http")) input = "https://$input"
            binding.webview.loadUrl(input)
        }

        // 默认打开一个示例页，方便直接体验
        if (savedInstanceState == null) {
            binding.webview.loadUrl("https://www.w3schools.com/html/mov_bbb.mp4")
        }
    }

    /** 命中媒体地址：在底部面板加入一个下载按钮（去重）。 */
    private fun offerDownload(url: String) {
        if (seen.contains(url)) return
        seen.add(url)
        val name = MediaDetector.fileNameFromUrl(url)

        val btn = Button(requireContext()).apply {
            text = "下载：$name"
            setOnClickListener {
                val cookies = CookieManager.getInstance().getCookie(url)
                DownloadService.start(requireContext(), url, name, DownloadItem.SourceType.BROWSER, cookies)
            }
        }
        requireActivity().runOnUiThread {
            binding.detectedContainer.addView(btn)
            binding.detectedPanel.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        binding.webview.destroy()
        _binding = null
        super.onDestroyView()
    }
}
