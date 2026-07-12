# Atarayo-Cast 项目上下文

本文档记录 Codex 接手项目时已确认的项目状态、开发环境和后续迭代注意事项。后续迭代优先更新本文档，避免分散记录。

## 当前状态

- 项目仓库：`https://github.com/resolution1991/Atarayo-Cast.git`
- 本地源码目录：`/Users/algernon/Documents/Cast-App`
- 迁移记录：2026-07-05 已将原 `/Users/algernon/Documents/AirCast` 的源码、Git 历史、标签和 `origin` 远端迁入本 Codex 项目目录；后续以 `Cast-App` 作为默认工作目录。
- 当前分支：`main`
- 当前发布目标：`v0.7.0`（UI/UX 重构、Android 8 解码兼容、TextureView 显示链路与宽屏首页适配，发布到 GitHub）。
- v0.3 包含 Codex 修复：权限回调误启动、KeepScreenOn collector 累积、native 初始化失败处理、依赖路径配置、版本号对齐、设置页分辨率选中态、AirPlay 启动阶段块状伪影、持续投屏参考帧丢失重同步、AirPlay `maxFPS` 60fps 协商。
- 版本定位：Android 设备作为 AirPlay 接收端和 DLNA Media Renderer。
- 已确认构建产物：`/Users/algernon/Documents/Cast-App/app/build/outputs/apk/debug/app-debug.apk`

## 当前未发布改动（2026-07-10 UI/UX 重构）

- 主界面由“黑色视频底图 + 顶部工具栏 + 底部启停双按钮”重构为两态结构：未投屏时显示接收仪表盘，投屏时显示视频与统一媒体控制层。
- 空闲态仅保留一个随服务状态切换的主按钮；刷新、全屏、结束投屏只在对应投屏场景出现，避免无效操作。
- AirPlay 与 DLNA 使用统一控制语义；刷新画面仅对 AirPlay 显示，结束投屏根据当前协议调用 `disconnectClient()` 或 `terminateDlnaCasting()`。
- 全屏统一使用 `WindowInsetsControllerCompat`，返回键在全屏投屏时优先退出全屏；修复自动全屏偏好失效和“启动服务即全屏”的错误语义。
- `AirCastService.LocalBinder` 增加当前状态与协议读取，Activity 重新绑定时恢复真实界面状态。
- 设置页新增 MaterialToolbar 返回导航、五类设置分组、整行点击、Material 输入框和 PIN 完整性校验。
- 服务运行中新增“停止服务并编辑”路径，仅锁定设备名、分辨率、编码、PIN、防息屏等需服务重启的参数；自动全屏、PiP、开机自启和调试信息保持可改。
- 拒绝通知权限不再阻断前台接收服务启动，会继续启动并通过 Snackbar 提示。
- 视觉统一为 Material 3 深色体系、自定义矢量图标、至少 48dp 触控区域、edge-to-edge inset 与最大内容宽度。
- 2026-07-10 已完成 debug APK 构建；模拟器验证冷启动、主/设置导航、权限拒绝、服务启停、运行中设置解锁、设备名对话框和 PIN 错误校验。期间发现的 `TextInputLayout` 参数类型崩溃已修复，清空 crash buffer 后未再出现新崩溃。
- 待真机验证：真实 AirPlay / DLNA 投屏控制层、全屏/返回、结束投屏、PiP、横竖屏，以及 Lenovo YT-K606F 上的触控与系统栏表现。

## 投屏稳定性待办（2026-07-11）

- [ ] 连接真实 AirPlay 发送端和 Lenovo YT-K606F，采集“不出画面/闪退”的完整 Java、MediaCodec 与 native tombstone；当前无线调试地址拒绝连接，尚不能完成最终归因。
- [ ] 真机覆盖 AirPlay H.264/H.265、镜像/视频、横竖屏、全屏/返回、后台恢复、PiP、刷新画面与断开重连。
- [ ] 验证 Activity 进入后台时解除 Surface、返回前台重新绑定 MediaCodec 的行为，重点观察是否需要等待新 IDR 或触发解码器重建。
- [ ] 验证“刷新画面”在 MediaCodec 持有 Surface 时调用 `lockCanvas()` 的设备兼容性；如复现 native window 冲突，改为解码器安全的清帧/重建流程。
- [ ] 在可用真机上复测 16KB 页面环境下的 AirPlay native 链路；静态对齐通过不替代真实会话验证。

