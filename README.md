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
- **纯原生实现** — 无外部 DLNA 库依赖，全部使用 Android API

### 高级功能

- **分辨率自适应** — 自动检测设备屏幕分辨率（上限 4K），也支持手动选择 9 种分辨率
- **画中画 (PiP)** — 投屏中按 Home 键进入画中画模式
- **防息屏** — WakeLock 保持 CPU 唤醒，FLAG_KEEP_SCREEN_ON 保持屏幕常亮
- **全屏沉浸** — 隐藏系统栏，控制浮层 8 秒自动隐藏
- **调试覆盖层** — 实时显示 FPS、码率、编码器、分辨率
- **开机自启** — 支持 BootReceiver 开机自动启动服务
- **通知栏控制** — 前台服务通知，含停止按钮，点击回到应用

### 性能优化

- **DirectByteBuffer 帧池** — 8 x 4MB 预分配缓冲池，零 per-frame 内存分配，消除 ~120MB/s GC 压力
- **MediaCodec 异步回调** — 无轮询，codec 就绪时自动拉取帧
- **NAL 零拷贝解析** — 使用偏移引用代替 ByteArray 复制
- **低延迟模式** — MediaCodec KEY_LOW_LATENCY (API 30+)
- **UPnP 线程池** — CachedThreadPool，最大 50 并发连接

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
| **UI** | `MainActivity.kt` | SurfaceView 渲染、全屏控制、PiP、调试覆盖层 |
| **UI** | `SettingsActivity.kt` | 设备名、分辨率、H.265、PIN、画中画等设置 |
| **服务** | `AirCastService.kt` | 前台服务，实现 `NativeCallbacks`，协调所有子系统 |
| **视频** | `VideoDecoder.kt` | MediaCodec 异步解码 H.264/H.265，NAL 解析提取 CSD |
| **音频** | `AudioPlayer.kt` | PCM 直写 / ALAC 软解 / AAC 硬解，音频焦点管理 |
| **JNI** | `NativeBridge.kt` | JNI 声明 + DirectByteBuffer 帧池管理 |
| **JNI** | `NativeCallbacks.kt` | 19 个 native → Kotlin 回调接口 |
| **mDNS** | `AirPlayRegistrar.kt` | NsdManager 注册 RAOP/AirPlay 服务 |
| **DLNA** | `DlnaManager.kt` | SSDP + SOAP/HTTP + ExoPlayer |
| **DLNA** | `SsdServer.kt` | SSDP 多播发现 |
| **DLNA** | `UpnpHttpServer.kt` | UPnP 设备描述 + SOAP 控制 + GENA 事件 |
| **原生** | `native_bridge.cpp` | JNI 实现：RAOP 生命周期、帧池、ALAC 解码 |
| **原生** | `android_raop_callbacks.c` | RAOP 回调 → JNI → Kotlin，帧池管理 |
| **原生** | `android_dnssd_shim.c` | DNS-SD TXT 记录构建（适配 Android NsdManager） |

### 视频管线

```
macOS/iOS
  │ AirPlay H.264/H.265 NAL stream
  ▼
UxPlay RAOP (C)
  │ _video_process callback
  ▼
Frame Pool (sem_wait → memcpy → ByteBuffer)
  │ DirectByteBuffer (zero-copy)
  ▼
NativeCallbacks.onVideoData() [Kotlin]
  │
  ▼
VideoDecoder.feedFrame()
  │ NAL parse → extract SPS/PPS/VPS → CSD
  ▼
MediaCodec async callback
  │ onInputBufferAvailable → feed pendingFrame
  │ onOutputBufferAvailable → releaseOutputBuffer(true) → Surface
  ▼
SurfaceView (硬件渲染)
  │
  ▼
nativeBridge.returnFrameBuffer() → 帧归还池
```

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

原生依赖库需要放在 `AIRCAST_DEPS_DIR` 指定的目录（默认为项目内 `third_party/`，可通过 CMake 参数覆盖）：

```
aircast-deps/
├── UxPlay-master/       # UxPlay 源码 (lib/ 目录)
├── openssl-cmake-3/      # OpenSSL CMake 构建脚本
├── libplist/             # libplist 源码 (src/ + libcnary/ + include/)
└── alac/                 # Apple ALAC 解码器 (codec/ 目录)
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
3. **DLNA 投屏** — 在支持 DLNA 的应用中选择设备名称
4. **全屏模式** — 投屏时点击屏幕显示控制栏，点击全屏按钮
5. **画中画** — 投屏中按 Home 键进入 PiP 模式
6. **停止服务** — 点击通知栏的停止按钮或应用内的停止按钮

### 设置项

| 设置 | 说明 | 默认值 |
|------|------|--------|
| 设备名称 | mDNS 广播的设备名 | Atarayo-Cast-Android |
| 分辨率 | 投屏分辨率（自动 / 9种手动选项） | 自动 |
| 自适应分辨率 | 自动检测屏幕原生分辨率 | 开启 |
| H.265/HEVC | 启用 HEVC 编码支持（4K） | 关闭 |
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

- **网络安全** — `network_security_config.xml` 默认禁用明文流量，仅允许 `localhost` 和 `.local` 域名使用明文（AirPlay/DLNA 本地通信需要）
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
│       │   ├── android_raop_callbacks.h   # RAOP 回调头文件
│       │   ├── android_raop_callbacks.c   # RAOP 回调实现 + 帧池
│       │   └── android_dnssd_shim.c  # DNS-SD 适配层
│       ├── java/com/atarayocast/app/
│       │   ├── AirCastApp.kt         # Application 类
│       │   ├── MainActivity.kt       # 主界面
│       │   ├── SettingsActivity.kt   # 设置界面
│       │   ├── audio/
│       │   │   └── AudioPlayer.kt    # 音频播放器
│       │   ├── bridge/
│       │   │   ├── NativeBridge.kt   # JNI 桥接声明
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
│       │   │   ├── AirCastService.kt # 核心前台服务
│       │   │   ├── AirPlayRegistrar.kt # mDNS 注册
│       │   │   └── BootReceiver.kt   # 开机自启
│       │   ├── ui/main/
│       │   │   └── MainViewModel.kt # 主界面 ViewModel
│       │   ├── util/
│       │   │   └── Constants.kt      # 常量 + 分辨率枚举
│       │   └── video/
│       │       └── VideoDecoder.kt   # MediaCodec 视频解码器
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

## 许可证声明

本项目使用并链接以下开源软件：

- **UxPlay** — GPL-3.0 许可证
- **OpenSSL** — Apache-2.0 许可证
- **libplist** — LGPL-2.1 许可证
- **Apple ALAC Decoder** — Apache-2.0 许可证
- **llhttp** — MIT 许可证

用户在使用和分发本项目时应遵守上述许可证的条款。

---

## 测试设备

- **Lenovo YT-K606F** (Android 平板)
  - 分辨率: 2160x1350 (16:10)
  - 连接方式: ADB over Wi-Fi (192.168.31.212:45523)
  - 端到端验证: macOS → AirPlay → 屏幕镜像成功
