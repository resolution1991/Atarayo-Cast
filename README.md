# Atarayo-Cast

Android 设备作为 AirPlay 接收端和 DLNA Media Renderer，接收来自 macOS / iOS 的屏幕镜像、音频投屏和 DLNA 媒体推送。

基于 [UxPlay](https://github.com/antimof/UxPlay) AirPlay 引擎，通过 JNI 集成到 Android 原生层，使用 MediaCodec 硬件解码和 Media3 ExoPlayer 实现 H.264/H.265 视频渲染和多格式音频播放。

---

## 功能特性

### AirPlay 接收端

- **屏幕镜像** — 接收 macOS / iOS 的 AirPlay 屏幕镜像（H.264 / H.265 硬件解码）
- **音频投屏** — 支持 PCM / ALAC / AAC-LC / AAC-ELD 四种音频编码
- **FairPlay 认证** — 完整的 FairPlay / PIN 认证流程
- **mDNS 发现** — 通过 Android NsdManager 注册 `_raop._tcp` 和 `_airplay._tcp` 服务
- **自定义 PIN** — 用户可设置 4 位 PIN 码进行认证

### DLNA 接收端

- **SSDP 发现** — MulticastSocket 实现 M-SEARCH 响应和 NOTIFY alive/byebye
- **SOAP 控制** — ConnectionManager / AVTransport / RenderingControl 全动作实现
- **GENA 事件** — 事件订阅框架，支持 HTTP NOTIFY 推送到订阅者
- **媒体播放** — Media3 ExoPlayer 处理 DLNA 推送的媒体 URL
- **接收端控制** — 支持在被投屏端呼出播放控制、暂停/播放、拖动进度和终止投屏
- **纯原生实现** — 无外部 DLNA 库依赖，全部使用 Android API

### 高级功能

- **自动全屏** — 投屏连接成功后自动切换全屏模式，投屏结束时自动退出
- **刷新画面** — 一键清除 Surface 画面，消除偶发压缩伪影
- **断开连接** — 关闭按钮仅断开当前 AirPlay 连接，服务保持运行，可立即重连
- **分辨率自适应** — 自动检测设备屏幕分辨率（上限 4K），也支持手动选择 9 种分辨率
- **画中画 (PiP)** — 投屏中按 Home 键进入画中画模式
- **防息屏** — WakeLock 保持 CPU 唤醒，FLAG_KEEP_SCREEN_ON 保持屏幕常亮
- **全屏沉浸** — 隐藏系统栏，控制浮层自动隐藏
- **调试覆盖层** — 实时显示 FPS、码率、编码器、分辨率
- **无投屏提示** — 未投屏时居中显示"当前无投屏输入"
- **开机自启** — 支持 BootReceiver 开机自动启动服务
- **通知栏控制** — 前台服务通知，含停止按钮，点击回到应用

### 性能优化 (v0.2 / v0.3)

- **DirectByteBuffer 帧池** — 24 x 6MB 预分配缓冲区（v0.1: 8 个），零 per-frame 内存分配
- **MediaCodec 异步回调** — 无轮询，codec 就绪时自动拉取帧
- **IDR 关键帧保护** — Kotlin + C 双层保护，IDR 帧池满时 sem_timedwait 等待 15ms，非 IDR 帧立即丢弃
- **顺序有界解码队列** — Kotlin 侧最多缓存 8 帧并保持 decode order，避免覆盖参考帧导致持续宏块伪影
- **IDR 重同步门控** — 队列溢出、native 帧池丢帧或 codec flush 后等待下一帧 IDR，再恢复渲染
- **干净首帧启动** — codec 未配置前只接受带 CSD 参数集和 IDR 的 Annex-B 帧，抑制启动期前 2 个输出缓冲
- **60fps 协商** — 同时设置 AirPlay `refreshRate` 和 `maxFPS`，允许发送端最高按 60fps 推流
- **NAL 零拷贝解析** — 使用偏移引用代替 ByteArray 复制
- **色彩元数据** — KEY_COLOR_STANDARD=BT.709 / KEY_COLOR_RANGE=LIMITED / KEY_COLOR_TRANSFER=SDR_VIDEO
- **解码器调度** — KEY_PRIORITY=0 (实时) / KEY_OPERATING_RATE=120 / KEY_FRAME_RATE=60
- **自适应播放** — KEY_MAX_WIDTH/HEIGHT=3840x2160，分辨率切换无需重建 codec
- **低延迟模式** — MediaCodec KEY_LOW_LATENCY (API 30+)
- **解码线程优先级** — THREAD_PRIORITY_DISPLAY
- **JNI 方法 ID 缓存** — ByteBuffer.clear/limit 方法 ID 在初始化时缓存，消除每帧 120+ 次冗余 JNI 调用
- **UPnP 线程池** — CachedThreadPool，最大 50 并发连接

### 音量控制 (v0.2 新增)

- **AudioTrack 增益控制** — Mac 音量仅控制投屏音频增益（0.0-1.0 连续值），不修改系统音量
- **dB→线性转换** — `10^(dB/20)` 标准音频增益映射
- **音频焦点联动** — duck/restore 时保留 AirPlay 增益，不覆盖

---

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    Kotlin 应用层                     │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │MainActivi│  │SettingsAc│  │  AirCastService   │  │
│  │   ty     │  │ tivity   │  │ (前台服务 + Callback)│  │
│  └────┬─────┘  └──────────┘  └───┬────────┬──────┘  │
│       │                         │        │          │
│  ┌────▼─────┐  ┌──────────┐  ┌─▼──┐  ┌──▼───────┐  │
│  │VideoDecod│  │AudioPlaye│  │NSD │  │DlnaManager│  │
│  │  er      │  │   er     │  │Reg │  │           │  │
│  └────┬─────┘  └────┬─────┘  └────┘  └───────────┘  │
│       │              │              │                 │
│  ┌────▼──────────────▼──────────────▼─────────────┐  │
│  │              NativeBridge.kt                   │  │
│  │        (JNI 声明 + DirectByteBuffer 池)          │  │
│  └────────────────────┬───────────────────────────┘  │
├───────────────────────┼──────────────────────────────┤
│              JNI 边界 (native_bridge.cpp)              │
├───────────────────────┼──────────────────────────────┤
│                   C/C++ 原生层                         │
│  ┌────────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │UxPlay Core │  │OpenSSL   │  │android_raop_     │  │
│  │(RAOP/AirPla│  │3.4.4     │  │  callbacks.c     │  │
│  │  y 协议)   │  │          │  │(帧池 + JNI 回调)  │  │
│  └────────────┘  ├──────────┤  └──────────────────┘  │
│                  │libplist  │  ┌──────────────────┐  │
│  ┌────────────┐  │llhttp    │  │Apple ALAC Decoder│  │
│  │playfair    │  │          │  │(软件 ALAC 解码)    │  │
│  │(FairPlay)  │  └──────────┘  └──────────────────┘  │
│  └────────────┘                                       │
│                                                       │
│  输出: libaircast_native.so                           │
└─────────────────────────────────────────────────────┘
```

### 核心组件

| 层级 | 文件 | 职责 |
|------|------|------|
| **UI** | `MainActivity.kt` | SurfaceView 渲染、自动全屏、刷新、断开连接、PiP、调试覆盖层 |
| **UI** | `SettingsActivity.kt` | 设备名、分辨率、H.265、PIN、画中画等设置 |
| **服务** | `AirCastService.kt` | 前台服务，实现 `NativeCallbacks`，协调所有子系统 |
| **视频** | `VideoDecoder.kt` | MediaCodec 异步解码 H.264/H.265，IDR 保护，NAL 解析，色彩元数据 |
| **音频** | `AudioPlayer.kt` | PCM 直写 / ALAC 软解 / AAC 硬解，AirPlay 增益控制，音频焦点管理 |
| **JNI** | `NativeBridge.kt` | JNI 声明 + DirectByteBuffer 帧池管理 + restartHttpd |
| **JNI** | `NativeCallbacks.kt` | 19 个 native → Kotlin 回调接口 |
| **mDNS** | `AirPlayRegistrar.kt` | NsdManager 注册 RAOP/AirPlay 服务 |
| **DLNA** | `DlnaManager.kt` | SSDP + SOAP/HTTP + ExoPlayer |
| **DLNA** | `SsdServer.kt` | SSDP 多播发现 |
| **DLNA** | `UpnpHttpServer.kt` | UPnP 设备描述 + SOAP 控制 + GENA 事件 |
| **原生** | `native_bridge.cpp` | JNI 实现：RAOP 生命周期、帧池、ALAC 解码、HTTPD 重启 |
| **原生** | `android_raop_callbacks.c` | RAOP 回调 → JNI → Kotlin，帧池管理，IDR 保护，JNI 方法缓存 |
| **原生** | `android_dnssd_shim.c` | DNS-SD TXT 记录构建（适配 Android NsdManager） |

### 视频管线 (v0.3 优化)

```
macOS/iOS
  │ AirPlay H.264 NAL stream
  ▼
UxPlay RAOP (C)
  │ _video_process callback
  │ ↓ IDR 检测 (_is_idr_frame)
  │ ↓ IDR: sem_timedwait(15ms)  非 IDR: sem_trywait(立即丢弃)
  │ ↓ 池满丢帧: onVideoReset(1001) + 抑制非 IDR 直到下一帧 IDR
  ▼
Frame Pool (24 x 6MB DirectByteBuffer)
  │ memcpy → ByteBuffer (JNI 方法 ID 已缓存)
  ▼
NativeCallbacks.onVideoData() [Kotlin]
  │
  ▼
VideoDecoder.handleFrame()
  │ 首帧: configureCodec(CSD) → 喂入首帧 IDR 数据
  │ 后续: IDR 检测 → 顺序有界队列 (最多 8 帧)
  │      → 队列溢出/flush 后等待下一帧 IDR 重同步
  │      → IDR 使用 BUFFER_FLAG_KEY_FRAME 标志
  ▼
MediaCodec async (THREAD_PRIORITY_DISPLAY)
  │ KEY_PRIORITY=0, KEY_OPERATING_RATE=120
  │ KEY_COLOR_STANDARD=BT.709, KEY_COLOR_RANGE=LIMITED
  │ KEY_MAX_WIDTH=3840, KEY_MAX_HEIGHT=2160 (自适应)
  │ KEY_LOW_LATENCY=1 (API 30+)
  │ onInputBufferAvailable → 按 decode order 喂入 queued frames
  │ onOutputBufferAvailable → releaseOutputBuffer(0L) → Surface
  ▼
SurfaceView (硬件渲染)
  │
  ▼
nativeBridge.returnFrameBuffer() → 帧归还池
```

> 60fps 协商：Android 侧通过 `nativeSetDisplaySize()` 同时写入 `refreshRate` 和
> `maxFPS`。`maxFPS=60` 只是允许 AirPlay client 最高 60fps 推流，最终帧率仍由
> 发送端、网络和编码策略决定。

### 音频管线

```
macOS/iOS
  │ AirPlay audio (ct=0/2/4/8)
  ▼
UxPlay RAOP (C)
  │ _audio_process callback
  ▼
NativeCallbacks.onAudioData(data, ct) [Kotlin]
  │
  ├── ct=0 (PCM)     → AudioTrack 直接写入
  ├── ct=2 (ALAC)    → NativeBridge ALAC 软解码 → AudioTrack
  ├── ct=4 (AAC-LC)  → MediaCodec 硬解码 → AudioTrack
  └── ct=8 (AAC-ELD) → MediaCodec 硬解码 → AudioTrack
                         ↑ AudioTrack.setVolume(airplayGain)
                           Mac 音量 → dB → 10^(dB/20) → 0.0-1.0 增益
```

---

## 原生依赖

项目通过 CMake 从源码编译以下库，不使用任何预编译二进制：

| 库 | 版本 | 用途 | 许可证 |
|----|------|------|--------|
| [UxPlay](https://github.com/antimof/UxPlay) | master | AirPlay/RAOP 协议核心 | GPL-3.0 |
| [OpenSSL](https://www.openssl.org/) | 3.4.4 | TLS/加密 | Apache-2.0 |
| [libplist](https://github.com/libimobiledevice/libplist) | 2.6.0 | Apple plist 解析 | LGPL-2.1 |
| llhttp | (UxPlay 内嵌) | HTTP 请求解析 | MIT |
| playfair | (UxPlay 内嵌) | FairPlay 解密 | GPL-3.0 |
| [Apple ALAC](https://github.com/macosforge/alac) | - | ALAC 音频解码 | Apache-2.0 |

---

## 构建指南

### 环境要求

- **Android Studio** (含 JDK 21 JBR)
- **Android SDK** compileSdk 36, minSdk 24
- **NDK** 27.0.12077973
- **CMake** 3.22.1
- **Gradle** 8.11.1 + AGP 8.7.3
- **macOS** 或 **Linux** (Windows 未测试)

### 依赖准备

原生依赖库需要放在 `AIRCAST_DEPS_DIR` 指定的目录（未配置时默认为 `app/src/main/cpp/third_party/`，可通过 Gradle/CMake 参数覆盖）：

```
aircast-deps/
├── UxPlay-master/       # UxPlay 源码 (lib/ 目录)
├── openssl-cmake-3/      # OpenSSL CMake 构建脚本
├── libplist/             # libplist 源码 (src/ + libcnary/ + include/)
└── alac/                 # Apple ALAC 解码器 (codec/ 目录)
```

依赖路径可用以下任一方式配置，优先级从高到低：

```properties
# local.properties（推荐，本机私有，不提交）
aircast.deps.dir=/absolute/path/to/aircast-deps
```

```bash
# 或通过 Gradle 参数
./gradlew assembleDebug -PaircastDepsDir=/absolute/path/to/aircast-deps

# 或通过环境变量
export AIRCAST_DEPS_DIR=/absolute/path/to/aircast-deps
```

### 一键构建

```bash
cd Atarayo-Cast
./build.sh
```

构建脚本支持三种模式：

```bash
./build.sh           # 构建 debug APK
./build.sh clean     # 清理 + 构建
./build.sh install   # 构建 + 安装到已连接设备
```

### 手动构建

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export GRADLE_OPTS="-Dorg.gradle.native=false"
export GRADLE_USER_HOME="/tmp/gradle-user-home"

cd Atarayo-Cast
./gradlew assembleDebug --no-daemon --project-cache-dir=/tmp/atarayo-cache
```

### 构建产物

```
app/build/outputs/apk/debug/app-debug.apk
```

### ABI 支持

- `arm64-v8a` (64位 ARM，主流设备)
- `armeabi-v7a` (32位 ARM，旧设备兼容)
- `x86_64` (模拟器)

---

## 安装与使用

### 安装到设备

```bash
# 通过 ADB 连接设备
adb connect <device_ip>:<port>

# 安装 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动应用
adb shell am start -n com.atarayocast.app/.MainActivity
```

### 使用方法

1. **启动服务** — 打开 Atarayo-Cast 应用，点击「启动服务」
2. **AirPlay 投屏** — 在 macOS 控制中心选择 AirPlay，找到设备名称
3. **自动全屏** — 投屏连接成功后自动进入全屏模式
4. **DLNA 投屏** — 在支持 DLNA 的应用中选择设备名称
5. **全屏控制** — 投屏时点击屏幕显示控制栏
   - **刷新** — 清除画面伪影
   - **关闭** — 断开当前投屏（服务保持运行）
   - **全屏** — 切换全屏/窗口模式
   - **设置** — 进入设置页面
6. **画中画** — 投屏中按 Home 键进入 PiP 模式
7. **音量控制** — 在 Mac 上调整音量，仅影响投屏音频（不修改系统音量）
8. **停止服务** — 点击通知栏的停止按钮

### 设置项

| 设置 | 说明 | 默认值 |
|------|------|--------|
| 设备名称 | mDNS 广播的设备名 | Atarayo-Cast-Android |
| 分辨率 | 投屏分辨率（自动 / 9种手动选项） | 自动 |
| 自适应分辨率 | 自动检测屏幕原生分辨率 | 开启 |
| H.265/HEVC | 启用 HEVC 编码支持（4K） | 开启 |
| 防息屏 | 投屏时保持屏幕常亮 | 开启 |
| 默认全屏 | 服务启动后自动进入全屏 | 关闭 |
| PIN 认证 | 4 位 PIN 码认证 | 关闭 |
| 开机自启 | 开机自动启动服务 | 关闭 |
| 画中画 | 支持投屏中进入 PiP | 开启 |
| 调试信息 | 实时显示 FPS/码率/编码/分辨率 | 关闭 |

### 可选分辨率

| 分辨率 | 宽高比 | 帧率 |
|--------|--------|------|
| 3840x2160 | 16:9 | 30fps |
| 2560x1600 | 16:10 | 60fps |
| 2560x1440 | 16:9 | 60fps |
| 2160x1350 | 16:10 | 60fps |
| 1920x1200 | 16:10 | 60fps |
| 1920x1080 | 16:9 | 60fps |
| 1080x675 | 16:10 | 60fps |
| 1280x720 | 16:9 | 60fps |

手动模式不限制设备原生分辨率，可选择高于设备屏幕的分辨率。

---

## 端口使用

| 端口 | 协议 | 用途 |
|------|------|------|
| 7000 | TCP | RAOP (AirPlay 音频/视频) |
| 7100 | TCP | AirPlay (连接信息) |
| 6000 | UDP | RTP Video |
| 6001 | UDP | RTP Audio |
| 1900 | UDP | DLNA SSDP (多播) |
| 8090 | TCP | DLNA HTTP (设备描述 + SOAP) |

---

## 安全配置

- **网络安全** — `network_security_config.xml` 允许明文流量，用于接收 DLNA 控制端推送的 `http://192.168.x.x/...` 局域网媒体 URL
- **PIN 认证** — 用户自定义 4 位 PIN 码，通过 `raop_set_plist` 设置到 RAOP 引擎
- **JNI 安全** — 所有 `GetStringUTFChars` 调用添加 NULL 检查
- **XML 转义** — UPnP/SOAP XML 输出添加 `xmlEscape()` 防止注入
- **线程安全** — GENA 订阅者使用 `ConcurrentHashMap`，JNI AttachCurrentThread 使用 pthread TLS 自动 detach

---

## 项目结构

```
Atarayo-Cast/
├── app/
│   ├── build.gradle.kts              # 应用构建配置
│   ├── proguard-rules.pro            # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml       # 清单文件
│       ├── cpp/                      # C/C++ 原生代码
│       │   ├── CMakeLists.txt        # CMake 构建配置
│       │   ├── native_bridge.cpp     # JNI 桥接实现
│       │   ├── android_raop_callbacks.h   # RAOP 回调头文件 (帧池 + JNI 缓存)
│       │   ├── android_raop_callbacks.c   # RAOP 回调实现 + 帧池 + IDR 保护
│       │   └── android_dnssd_shim.c  # DNS-SD 适配层
│       ├── java/com/atarayocast/app/
│       │   ├── AirCastApp.kt         # Application 类
│       │   ├── MainActivity.kt       # 主界面 (自动全屏 + 刷新 + 断开连接)
│       │   ├── SettingsActivity.kt   # 设置界面
│       │   ├── audio/
│       │   │   └── AudioPlayer.kt    # 音频播放器 (AirPlay 增益控制)
│       │   ├── bridge/
│       │   │   ├── NativeBridge.kt   # JNI 桥接声明 (含 restartHttpd)
│       │   │   └── NativeCallbacks.kt # Native 回调接口
│       │   ├── data/
│       │   │   └── AppPrefs.kt       # DataStore 偏好存储
│       │   ├── dlna/
│       │   │   ├── DlnaManager.kt    # DLNA 管理器
│       │   │   ├── DlnaMediaPlayer.kt # ExoPlayer 封装
│       │   │   ├── SsdServer.kt      # SSDP 发现服务
│       │   │   ├── UpnpHttpServer.kt # UPnP HTTP 服务器
│       │   │   └── UpnpXmlBuilder.kt # UPnP XML 构建
│       │   ├── service/
│       │   │   ├── AirCastService.kt # 核心前台服务 (含 disconnectClient)
│       │   │   ├── AirPlayRegistrar.kt # mDNS 注册
│       │   │   └── BootReceiver.kt   # 开机自启
│       │   ├── ui/main/
│       │   │   └── MainViewModel.kt # 主界面 ViewModel
│       │   ├── util/
│       │   │   └── Constants.kt      # 常量 + 分辨率枚举
│       │   └── video/
│       │       └── VideoDecoder.kt   # MediaCodec 视频解码器 (IDR 保护 + 色彩元数据)
│       └── res/
│           ├── drawable/             # 图形资源
│           ├── layout/               # 布局文件
│           ├── mipmap-*/             # 启动器图标
│           ├── values/
│           │   ├── colors.xml        # 颜色定义
│           │   ├── strings.xml       # 字符串资源
│           │   └── themes.xml        # 主题
│           └── xml/
│               ├── network_security_config.xml
│               ├── backup_rules.xml
│               └── data_extraction_rules.xml
├── docs/
│   └── video-optimization-plan.html # 视频优化方案文档
├── gradle/
│   ├── libs.versions.toml            # 依赖版本目录
│   └── wrapper/                      # Gradle Wrapper
├── build.gradle.kts                  # 根构建配置
├── settings.gradle.kts               # 项目设置
├── gradle.properties                 # Gradle 属性
├── build.sh                          # 构建脚本
└── .gitignore
```

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **语言** | Kotlin | 2.0.21 |
| **语言** | C / C++ | C17 / C++17 |
| **构建** | Gradle + AGP | 8.11.1 + 8.7.3 |
| **NDK** | Android NDK | 27.0.12077973 |
| **CMake** | | 3.22.1 |
| **JDK** | | 21 (Android Studio JBR) |
| **UI** | Material Design 3 | 1.12.0 |
| **导航** | AndroidX Navigation | 2.7.7 |
| **生命周期** | AndroidX Lifecycle | 2.8.4 |
| **偏好存储** | DataStore Preferences | 1.1.1 |
| **媒体** | Media3 ExoPlayer | 1.4.1 |
| **协程** | Kotlin Coroutines | 1.8.1 |
| **加密** | OpenSSL | 3.4.4 |

---

## 开发日志

详细开发日志见 [CHANGELOG.md](CHANGELOG.md)。

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-07-04 | 基线版本：AirPlay + DLNA + 全部功能 |
| v0.2 | 2026-07-04 | 视频优化 + 音量控制 + UI 增强 + Bug 修复 |
| v0.3 | 2026-07-04 | 60fps 协商 + 启动/持续伪影修复 + 开发环境可移植性 |
| v0.3.1 | 2026-07-05 | 高分辨率黑屏修复 + H.265 协商实验保护 + 设置页运行中锁定 |
| v0.4.0 | 2026-07-05 | DLNA 可用性重构 + 接收端本地控制 + 新应用图标 |
| v0.7.0 | 2026-07-12 | UI/UX 重构 + Android 8 解码兼容 + TextureView 显示链路 + 宽屏首页适配 |

---

## 许可证

本项目使用 GPL-3.0 许可证（因 UxPlay 依赖为 GPL-3.0）。