## 2026-07-11 已知投屏问题修复

- 自适应分辨率改为按长边 3840、短边 2160 的解码上限等比缩放，并保持设备原始横竖方向；1080x2400 不再被错误提升为 3840x2160。
- AirPlay 主原生库与 OpenSSL 增加 16KB 最大页面尺寸链接参数，避免关键 ELF LOAD 段继续以 4KB 对齐构建。
- 分辨率策略 5 项单元测试全部通过；覆盖 1080x2400 竖屏、2400x1080 横屏、2160x4096 超大竖屏、5120x1440 超宽屏和奇数尺寸偶数化。
- 强制清理 native 缓存并重建 OpenSSL 后，ARM64 与 x86_64 APK 内的 `libaircast_native.so`、`libcrypto.so`、`libc++_shared.so`、`libdatastore_shared_counter.so` 均确认 `0x4000` ELF LOAD 对齐，APK 通过 `zipalign -c -P 16`。
- 16KB 页面模拟器安装并启动成功，服务日志确认 `1080x2400 -> 1080x2400@60fps`，AirPlay 7000、DLNA 8090 和两项 mDNS 注册正常，crash buffer 为空。
- 修复后执行本地 DLNA 端到端回归：SOAP `SetAVTransportURI` / `Play` 均返回 HTTP 200，1280x720 H.264/AAC 视频记录首帧渲染并持续播放，未出现新崩溃。

### Huawei T5（Android 8）AirPlay 黑屏专项修复与回归状态

- 已从真机日志确认黑屏根因：`OMX.IMG.MSVDX.Decoder.AVC` 可完成 `MediaCodec.configure()`，但异步模式下从不触发输入槽回调，造成“Mac 显示已连接、T5 零帧送入/零帧渲染、队列溢出”。
- `VideoDecoder` 对 Android 8/9 改用同步 `dequeueInputBuffer()` / `dequeueOutputBuffer()` 路径；较新系统仍保持异步回调，避免改变现代设备的解码行为。
- T5 的旧 IMG 解码器额外采用 `1280×800@30fps` 的稳定协商上限；真机启动日志已确认手动 `1920×1200@60fps` 被限制，并实际写入 native 的 `refreshRate=30`、`maxFPS=30`。
- 调试覆盖层已增加协商尺寸、选中解码器/同步模式、送入/渲染/丢弃帧数、队列与输入槽、IDR 等待状态、最近输入帧字节数、送入与渲染间隔、Surface/配置状态及最后错误。
- 2026-07-11 已重新构建并安装 debug APK；`DisplaySizePolicyTest` 共 7 项通过，T5 冷启动、服务启动、AirPlay 7000、DLNA 8090、mDNS、WakeLock 和兼容协商均已验证，无 crash 日志。
- 第二轮实机证据（黑屏仍存在）：同步解码与协商策略均已生效，调试层显示 H.264 `1280×800@30`、已送 3629 / 已渲染 3626 / 丢弃 0，硬解驱动也累计报告 1347 帧已解码；因此根因从“没有喂入解码器”收敛到“已解码输出未真正合成到 Surface”。
- 已否决的修复：`releaseOutputBuffer(index, true)` 在该机 Android 8 `libstagefright.so` 的 `MediaCodec::onReleaseOutputBuffer` 内稳定触发 native `SIGABRT`，连接后约 5 帧即导致整个应用进程退出，无法通过 Kotlin 异常处理捕获。
- 当前修复：恢复时间戳输出接口，但将原先的无效 `0L` 改为 `System.nanoTime()` 单调时钟；旧 IMG 驱动可能把 `0L` 当作过期呈现时间而不合成，当前时钟可保持安全调用路径并让 SurfaceFlinger 正常接收帧。调试层标明 `输出：定时渲染（系统单调时钟）`。
- 第三轮 SurfaceFlinger 证据：视频缓冲区已作为 vendor-tiled `format=0x300` 的独立硬件图层提交，activeBuffer、crop 与 HWC Device layer 均存在，但该层需要以 transform `0x4` 旋转后输出到竖向物理面板，最终仍为黑色；表明 T5 的旧 Mali/Huawei 硬件合成器无法正确处理这条直通旋转路径。
- 同一会话中“刷新画面”的 `SurfaceHolder.lockCanvas()` 还多次与 MediaCodec 的 `API_MEDIA` 生产者争抢同一个 BufferQueue，系统明确记录 `already connected (cur=3 req=2)`；该按钮和 CPU Canvas 清屏逻辑已停用。
- 当前修复：AirPlay 视频承载由 `SurfaceView` 改为 `TextureView`，让 MediaCodec 输出先通过 SurfaceTexture/GPU 与应用 UI 合成，再交给系统显示，从而绕过失败的 vendor-tiled 直通硬件图层旋转。首次部署发现 Android 8 不允许 TextureView 设置 background drawable，已移除该属性；最终版本冷启动、Texture Surface 创建、服务启动和 crash buffer 均验证正常。
- [ ] 最后一项待确认：在 Mac 控制中心的“屏幕镜像”中重新选择 `T5`，持续镜像至少 30 秒；验收标准为调试层显示 `Surface: Valid (TextureView)`，且 Android 端有画面、不闪退、不再出现 `SurfaceView ... already connected`。当前自动化接口无法操作 macOS 控制中心的目标列表，需由本机用户完成该一次选择后继续采集真机日志。

