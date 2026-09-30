# 视频源下载器（个人安卓工具）

仅供个人在安卓手机上安装使用，用于**下载正在播放视频的原始源文件**（不是录屏、不是重新编码），
并自动存入系统相册。

## 它到底做了什么
1. **拿到原始直链**：通过两条路径拿到视频的真实下载地址（.mp4 / .m3u8 / .ts …）：
   - **内置浏览器嗅探**（最可靠）：在 App 内打开网页播放视频，WebView 自己完成 TLS，
     能拿到 HTTPS 视频的真实直链（含鉴权 Cookie），绕过证书绑定。
   - **系统流量嗅探**（VPN）：开启后，本机流量中的明文 HTTP 媒体直链会被捕获
     （HTTPS 只能拿到域名，见限制）。
2. **点击即下载源文件**：UI 提供明确入口（浏览器底部「下载：xxx」按钮、下载页手动直链框、
   嗅探自动捕获项），点击后由前台 `DownloadService` 直接下载原始字节。
3. **状态反馈**：下载页实时显示 排队 / 下载中 % / 合并分片 / 写入相册 / 完成 / 失败。
4. **存相册**：完成后通过 `MediaStore` 写入 `Movies/VideoGrabber`（分区存储，无需存储权限）。

## 工程结构
```
app/src/main/java/com/weig/videograbber/
  MainActivity.kt         底部导航 + 通知权限申请
  BrowserFragment.kt      内置 WebView，拦截媒体请求给出下载按钮
  DownloadsFragment.kt    嗅探开关 + 手动直链 + 下载列表/进度
  DownloadService.kt      前台下载服务（直链流式下载 / HLS 经 ffmpeg 零重编码转 mp4），完成后存相册
  CaptureVpnService.kt    本地 VPN 透明转发 + HTTP 直链提取 + HTTPS 的 SNI 域名提取
  MediaSaver.kt           MediaStore 写入相册
  MediaDetector.kt        媒体地址判定 / 文件名推断
  DownloadRepository.kt   跨组件（Service↔UI）下载状态共享（LiveData 单例）
  DownloadItem.kt         下载任务模型
```

## 如何构建
1. 用 **Android Studio**（已装 Android SDK，compileSdk 34）打开本目录 `VideoGrabber`。
2. 连接安卓手机（开发者选项 → USB 调试），点击 Run 安装到手机。
   - 本机（Windows）只写代码、不编译；首次构建需联网拉取 Gradle/依赖。
3. 首次运行会请求：
   - **通知权限**（用于前台服务进度通知，Android 13+）。
   - **VPN 权限**（点「开启嗅探」时系统弹窗，用于流量嗅探）。
   - 建议在系统设置里把本 App 设为「不受电池优化限制」，否则 OEM 可能杀掉后台嗅探/下载。

## 自动出 APK（推荐：你不用装任何开发工具）
本工程已内置 GitHub Actions 自动构建（`.github/workflows/build.yml`）。
只要把工程推到 GitHub 仓库，GitHub 的云端构建机（位于境外，可正常拉取 ffmpeg-kit 所需的 arthenica 仓库）
会自动编译并产出 APK，你下载安装即可——全程**不需要**在本机装 Android Studio / JDK / SDK。

> **为什么必须走这条路 / 为什么你不自己装也能拿到包**：
> App 依赖 ffmpeg-kit（视频转封装），它只发布在 arthenica 自有仓库、不在 Maven Central。
> GitHub Actions 的构建机在境外能正常访问该仓库；而写代码环境（本机沙箱）网络受限无法访问该仓库，
> 因此**这个工程无法在写代码环境直接编译出包**，必须由你在能联网的环境（GitHub Actions 或你自己的 Android Studio）构建。

最简步骤（网页操作，无需 git 命令）：
1. 注册一个 GitHub 账号（免费）：https://github.com
2. 新建一个仓库（Repository），名字随意（如 `VideoGrabber`），Public / Private 均可。
3. 进入新仓库，点 **Add file → Upload files**，把本 `VideoGrabber` 文件夹里**所有内容**拖进去
   （文件较多时可分几次上传，或用 GitHub Desktop 客户端一次性拖入；`.gitignore` 已排除 build 缓存，直接全选拖入即可）。
