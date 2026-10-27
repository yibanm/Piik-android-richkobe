# Piik Android richkobe

[Piik](https://github.com/TNTcraftHIM/Piik)（开源屏幕共享应用）的 Android 汉化优化版。有了它可以：

- **观看**任何 Piik 邀请链接，全屏播放，离开应用时自动进入画中画。
- **从手机发起共享**：在手机上开一个 Piik 房间，把手机屏幕和声音分享给朋友。
- **房间聊天**：文字、表情、语音互动，与桌面端 Piik 一致（引擎自带，走 WebRTC 数据通道，无需服务器）。

这是移植而非重写。手机上运行的是 **Piik 自己的引擎**（其 Go App，为 Android 交叉编译）并展示 **Piik 自己的 web 界面**（内置完整简体中文语言包，中文系统下自动生效，页面内也可手动切换语言）。新增的部分是 Android 屏幕采集，接入 Piik 在 Windows、macOS、Linux 采集器上使用的同一套采集契约。因此房间、邀请、密码、公开邀请链接、画质设置和观看者管理的行为与桌面端 Piik 完全一致。

与 Piik 项目无关联。MIT 许可证，与 Piik 相同。

## 本版本相对原移植版的改动

- 应用名与应用内标题：**Piik Android richkobe**；包名：`com.richkobe.piikandroid`。
- 壳层界面（主页 / 通知 / 提示 / 错误信息）全部汉化；web 界面用上游自带的简体中文包，中文系统下自动生效，页面内也可手动切换。
- 主页标题行新增 **GitHub 图标**（层叠质感 + 点击反馈），点击直达本仓库。
- 内置引擎升级至 **Piik v2.5.0**（上游首次纳入 Android 的版本）：在 v2.4.1 已有的房间聊天（文字 / 表情 / 语音）、移动浏览器全屏修复、连接恢复与采集兼容性修复、房间语音 64 kbps 与房主码率设置、分享麦克风、多站点授权管理、完整简体中文语言包基础上，进一步获得采集稳定性改进（换源 / 编码器输出变化时画面继续推进、低码率恢复、静止画面关键帧）、更顺的应用进入（启动失败原地重试、从浏览器返回保留连接、共享页面资源压缩）、更本地化的故障恢复（被拒输入不再撤销远控授权、连接失败保留健康会话），以及前端「手机远控电脑」组件（需房间内另有一台支持远控的电脑；Android 设备本身不能被控）。
- **老设备白屏修复**：构建期把全部前端 js 降级到 Chrome 74 语法子集（esbuild + AST 门禁），运行期注入兼容垫片（Promise.allSettled / replaceAll / Object.hasOwn / structuredClone / .at() / crypto.randomUUID / Promise.any / findLast 等），Android 8/9 出厂 WebView 不再因语法过新或运行时 API 缺失而白屏、「无法访问」；若仍加载失败会显示中文诊断页而非静默空白。
- 新增 **悬浮球**：屏幕边缘可拖拽小球，点击展开菜单，在游戏或其他 App 里也能停止共享、跳回主控页（需授予「显示在其他应用上层」权限）。
- 新增 **系统声音开关**：可只关掉「手机正在播放的声音」的共享，麦克风与房间语音不受影响。
- 新增 **网页前进 / 后退箭头**（WebView 左下角原生悬浮按钮）与**重开直达房间**：离开屏幕再回来直接回到原来的房间页而非启动器。
- 新增 **首次启动引导忽略电池优化**（只问一次），防共享被系统冻结断流；主页**一键复制诊断信息**（含 WebView 内核版本），真机出问题粘贴即可反馈。
- **手表等小屏布局压缩**：注入 media query CSS（物理宽度 ≤480 CSS px 才命中），压缩控制条与卡片、隐藏装饰元素；只影响小屏设备（手表，均为 v7a 包），手机零影响。
- 修复 **主机端返回键**：投屏时按返回键直接回应用主页（共享在后台继续），不再卡在网页历史里、只能杀进程才能停共享。
- 网络：IPv4/IPv6 双栈媒体传输（ICE 候选收集与 wildcard 双栈 UDP，引擎原生支持）；UDP 优先、TCP 回退、STUN 多服务器容灾（cloudflare + 小米 + bilibili）、cloudflared 隧道提供公开邀请中继——移动数据下也可共享。
- CI 工作流：Go 1.27 + Gradle/npm/go module 三重缓存 + 并发防抖；Release 改为仅打 tag 时发布，日常构建从 Actions artifact 下载；APK 文件名带版本号与架构；发版说明自动从仓库内 `CHANGELOG.md` 抽取。

## 与官方安卓版的区别

上游 Piik 自 v2.5.0 起提供官方安卓安装包（`piik-app-android-universal.apk`，universal 包）。本仓库与官方安卓版都跑 Piik 引擎，但产品形态不同，对照如下（依据官方仓库 v2.5.0 release 资产与官方包实测，2026-10；官方后续版本若调整形态，以官方当时版本为准）：

| | 本仓库（Piik Android richkobe） | 官方安卓版（v2.5.0 universal） |
|---|---|---|
| 运行形态 | 原生 Android 应用：主页、通知、悬浮球、系统声音开关均为 Android 原生控件；观看离开应用自动画中画 | 浏览器形态加载本地服务的 web 界面（实测：浏览器地址栏与导航栏常驻屏幕底部） |
| 安装包 | 按 ABI 分两包：arm64-v8a ≈26 MB / armeabi-v7a ≈27 MB，各只含自己架构的引擎与隧道 | universal 单包 ≈14.4 MB，全架构合一包 |
| 界面布局 | 与桌面端同一套 web 界面，控制条在画面下方 | 主控制条悬浮叠在画面上，纵向占用更省 |
| 汉化 | 壳层（主页 / 通知 / 提示）全汉化；web 界面用上游内置简体中文包 | web 界面跟随上游语言包（含简体中文，中文系统自动生效） |
| Android 专属附加 | 悬浮球（在其他 App 里停共享 / 回主页）、应用内系统声音开关、主机端返回键直接回主页的修复 | 浏览器形态不提供悬浮球、系统声音开关等 Android 原生控件 |
| 内置引擎 | Piik v2.5.0 | Piik v2.5.0（版本一致；差异在打包形态与 Android 原生附加控件，见上） |

说明：

- 官方 universal 包下载体积（≈14.4 MB）明显小于本仓库单架构包（≈26–27 MB），其打包策略差异（含哪些架构、.so 是否 strip 或压缩）未解包验证，此处不下结论，表中只列实测下载体积。
- 本仓库计划借鉴官方版「控制条叠在画面上」的布局压缩底栏高度，**尚未实现**，当前版本界面布局仍与桌面端一致。

## 获取 APK

APK 由 GitHub Actions 构建（需要 Google 的 Android SDK、Go 和 Node）：

1. 打开本仓库 **Actions → Android APK**，进入最近一次构建。
2. 下载 artifact（名如 `piik-android-richkobe-2.5.0-apks`），解压得到 `Piik-Android-richkobe-v2.5.0-arm64-v8a.apk`（64 位设备，即 V8a/arm64 机型）与 `Piik-Android-richkobe-v2.5.0-armeabi-v7a.apk`（32 位老设备），两包功能一致。文件名带版本号与架构。
3. 传到手机打开安装（需允许文件管理器或浏览器「安装未知应用」）。

打 `v*` tag 时会额外发布 **Releases**，附带 APK，可直接用手机下载安装。

**关于更新：** 未配置签名密钥时 CI 用一次性 debug key 签名，而 Android 拒绝用不同密钥签名的应用覆盖升级。如需可覆盖升级，请在仓库 Secrets 配置 `PIIK_KEYSTORE_BASE64`（`.jks` 的 base64）、`PIIK_KEYSTORE_PASSWORD`、`PIIK_KEY_ALIAS`、`PIIK_KEY_PASSWORD`。

### 本地构建

需要 Go ≥ 1.27.3、Node ≥ 24、git，以及 Android Studio（或含 platform 35 的 Android SDK）。

```sh
scripts/build-native.sh        # Piik 引擎 + 采集 shim + cloudflared → app/src/main/jniLibs
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

`ABIS="arm64-v8a x86_64" scripts/build-native.sh` 会为 Android 模拟器追加 x86_64 构建。`SKIP_CLOUDFLARED=1` 会去掉「公开邀请」支持（「本地」与「站点」模式不受影响）。

## 使用方法

**观看：** 粘贴邀请链接，或从微信、QQ、Discord 等聊天应用把链接「分享」到本应用。`*.trycloudflare.com`（Piik 公开邀请）链接可直接在应用内打开。播放中离开应用会切换到画中画。

**发起共享：**

1. 点 **开始共享**。允许通知与录音权限；录音让共享包含手机声音和你的麦克风。
2. 出现 Piik 启动器，选择模式：
   - **本地房间：** 同一 Wi-Fi 内。
   - **公开邀请：** 任何有链接的人都能看，无需端口转发，移动数据可用。
   - **连接站点：** 你自己运行的 Piik 服务器。
3. **开始共享 → 系统的屏幕/窗口选择器。** Android 自己的对话框会询问共享什么。Android 14+ 可以只共享单个应用而非整块屏幕。
4. 切换到要共享的应用或游戏。通知会让共享保持存活；**停止**按钮在通知里和主页上都有。

画质调整、观看者管理和邀请链接都在 Piik 常规的主控页面里进行。

**房间聊天：** 主控页与观看页均有聊天入口（引擎提供），支持文字、表情互动与房间语音。

## 工作原理

```
 WebView (Piik 的 web UI) ──localhost──▶ libpiik.so  (Piik 的 Go App 引擎，子进程)
                                           │  启动它的「本地采集进程」：
                                           ▼
                                      libpiikcapture.so (shim) ──abstract Unix socket──▶ CaptureServer (Kotlin)
                                                                                           │
   MediaProjection ─▶ VirtualDisplay ─▶ GL (缩放 + 加边) ─▶ 每层一个 MediaCodec H.264 ──────┤ SMED 帧
   AudioPlaybackCapture / 麦克风 ─▶ 48 kHz PCM ─────────────────────────────────────────────┘
```

- **采集契约。** `CaptureServer` 实现 Piik 的 protocol 7，与 `native/capture/linux/main.c` 相同：
  - `--probe`、`--list`、`--list-microphones`、`--capture-video`、`--encoded-video`、`--capture-audio`、`--capture-microphone`
  - 32 字节「SMED」帧
  - starting 与 active 状态
  - Begin 帧
  - 按层 simulcast，`A`/`K`/`B`/`Q` 控制
- **编码。** 一条 GL 线程把每帧渲染进每个 simulcast 层各一个硬件编码器。硬件编码器的 SPS 被归一化为 Piik 要求的 constrained-baseline `42c0xx` profile-level-id。
- **一块屏幕，保持存活。** Android 14+ 每次屏幕采集授权只允许一个 VirtualDisplay，因此屏幕源的生命周期长于 Piik 的采集进程（画质每次变更都会重启采集进程），你不会每次变更都被再问一遍。
- **对 Piik 的补丁**（`native/piik-patches/`，约 40 行）：
  - 接受采集辅助进程上报的 `platform: "android"`。
  - 把打开页面的请求交给应用处理，而不是 `xdg-open`。
  - 从应用获取局域网地址与 DNS 服务器。在 Android 上纯 Go 二进制两者都读不到：没有 `/etc/resolv.conf`，且 Android 11+ 屏蔽 netlink。
- **cloudflared** 从源码构建，带同样的 DNS 修复。
- **WebView 与本地地址。** WebView 会把本地地址藏进 mDNS 名字后面。解析它们需要组播，而移动数据从不携带组播。Piik 的主控页通过 WebRTC 连接引擎，因此在主控模式下应用会短暂打开麦克风（随即停止），让 WebView 使用真实地址。若麦克风权限被拒绝，还有 Wi-Fi 组播锁兜底。

## 已验证与未验证

**v2.5.0 升级的验证边界（本仓库维护者，2026-10）**：本轮实跑了构建链，不止静态核对。已验证通过——

- 补丁 `0001-android-support.patch` 在**纯净 v2.5.0 克隆**（`499789671c75…`）上 `git apply --check` 干净应用；反向校验与工作树 diff 一致。
- **实跑** `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/piik-app`：编译通过，产物 47.6 MB（未 strip，含 v2.5.0 新依赖 livekit/protocol v1.52.2 等）；`-X internal/app/buildinfo.Revision` 注入路径正确（**v2.5.0 版本变量搬家**：从 `cmd/piik-app` 的 `internal/app.BuildRevision` 移到 `internal/app/buildinfo` 包的 `Revision`）。
- **实跑** `npm run build:web` + esbuild `--target=chrome74` 转译 + AST 门禁：`transpile gate: all dist js clean for chrome74 syntax subset`，`import.meta.url` 保留 2 处未被替换（chrome74 事故的核心回归点）。
- 采集契约核对：`probeProtocol` 仍为 7；`Capabilities` 仅新增 `remoteInput`/`captureBorderControl` 两个 `omitempty` 字段——Kotlin probe JSON（`CaptureProtocol.kt`）不传即默认 false，字段集合仍是引擎结构子集，`DisallowUnknownFields` 安全。
- Kotlin↔引擎传参链核对：`PIIK_LAN_ADDRESSES`（`lan/addresses.go`）、`PIIK_DNS_SERVERS`（`dns_android.go`）、`PIIK_ANDROID_CAPTURE_SOCKET`（`capture-shim/main.go`）、`PIIK_ANDROID_OPEN` marker（`browser/open.go` stderr）四个约定的读取点与 Kotlin 写入点一一对上。
- 工具链要求（Go 1.27.3 / Node ≥24 <27）与现有 CI 兼容；`vite` 仍无 `build.target`（转译门禁继续有效）；上游 `mobile/` 目录是官方自己的桥，与 `cmd/piik-app` + shim 路线不冲突——但它在主 module 内且 `mobile/cloudflared/fallback_test.go` 的测试依赖残缺，会让 `go mod vendor` 失败，故构建脚本在 vendor 前删除该目录（全仓库无 `Piik/mobile` 引用，已 grep 核验）。
- **CI 构建已通过**（Actions run `40477661849`，全部 step success）：两个 ABI 的三个原生产物均构建成功——arm64-v8a `libpiik.so` 49,611,046 B（47.3 MB）/ `libcloudflared.so` 28,770,598 B / `libpiikcapture.so` 3,539,238 B；armeabi-v7a（cgo + NDK clang）`libpiik.so` 47,410,896 B（45.2 MB）/ `libcloudflared.so` 27,219,904 B / `libpiikcapture.so` 3,300,904 B。
- **可排除「CI 复用缓存旧产物」**：与 v2.4.1 引擎那轮构建（run `40462220571`）逐字节对比，`libpiik.so` 两个 ABI 都变了（arm64 48,955,686 → 49,611,046 B，+640 KiB；v7a 46,674,648 → 47,410,896 B，+719 KiB），而 cloudflared 与采集 shim 的字节数两轮**完全相同**（版本未变，理应如此）——即只有引擎被重编、且确实变大了，与 v2.5.0 新增依赖相符。
- **armeabi-v7a 的 cgo 构建链本机也已跑通**（NDK r27，`CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7`）：删 `mobile/` 后 `go mod vendor` exit 0，三个产物全部构建成功并经 `file` 确认为 `ELF 32-bit LSB pie executable, ARM, EABI5, interpreter /system/bin/linker, for Android 26`。

**未做**：上游测试套件、协议校验器实测、Chromium 端到端、**真机投屏**——CI 只证明「能构建出包」，实际运行行为（共享、悬浮球、静音开关、老设备白屏修复效果）仍需真机验证。

以下为原移植版在 **v2.3.0** 上记录过的离线验证（保留作历史参考，非本仓库对 v2.5.0 重跑的结果）：

- 打过补丁的 Piik 引擎与 cloudflared 可为 Android 交叉编译，Piik 自己的测试在打补丁后通过。
- 全部 Kotlin 代码针对 Android 15 SDK 编译通过。
- Piik 的采集客户端及其严格校验器接受本应用通过真实 shim 输出的协议内容：
  - probe、源列表、starting 与 active 状态、SPS 归一化
  - 三个 simulcast 层，包括流中途激活层时以关键帧打开
  - 实时码率与关键帧控制
  - 系统音频
- Piik 的启动器 → 本地房间 → 主控页 → 原生共享流程在 Chromium（WebView 的内核）里端到端跑通，使用 Android 采集服务的替身。

本移植版在真机上的验证状态见仓库 Actions 构建结果与 issue。`adb logcat -s PiikService PiikCapture PiikVideo PiikEncoder PiikGl PiikProjection PiikWeb` 可查看运行日志。

## 限制

- 阻止截屏的应用（银行、Netflix 等 DRM 视频）会显示黑屏。选择不被采集音频的应用是无声的。通话与通知音永远不被采集。
- 熄屏时共享暂停。
- 主控页加载时麦克风指示灯会短暂闪烁（原因见上文）。
- 在移动数据下服务多个观看者会消耗大量上行流量。Piik 的画质设置生效。
- APK 下载约 26–27 MB（压缩后，40 MB 以内；实测 arm64-v8a 28,007,633 B、armeabi-v7a 28,861,219 B），安装后占用约 78 MB——因为携带完整的 Piik 引擎（libpiik.so ≈45–47 MB，两 ABI 不同）、cloudflared 隧道（libcloudflared.so ≈26–27 MB）与采集 shim。arm64-v8a（64 位）与 armeabi-v7a（32 位）两包功能一致，均含公开邀请模式。
- Android 8.0 出厂 WebView（Chrome 58–60）不支持 ES Module，即使语法降级也可能白屏（应用会显示诊断页）；更新系统 WebView 即可。Android 9 及以上已覆盖。

## 许可

本移植版为 MIT。Piik 为 MIT（© 其作者），其第三方声明内置于 web 界面 `/third-party-licenses.txt`。cloudflared 为 Apache-2.0（© Cloudflare）。GitHub 图标来自 GitHub octicons（MIT）。