## v0.3 后本地修复记录

- 2026-07-05 DLNA 视频黑屏修复：用户复测后确认 DLNA 音频已能接收、视频仍黑屏。日志显示 `SetAVTransportURI`/`Play`/seek/音量均已生效，进度和总时长持续更新，且系统为视频配置了 `1280x720` nativeWindow，说明拉流与解码链路已走通；进一步启动日志确认 Activity 绑定服务时调用 `setSurface`/`setPlayerView` 早于 `DlnaMediaPlayer` 创建，原 `DlnaManager` 未保存 pending PlayerView，播放器创建后没有实际挂载播放器视图。已新增独立 `androidx.media3.ui.PlayerView` 作为 DLNA 视频输出，AirPlay 继续使用原 `SurfaceView`；`DlnaManager` 缓存 pending `Surface`/`PlayerView` 并在播放器初始化后补挂；`MainActivity` 根据广播的 `EXTRA_PROTOCOL` 在 AirPlay Surface 与 DLNA PlayerView 间切换；`DlnaMediaPlayer` 新增视频尺寸和首帧渲染日志；GENA `NOTIFY` 从 `HttpURLConnection` 改为原始 socket，避免 Android 拒绝 `NOTIFY` 方法。后续复测仍黑屏时，日志和控件树确认 UI 已显示 `dlnaPlayerView`，但 `PlayerView` 内部 `exo_shutter` 仍覆盖，且没有 `Video size` / `Rendered first DLNA video frame`；发现 `setPlayerView()` 中错误地在 `view.player = player` 之后调用 `player.clearVideoSurface()`，等于把 PlayerView 刚绑定好的视频 Surface 清掉。已改为先清旧 Surface、再绑定 `view.player = player`。用户后续复测确认“画面能看到了”。
- 2026-07-05 DLNA 第二轮系统性重构：用户复测后确认仅设备名称修复生效，其余仍表现为接收端无响应、推送后进度 00:00。本轮 review 确认一个关键链路问题是 `network_security_config.xml` 只允许 `localhost/.local` 明文 HTTP，而 DLNA 控制端常推送 `http://192.168.x.x/...` 局域网媒体 URL，接收端会被 Android 明文策略拦截拉流；已改为允许明文流量。协议层补齐常见控制端预检/兼容动作：`PrepareForConnection`、`ConnectionComplete`、`SetNextAVTransportURI`、`GetDeviceCapabilities`、`GetCurrentTransportActions`、`SetPlayMode`、`ListPresets`、`SelectPreset`，并支持 `HEAD` 查询 SCPD/description。播放层将 ExoPlayer 位置、时长、状态、错误缓存到主线程维护，SOAP 查询只读缓存，避免 UPnP 工作线程直接读 ExoPlayer；`GetTransportInfo`/LastChange 返回真实 `OK` 或 `ERROR_OCCURRED`；播放状态异步变化时主动推 GENA 事件；`SetAVTransportURI` 后启用兼容性自动播放，覆盖只发 URI 不再发 `Play` 的控制端。
- 2026-07-05 DLNA 可用性修复：针对实测“接收端无响应、投屏端显示名称不是用户自定义名称、投屏后进度一直 00:00”，已将 DLNA friendlyName 改为使用设置中的设备名称；将 DLNA UDN 改为基于 Android ID 的稳定 UUID；统一 SSDP `LOCATION` 使用 UPnP HTTP 服务实际 host；修复 HTTP/SOAP 请求体按字符读取导致带中文/复杂 metadata 时可能超时无响应的问题；增强 SOAP 参数解析以支持命名空间和 XML entity 反转义；从 DIDL-Lite metadata 提取标题；`GetTransportInfo`/GENA LastChange 改为回读 ExoPlayer 真实播放状态；新增 GENA 订阅初始事件；DLNA 播放状态会同步到前台服务通知和主界面状态；关闭当前连接时同时停止 DLNA 播放。已通过 `./build.sh` 构建并安装到 `192.168.31.212:45523`，仍需用真实 DLNA 控制端复测发现、推送、进度、暂停、seek、音量和停止。
- `v0.4.0` 发布范围：固化 DLNA 发现/控制/视频显示/接收端本地控制/终止投屏链路，以及 APK launcher 新图标；Android 元数据对齐为 `versionName = "0.4.0"`、`versionCode = 5`。
- 手动选择 `2560x1600` 后 Mac 端显示投屏成功但 Android 端黑屏：已将视频帧池和 MediaCodec 输入上限从 4MB 提升到 6MB，避免较高分辨率首个 CSD/IDR 帧在 native 或 Kotlin 层被判定过大后丢弃。
- 同步将手动/自适应协商出来的目标分辨率预先写入 `VideoDecoder`，避免首帧早于 `onVideoSize()` 到达时用旧的 `1920x1080` fallback 配置 MediaCodec。
- 新增默认关闭的实验设置项 `强行使用 H.265 编码`：开启后服务端会强制打开 HEVC 能力声明，并在 native `video_set_codec` 协商回调中拒绝非 H.265 codec。该选项用于验证 Mac/iOS 端是否会改用 HEVC；若发送端仍只给 H.264，投屏会连接失败或被重置，属于预期兼容性风险。
- 修复强制 H.265 模式下发送端仍选择 H.264 时的黑屏问题：实机日志显示 `video_set_codec` 已拒绝 H.264，但 UxPlay 仍继续推送 H.264 视频帧，导致音频播放、Mac 端显示已连接、安卓端黑屏且调试层仍显示 H.264。现已在 native 视频帧入口丢弃强制模式下的非 H.265 帧，并回调 Kotlin 主动重启 RAOP HTTPD 断开该客户端，避免黑屏假连接。
- 为验证“伪装成 Apple TV 4K 较新系统版本是否触发 HEVC”新增项目内编译覆盖 `app/src/main/cpp/aircast_global_override.h`：保持 `GLOBAL_MODEL=AppleTV6,2`，将 `GLOBAL_VERSION` 从 UxPlay 默认 `220.68` 覆盖为 `380.20.1`。该覆盖通过 CMake `-include` 注入，统一影响 DNS-SD `srcvers/vs`、RAOP `Server: AirTunes/...`、AirPlay/RAOP info 响应，不直接修改外部依赖目录 `/Users/algernon/WorkBuddy/.../aircast-deps`。
- 设置页新增服务运行中锁定规则：当 `AirCastService` 正在运行时，灰化并禁用需要重启服务才可靠生效的设置，包括设备名称、分辨率、自适应分辨率、H.265、强制 H.265、防息屏、默认全屏、PIN 认证；设置页顶部显示“部分设置在服务进行中无法更改，请停止服务后再调整。”开机自启、画中画、调试信息保持可改。