4. 提交（Commit）后，点仓库顶部的 **Actions** 标签，会看到一条 "Build Debug APK" 正在运行。
5. 等几分钟变绿（✓）后，点进该次运行，在 **Artifacts** 区下载 `videograbber-debug-apk`（里面是 `.apk`）。
6. 把 APK 传到手机安装即可（首次安装需允许「未知来源」）。

> 提示：以后代码有更新，重新 push 一次，APK 会自动重新生成。

## 给魏的傻瓜上手流程（备选：自己装 Android Studio 编译）
1. 找一台装了 **Android Studio** 的电脑（没有就装一个，免费），用数据线连上你的安卓手机。
2. 手机：设置 → 关于手机 → 连点「版本号」7 下打开开发者模式 → 返回 → 系统 → 开发者选项 → 打开 **USB 调试**。手机弹「是否允许」点允许。
3. 在 Android Studio 里「Open / 打开」本目录 `VideoGrabber`，等它联网拉完依赖（首次较慢）。
4. 顶部设备选你的手机，点 ▶ Run（绿色三角）。App 会自动装到手机上。
5. 手机打开 App：先去「浏览器」页，地址栏默认有个测试视频，点底部「下载：xxx」按钮，
   就能看到进度条、存相册进度，最后在相册 `Movies/VideoGrabber` 里找到成品。
6. 跑通测试视频后，再去抓你真正想抓的网页视频。

## 依赖说明
- 视频转封装用的是 **ffmpeg-kit（full 版）**，它自带各格式/协议支持（含 https、AES-128），
  无需你本地编译任何 C 代码；代价是安装包偏大（约几十 MB），个人自用完全无妨。

## 三种下载入口
| 入口 | 位置 | 适用 |
|---|---|---|
| 浏览器嗅探 | 「浏览器」页底部「下载：文件名」按钮 | 网页内播放的视频（含 HTTPS） |
| 手动直链 | 「下载」页输入框 + 下载按钮 | 任何你能拿到的 .mp4/.m3u8 直链 |
| 系统嗅探 | 「下载」页「开启嗅探」 | 明文 HTTP 视频（任意 App 播放时） |

## 已知限制（务必看）
- **HTTPS 拿不到完整直链**：系统嗅探走明文解析，HTTPS 只能看到域名（SNI）。
  证书绑定（cert pinning）的 App（抖音/快手/小红书等）即使是内置浏览器也拿不到直链。
  → 这类平台的视频请用各平台自带的「保存/分享」或 PC 端方案。
- **UDP/QUIC 未转发**：嗅探仅在开启时转发 TCP + DNS，QUIC(HTTP3) 会被丢弃，
  因此开启嗅探期间部分 HTTPS 站点可能加载异常——正常上网时请关闭嗅探。
- **HLS 转 mp4（已接入 ffmpeg-kit）**：`.m3u8` 现由 ffmpeg 直接拉取并以 `-c copy` 零重编码转成
  `mp4`（画质 100% 保留，仅换容器），**支持未加密与 AES-128 加密分片**。
  进度按 m3u8 总时长估算；直播型（无总时长）只显示「转封装中」。
  成败仍取决于「能否拿到直链」——证书绑定拿不到直链时这一环无从谈起。
- **透明 TCP 转发需真机验证**：`CaptureVpnService` 自实现序列号/校验和，
  是本项目最可能需要在真机上微调的部分。

## 备注
- **本工程已消除已知会阻断编译的问题**：补上了 `themes.xml`（Manifest 引用的主题）；
  在 `settings.gradle.kts` 固定了 AGP 8.13.2 / Kotlin 1.9.24 / Gradle 8.13 插件版本；
  在仓库里加入了 ffmpeg-kit 的 arthenica 源；`.gitignore` 已排除构建缓存。
- 写代码环境（Windows 沙箱）**未**实际编译运行（无法访问 arthenica 仓库与 GitHub），
  因此作为「已修复编译级错误的可构建骨架」交付；请在你的环境（GitHub Actions 或 Android Studio）首次构建。
- `CaptureVpnService` 的自实现 TCP 透明转发是本项目**最可能需要真机微调**的部分，
  首次验证建议先跑通「浏览器嗅探 + 下载转 mp4 + 存相册」主链路，再开系统嗅探。
- 从第三方平台抓取视频可能违反其服务条款，仅供个人学习/自用，分发责任由使用者承担。