## 技术栈

- Android Kotlin 原生应用
- Kotlin `2.0.21`
- Gradle Wrapper `8.11.1`
- Android Gradle Plugin `8.7.3`
- compileSdk / targetSdk `36`
- minSdk `24`
- NDK `27.0.12077973`
- CMake `3.22.1`
- JDK：Android Studio bundled JBR 21
- Native 层：C17 / C++17，通过 JNI 集成 UxPlay、OpenSSL、libplist、llhttp、playfair、Apple ALAC。

## 核心模块

- `app/src/main/java/com/atarayocast/app/MainActivity.kt`
  - SurfaceView 渲染、控制浮层、全屏/退出全屏、刷新画面、断开当前 AirPlay 客户端、设置入口、PiP。
- `app/src/main/java/com/atarayocast/app/service/AirCastService.kt`
  - 前台服务主生命周期，初始化 native bridge，注册 mDNS，启动 DLNA，协调视频/音频播放和通知栏状态。
- `app/src/main/java/com/atarayocast/app/bridge/NativeBridge.kt`
  - JNI 生命周期、DirectByteBuffer 帧池、DNS-SD TXT 记录、ALAC 解码入口。
- `app/src/main/java/com/atarayocast/app/video/VideoDecoder.kt`
  - MediaCodec 硬解 H.264/H.265，IDR 保护、色彩元数据、低延迟配置。
- `app/src/main/java/com/atarayocast/app/audio/AudioPlayer.kt`
  - PCM、ALAC、AAC-LC、AAC-ELD 播放与 AirPlay 增益控制。
- `app/src/main/java/com/atarayocast/app/dlna/`
  - 纯 Android API 实现 SSDP、UPnP HTTP、SOAP、GENA、Media3 ExoPlayer 播放。
- `app/src/main/cpp/`
  - UxPlay/RAOP native 集成、Android DNS-SD shim、JNI callbacks、native bridge。

## 构建环境

已确认本机存在：

- Android Studio JBR：`/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Android SDK：`/Users/algernon/Library/Android/sdk`
- 原生依赖目录：`/Users/algernon/WorkBuddy/2026-07-03-20-28-39/aircast-deps`

原生依赖目录包含：

- `UxPlay-master`
- `openssl-cmake-3`
- `libplist`
- `alac`

当前 `app/build.gradle.kts` 中 `AIRCAST_DEPS_DIR` 已改为可配置，不再硬编码个人绝对路径。优先级：

1. Gradle 参数：`-PaircastDepsDir=/path/to/aircast-deps`
2. `local.properties`：`aircast.deps.dir=/path/to/aircast-deps`
3. 环境变量：`AIRCAST_DEPS_DIR=/path/to/aircast-deps`
4. CMake 默认：`app/src/main/cpp/third_party`

本机 ignored 的 `local.properties` 已配置：

```properties
aircast.deps.dir=/Users/algernon/WorkBuddy/2026-07-03-20-28-39/aircast-deps
```

## 构建命令

推荐使用项目脚本：

```bash
cd /Users/algernon/Documents/Cast-App
./build.sh
```

安装到已连接设备：

```bash
cd /Users/algernon/Documents/Cast-App
./build.sh install
```

本项目默认规则：完成构建后，将最新 APK 安装到测试机。当前默认测试机为 `192.168.31.212:45523`（`Lenovo_YT_K606F`）；若 ADB 地址变化，先用 `adb devices -l` 重新确认。

构建脚本会设置：

- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- `GRADLE_OPTS=-Dorg.gradle.native=false`
- `GRADLE_USER_HOME=${TMPDIR}/aircast-gradle-home`
- `--project-cache-dir ${TMPDIR}/aircast-project-cache`

## 已验证结果

2026-07-04 在本机执行 `./build.sh`：

- 结果：成功
- 产物：`app-debug.apk`
- 产物大小：约 29 MB
- 三个 ABI 均参与 native 构建：`arm64-v8a`、`armeabi-v7a`、`x86_64`

构建中存在但未阻断的警告：

- Android SDK XML version 4 / parser supports up to 3 的 CMake 警告。
- `AirCastService.kt` 使用已废弃的 `defaultDisplay/getRealMetrics` 分支。

在 Codex 沙箱内直接构建会失败，错误为 Gradle daemon 本地 socket `Operation not permitted`。需要允许构建命令在沙箱外执行，或使用已授权的 `./build.sh`。

## 当前测试方法

- 主测试环境：Lenovo YT-K606F 真机，通过无线调试连接本机，当前 ADB 地址为 `192.168.31.212:45523`。
- 真机负责 AirPlay/DLNA 端到端验证：mDNS/SSDP 发现、投屏首帧、音视频、断开重连、PiP、防息屏和后台行为。
- Android Studio 模拟器只做辅助烟测：安装、启动、设置页、权限弹窗和基础生命周期，不用于判断 AirPlay/DLNA 是否真正可用。
- 典型日志命令：

```bash
adb -s 192.168.31.212:45523 logcat -s AirCastService MainActivity NativeBridge VideoDecoder AudioPlayer AirPlayRegistrar DlnaManager DLNA_SSDP DLNA_HTTP AirCastNative
```

## 最低兼容设备基线（2026-07-11）

- 测试设备：Huawei AGS2-AL00HN（设备名 `T5`），USB ADB 序列号 `FKFBB18C21151702`。
- 系统与硬件：Android 8.0 / API 26、`arm64-v8a`、8 核 CPU（4×1.709GHz + 4×2.362GHz）、约 4GB 内存、1200×1920 @ 320dpi、Mali-T830 / OpenGL ES 3.2、4KB 内存页。
- 安装验证：当前 debug APK `versionName=0.7.0`、`versionCode=7` 覆盖安装成功；冷启动与横屏首页验证正常，无 crash buffer 记录。
- 服务验证：AirPlay 7000、DLNA 8090、mDNS 注册、WakeLock 和 native 帧池初始化均成功；该机保留了手动 `2560x1600@60fps` 设置，后续需补测清除数据后的默认自适应分辨率和 1080p 手动档。
- DLNA 回归：本机 HTTP 测试媒体经 SOAP `SetAVTransportURI` / `Play` 均返回 HTTP 200；1280×720 H.264/AAC 视频在真机记录到首帧渲染、持续播放与播放进度，无闪退。
- 性能快照：播放期总 PSS 约 121.8MB（Native Heap 13.2MB、Dalvik Heap 32.7MB）；10 秒稳态 UI 帧统计为 107 帧，90/95/99 分位 18/20/65ms。该统计包含控制层绘制，不等价于硬件视频 Surface 的实际帧率。
- 待补测：真实 AirPlay 发送端的 H.264/H.265、音频、断开重连、横竖屏、全屏/返回、PiP；需要同一局域网内的 Mac、iPhone 或 iPad 发起真实会话并采集日志。

## 启动伪影修复策略

- 原因：启动阶段如果 MediaCodec 先处理无效帧、非 IDR 帧，或在未同时拿到 CSD 参数集和 IDR 时配置，会用不完整参考帧建立解码状态；即使拿到 IDR，前几个输出缓冲仍可能处在解码器同步过渡期。
- 修复：`VideoDecoder` 未配置时只接受“CSD + IDR”的干净同步帧；无 leading NAL start code 的帧直接丢弃；flush/重同步后等待下一帧 IDR；配置或重同步后抑制前 2 个输出缓冲，不渲染到 Surface。

## 持续伪影修复策略

- 原因：H.264/H.265 的非 IDR 帧依赖前序参考帧。旧版 `VideoDecoder` 使用单帧 pending 覆盖策略，native 帧池满时也可能直接丢非 IDR；任意参考帧丢失后继续解 P/B 帧，会把宏块错误传播到下一帧 IDR。
- 修复：`VideoDecoder` 改为最多 8 帧的顺序有界队列，保持输入 decode order；队列溢出时丢弃积压并等待下一帧 IDR。native 帧池丢帧时通过 `onVideoReset(1001)` 触发 Kotlin `flush()`，并在 native 侧抑制非 IDR，直到下一帧 IDR 成功进入 Java。
- MediaCodec 注意事项：异步 `MediaCodec.flush()` 后必须重新 `start()`，否则可能不再收到 input buffer 回调。

## 60fps 协商策略

- 原因：UxPlay display plist 中 `refreshRate` 和 `maxFPS` 是不同字段；`refreshRate=60` 表示显示刷新率，`maxFPS` 才限制 AirPlay client 最高推流帧率。UxPlay 默认 `maxFPS=30`。
- 修复：`nativeSetDisplaySize(width, height, fps)` 同时写入 `refreshRate=fps` 和 `maxFPS=fps`，并在 native 日志输出实际参数。
- 边界：`maxFPS=60` 是 advisory，不保证发送端一定输出 60fps；如果仍为 30fps，下一步应验证发送端型号、请求分辨率、网络条件和 AirPlay 协商日志。

## DLNA 本地控制与发现兼容性

- 2026-07-05 修复方向：DLNA 投屏期间改用独立 `PlayerView` 承载 ExoPlayer 视频输出，并开启 Media3 自带 controller；DLNA 播放时隐藏 AirPlay 风格的全屏 overlay，避免接收端触摸被上层 UI 干扰。
- 本地控制同步：接收端暂停、播放、拖动进度条会更新 `DlnaMediaPlayer` 的缓存进度与传输状态，并触发 AVTransport GENA 通知，降低投屏端与接收端状态脱节的概率。
- 小米 17 系统本地媒体发现兼容性：SSDP 发现响应改为大小写宽松匹配，补齐裸 UUID、root device、MediaRenderer、AVTransport、ConnectionManager、RenderingControl 多目标响应；alive/byebye 通知也补齐裸 UUID 与服务目标。
- 设备描述兼容性：`description.xml` 增加 DLNA DMR 标记 `X_DLNADOC=DMR-1.50`、`presentationURL`，并扩大 `SinkProtocolInfo`，覆盖常见本地媒体 MIME，如 mp4/m4v/mkv/webm/mov/3gp/avi/flv/mpeg/ts、常见音频和通配 `video/*`、`audio/*`。
- 2026-07-05 增加接收端“终止投屏”操作：DLNA 播放时触摸画面会短暂显示 `终止投屏` 按钮；按钮调用 `AirCastService.LocalBinder.terminateDlnaCasting()`，再走 `DlnaManager.stopPlaybackFromReceiver()` -> `UpnpHttpServer.stopPlaybackFromReceiver()` 清空当前 URI、停止 ExoPlayer，并主动发送 AVTransport LastChange 通知。
- 2026-07-05 交互调整：`终止投屏` 按钮不再使用独立隐藏计时器，改为监听 `PlayerView` controller visibility；Media3 播放控制 UI 显示时按钮同步显示，控制 UI 隐藏时按钮同步隐藏。
- 仍需真机复测：小米系统本地媒体投屏的设备列表是否恢复、发起投屏后是否能正常播放、接收端触摸是否能呼出播放控件并控制进度。

## 宽屏横屏首页适配（2026-07-12）

- 现象：首页的 `dashboardScroll` 以前被直接限制为最大 `680dp` 宽。横屏宽屏设备上，其两侧会露出下方仍保持可见的 `TextureView` 视频层，表现为两条黑边。
- 修复：滚动背景改为始终铺满父容器；新增内部约束容器，仅将 `dashboardContent` 限制为最大 `680dp` 并居中。这样保留了卡片和文字的舒适阅读宽度，同时背景完整覆盖横屏屏幕。
- 真机验证：Huawei AGS2-AL00HN（T5，Android 8.0，1920×1200 横屏）覆盖安装后截图确认，首页背景连续显示、内容居中、无两侧黑边；首页仍可纵向轻微滚动访问底部“启动接收服务”操作。

## 功能边界

当前 README 和 CHANGELOG 显示 v0.3 已覆盖：

- AirPlay 屏幕镜像：H.264/H.265
- AirPlay 音频：PCM、ALAC、AAC-LC、AAC-ELD
- FairPlay / PIN 认证
- mDNS 服务注册
- DLNA Media Renderer
- 自动全屏、PiP、防息屏、通知栏控制
- 调试覆盖层
- 分辨率自适应与手动选择
- 视频低延迟和帧池优化
- AirPlay 音量增益控制

## 后续迭代优先关注

1. 真机验证闭环：构建通过不等于投屏链路稳定，需要按 AirPlay、DLNA、音频、断开重连、后台/PiP 分场景验证。
2. Android 兼容性：处理 deprecated API，尤其是显示尺寸、全屏沉浸、通知权限和后台启动限制。
3. 日志与诊断：为 RAOP 连接、解码器重建、DLNA SOAP 错误和 mDNS 注册失败整理可复用排障命令。
4. 发布配置：当前 debug/release 元数据已对齐到 `versionName = "0.7.0"`、`versionCode = 7`；后续每次 tag/release 仍需同步更新。
5. 依赖授权：项目因 UxPlay 使用 GPL-3.0，发布前需要确保 LICENSE、源码提供方式和第三方依赖声明完整。

## 常用命令

```bash
git status --short --branch
./build.sh
./build.sh clean
./build.sh install
adb shell am start -n com.atarayocast.app/.MainActivity
adb logcat -s AirCastService MainActivity NativeBridge VideoDecoder AudioPlayer AirPlayRegistrar DlnaManager
```
