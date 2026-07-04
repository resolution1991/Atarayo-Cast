# 开发日志

记录 Atarayo-Cast 从项目创建到 v0.1 基线的完整开发过程。

---

## v0.1 — 基线版本 (2026-07-04)

### Phase 0: 项目骨架 (2026-07-03)

**目标：** 创建 Android 项目骨架，生成首版可运行 APK。

**完成内容：**
- 创建 Android 项目结构（Kotlin + Gradle + AGP）
- 配置 `build.gradle.kts`：compileSdk 36, minSdk 24, targetSdk 36
- 配置 NDK + CMake 原生构建支持
- 创建 `MainActivity`、`AirCastApp`、基础布局
- 首版 APK 编译成功并安装到测试设备

---

### Phase 1: AirPlay 接收端 (2026-07-03)

**目标：** 基于 UxPlay 实现完整的 AirPlay 接收端，支持 macOS → Android 屏幕镜像。

#### 1.1 UxPlay 编译集成

**挑战：** UxPlay 是 C 语言项目，依赖多个外部库，需要交叉编译到 Android ABI。

**解决方案：**
- **OpenSSL 3.4.4** — 使用 [openssl-cmake](https://github.com/openssl/openssl-cmake) 从源码交叉编译，通过 CMake 子项目集成
- **libplist 2.6.0** — 从 [libimobiledevice/libplist](https://github.com/libimobiledevice/libplist) 源码直接编译，包含 `libcnary` 容器库
- **llhttp** — UxPlay 内嵌的 HTTP 解析器，直接编译 `.c` 源文件
- **playfair** — UxPlay 内嵌的 FairPlay 解密库，直接编译
- **Apple ALAC** — Apple 官方 ALAC 参考解码器（Apache-2.0），用于 AirPlay Audio 的 ALAC 解码

**CMakeLists.txt 设计：**
- 所有库编译为静态库（`STATIC`），最终链接进 `aircast_native.so`
- 使用 `AIRCAST_DEPS_DIR` 变量指向依赖目录，支持命令行覆盖
- `-ffile-prefix-map` 去除构建路径信息
- 三个 ABI 均支持：`arm64-v8a`、`armeabi-v7a`、`x86_64`

**编译警告处理：**
- libplist 的 `libcnary/cnary.c` 有 9 个 `-Wincompatible-pointer-types` 警告（`node_t*` vs `node_t` 类型不匹配），不影响功能
- OpenSSL CMake 有 SDK XML 版本兼容警告（SDK XML v4 vs 解析器支持 v3），不影响编译

#### 1.2 JNI 桥接层

**设计：** Kotlin ↔ C/C++ 的完整 JNI 桥接。

**`native_bridge.cpp` 核心结构：**
```c
typedef struct {
    raop_t *raop;                    // UxPlay RAOP 实例
    dnssd_t *dnssd;                  // DNS-SD 上下文
    android_callback_ctx_t cb_ctx;  // 回调上下文 + 帧池
    raop_callbacks_t callbacks;     // RAOP 回调表
    char hw_addr[6];                // 设备硬件地址
} server_ctx_t;
```

**JNI 函数清单（17 个）：**

| 函数 | 职责 |
|------|------|
| `nativeInit` | 创建 RAOP 实例，注册回调，生成 PIN |
| `nativeStart` | 启动 RAOP HTTPD，注册 DNS-SD 记录 |
| `nativeStop` | 停止 HTTPD，注销 DNS-SD |
| `nativeDestroy` | 销毁帧池/RAOP/dnssd |
| `nativeSetDisplaySize` | 设置 width/height/refreshRate plist |
| `nativeSetH265Enabled` | H.265 标志 + DNS-SD feature bit 42 |
| `nativeSetCodecs` | ALAC/AAC 编解码器声明 |
| `nativeSetPinAuth` | PIN 认证（raop_set_plist） |
| `nativeSetPlist` | 任意 plist 键值设置 |
| `nativeGetRaopTxtRecords` | RAOP DNS-SD TXT 记录 → HashMap |
| `nativeGetAirplayTxtRecords` | AirPlay DNS-SD TXT 记录 → HashMap |
| `nativeGetRaopServiceName` | RAOP 服务名 |
| `nativeGetServerName` | AirPlay 服务名 |
| `nativeInitFramePool` | DirectByteBuffer 帧池初始化 |
| `nativeReturnFrameBuffer` | 帧归还池 |
| `nativeAlacInit` | Apple ALACDecoder 初始化 |
| `nativeAlacDecode` | ALAC → PCM 解码 |
| `nativeAlacDestroy` | ALACDecoder 销毁 |

**日志映射：** UxPlay 使用 `syslog` 级别，映射到 Android `__android_log_print`：
- LOG_EMERG/ALERT/CRIT → ANDROID_LOG_FATAL
- LOG_ERR → ANDROID_LOG_ERROR
- LOG_WARNING → ANDROID_LOG_WARN
- LOG_NOTICE/INFO → ANDROID_LOG_INFO
- LOG_DEBUG → ANDROID_LOG_DEBUG

#### 1.3 RAOP 回调实现

**`android_raop_callbacks.c` — RAOP 回调到 JNI：**

定义 20 个 JNI methodID（对应 `NativeCallbacks.kt` 接口的所有方法），在 `android_callbacks_init` 中通过 `GetMethodID` 缓存。

**线程安全处理：**
- RAOP 在内部 pthread 中调用回调
- 使用 `AttachCurrentThread` 将原生线程附加到 JVM
- 使用 pthread TLS（Thread-Local Storage）+ destructor 自动跟踪 attach 状态，线程退出时自动 `DetachCurrentThread`
- 避免重复 attach / 忘记 detach 导致的内存泄漏

#### 1.4 DNS-SD 适配

**`android_dnssd_shim.c` — DNS-SD 适配层：**

UxPlay 原本使用系统 DNS-SD API（mdns.h），但 Android 不提供这些 C API。适配层做了两件事：
1. **TXT 记录构建** — 在内存中构建 DNS-SD TXT 记录，通过 JNI 暴露给 Kotlin 层
2. **空桩函数** — `dnssd_register`/`dnssd_unregister` 等函数为空桩，实际 mDNS 注册由 Kotlin 层的 `NsdManager` 完成

**`AirPlayRegistrar.kt` — Kotlin mDNS 注册：**
- 从 `NativeBridge` 读取 TXT 记录和服务名
- 使用 `NsdManager.registerService()` 注册 `_raop._tcp` 和 `_airplay._tcp`
- 保存 listener 引用，`unregister()` 时正确调用 `nsdManager.unregisterService()`
- 注册失败时最多重试 3 次，指数退避（attempt * 2000ms）

#### 1.5 视频解码

**MediaCodec H.264/H.265 硬件解码：**
- 从 AirPlay NAL 流提取 SPS/PPS（H.264）或 VPS/SPS/PPS（H.265）作为 CSD（Codec-Specific Data）
- 配置 MediaCodec 输出到 SurfaceView 的 Surface
- `releaseOutputBuffer(index, true)` 渲染到 Surface

#### 1.6 FairPlay/PIN 认证

- **FairPlay** — playfair 库处理 FairPlay 加密握手
- **PIN 认证** — `random_pin()` 生成随机 4 位 PIN，通过 `onDisplayPin` 回调通知 UI，通知栏显示 PIN
- **硬件地址** — 使用 `Settings.Secure.ANDROID_ID` 的 MD5 前 6 字节作为模拟硬件地址

#### 1.7 端到端验证

- macOS → AirPlay → Lenovo YT-K606F 屏幕镜像成功
- 视频渲染正常，延迟可接受

---

### Phase 2: DLNA 接收端 (2026-07-03)

**目标：** 实现 DLNA Media Renderer，接收 DLNA 推送的媒体。

**设计决策：** 不使用 Cling 库，纯 Android API 实现完整 UPnP 协议栈。

**理由：**
- Cling 依赖庞大（~2MB），且已停止维护
- DLNA Media Renderer 只需实现 SSDP 发现 + SOAP 控制 + 媒体播放
- 纯 API 实现更轻量，且对协议细节有完全控制

#### 2.1 SSDP 发现 (`SsdServer.kt`)

- **MulticastSocket** 加入 `239.255.255.250:1900` 多播组
- **M-SEARCH 响应** — 收到搜索请求时回复 200 OK + 设备描述 URL
- **NOTIFY alive/byebye** — 周期性发送 alive 通知，停止时发送 byebye
- 需要 `MulticastLock` 防止 Wi-Fi 休眠中断多播

#### 2.2 HTTP 设备描述 (`UpnpHttpServer.kt`)

- **ServerSocket** 监听端口 8090
- **GET /desc.xml** — 返回设备描述 XML（UDN、friendlyName、serviceList）
- **GET /ConnectionManager.xml** — ConnectionManager 服务描述（SCPD）
- **GET /AVTransport.xml** — AVTransport 服务描述
- **GET /RenderingControl.xml** — RenderingControl 服务描述

#### 2.3 SOAP 控制

实现以下 UPnP 动作：

**ConnectionManager:**
- `GetProtocolInfo` — 返回支持的协议
- `GetCurrentConnectionIDs` — 返回当前连接 ID

**AVTransport:**
- `SetAVTransportURI` — 设置媒体 URI（ExoPlayer 准备播放）
- `Play` — 开始播放
- `Pause` — 暂停
- `Stop` — 停止
- `Seek` — 跳转
- `GetPositionInfo` — 返回当前播放位置/时长
- `GetTransportInfo` — 返回当前传输状态
- `GetMediaInfo` — 返回当前媒体信息

**RenderingControl:**
- `GetVolume` / `SetVolume` — 音量控制
- `GetMute` / `SetMute` — 静音控制

#### 2.4 GENA 事件通知

- **SUBSCRIBE** — 接收订阅请求，记录 subscriber callback URL
- **UNSUBSCRIBE** — 移除订阅者
- **NOTIFY 推送** — 状态变化时通过 HTTP NOTIFY 发送到所有订阅者
- **LastChange XML** — 构建 AVTransport + RenderingControl 的 LastChange 事件 XML
- 订阅者使用 `ConcurrentHashMap` 保证线程安全

#### 2.5 媒体播放

- `DlnaMediaPlayer.kt` 封装 Media3 ExoPlayer
- `setSurface(Surface?)` 支持绑定到 SurfaceView
- 支持 HTTP/HTTPS 媒体 URL
- 与 AirCastService 通过 `LocalBinder.setSurface` 双通道（同时设置 VideoDecoder 和 DlnaMediaPlayer 的 Surface）

---

### Phase 3: 高级功能 (2026-07-04)

#### 3.1 AirPlay 音频播放 (`AudioPlayer.kt`)

**支持的音频编码：**

| ct 值 | 编码 | 解码方式 | 典型场景 |
|-------|------|----------|----------|
| 0 | PCM 16-bit LE | AudioTrack 直写 | 原始 PCM |
| 2 | ALAC | NativeBridge 软件 ALAC 解码器 | AirPlay 音乐 |
| 4 | AAC-LC | MediaCodec 硬件解码 | AAC-LC |
| 8 | AAC-ELD | MediaCodec 硬件解码 | macOS 屏幕镜像 |

**ALAC 软解码：**
- 使用 Apple ALAC 参考解码器（C++ 实现）
- 构建 24 字节 `ALACSpecificConfig`（大端序）：
  - frameLength=4096, compatibleVersion=0, bitDepth=16
  - pb=2, mb=2, kb=1, numChannels=2, maxRun=65535
  - maxFrameBytes=0, avgBitRate=0, sampleRate=44100
- `ALACDecoder::Init` 初始化解码器
- `ALACDecoder::Decode` 通过 `BitBuffer` 接口解码 ALAC → PCM

**AAC 硬解码：**
- MediaCodec `"audio/mp4a-latm"` 解码器
- AAC-ELD AudioSpecificConfig: `[0xF8, 0xE8, 0x50, 0x00]`（44100Hz/2ch/ELD）
- AAC-LC AudioSpecificConfig: `[0x12, 0x10]`（44100Hz/2ch/LC）
- 非阻塞 `dequeueInputBuffer/dequeueOutputBuffer`，最多迭代 16 次

**AudioTrack 配置：**
- 44100 Hz, 立体声, 16-bit PCM
- `PERFORMANCE_MODE_LOW_LATENCY` (API 26+)
- 线程安全：所有方法 `synchronized`

**音频焦点管理：**
- 请求 `AUDIOFOCUS_GAIN`（API 26+ 使用 `AudioFocusRequest`）
- 焦点丢失：暂停播放
- 瞬态焦点丢失：降低音量（ducking）
- 焦点恢复：恢复播放和音量

#### 3.2 音量控制联动

- `onVolumeChange(volume: Float)` 回调
- 0.0–1.0 浮点映射到 `AudioManager.setStreamVolume(STREAM_MUSIC, int, 0)`
- 反向：系统音量变化也可通过 `AudioManager` 查询

#### 3.3 画中画 (PiP)

- `onUserLeaveHint()` — 投屏中 + PiP 开启时进入 PiP
- `PictureInPictureParams` 16:9 宽高比
- `onPictureInPictureModeChanged()` — PiP 中隐藏所有控件，退出时恢复

#### 3.4 全屏沉浸模式

- `FLAG_FULLSCREEN` + `FLAG_LAYOUT_NO_LIMITS` 隐藏状态栏
- `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 沉浸式（API 30 以下）
- `WindowInsetsControllerCompat` 沉浸式（API 30+）
- 控制浮层 8 秒自动隐藏（`Handler.postDelayed`）
- 全屏时触摸 Surface 切换控制浮层显隐

#### 3.5 防息屏

- `WakeLock` — `PARTIAL_WAKE_LOCK`，无超时，整个投屏会话期间持有
- `FLAG_KEEP_SCREEN_ON` — SurfaceView 保持屏幕常亮

#### 3.6 通知栏

- 低优先级持久通知（`IMPORTANCE_LOW`）
- 点击回到应用（`PendingIntent` → `MainActivity`）
- 含「停止」操作按钮
- PIN 认证时临时升级为高优先级通知，显示 PIN 码

#### 3.7 调试覆盖层

- 实时统计 FPS、码率（Mbps）、编码器（H.264/H.265）、分辨率
- `AirCastService` 中 `@Volatile` 字段，每秒更新
- `MainActivity.updateDebugOverlay()` 每秒读取并格式化显示
- 等宽字体，半透明背景

#### 3.8 设置界面

使用 `DataStore Preferences` 持久化，PreferenceFragment 风格的 Material Design 3 布局：

| 设置项 | 类型 | 默认值 |
|--------|------|--------|
| 设备名称 | 文本对话框 | Atarayo-Cast-Android |
| 分辨率 | 单选对话框 | 自动 |
| 自适应分辨率 | Switch | 开启 |
| H.265/HEVC | Switch | 关闭 |
| 防息屏 | Switch | 开启 |
| 默认全屏 | Switch | 关闭 |
| PIN 认证 | Switch + PIN 对话框 | 关闭 |
| 开机自启 | Switch | 关闭 |
| 画中画 | Switch | 开启 |
| 调试信息 | Switch | 关闭 |

#### 3.9 开机自启

- `BootReceiver` 监听 `BOOT_COMPLETED`
- 检查 `bootStart` 偏好，如开启则自动 `AirCastService.start()`

---

### Bug 修复 (2026-07-04)

#### Bug 1: AirPlay 投屏无声音

**现象：** macOS 屏幕镜像投屏成功，但选择被投屏设备作为音频输出时没有声音。

**根因：** macOS 屏幕镜像使用 AAC-ELD (ct=8) 音频编码发送音频，而 `AudioPlayer` 只支持 ct=0 (PCM) 和 ct=2 (ALAC)，AAC-ELD 帧被静默丢弃。

**修复：**
- `AudioPlayer.kt` 新增 `initAacDecoder(ct)` 方法，创建 `MediaCodec "audio/mp4a-latm"` 解码器
- AAC-ELD AudioSpecificConfig: `[0xF8, 0xE8, 0x50, 0x00]`
- AAC-LC AudioSpecificConfig: `[0x12, 0x10]`
- 新增 `feedAacFrame()` 非阻塞解码方法
- 新增 `releaseAacDecoder()` 清理方法
- `stop()` / `release()` 中加入 AAC 解码器清理

#### Bug 2: 重启服务后无法投屏

**现象：** 停止投屏后重新「启动服务」，macOS 端显示已投屏，但安卓端没有画面。

**根因：** `stopAirCast()` 调用 `videoDecoder.release()`，设置 `released = true` 并退出 HandlerThread。服务未销毁时重新 `startAirCast()` 使用同一 `VideoDecoder` 实例，所有 `decodeFrame()` 调用因 `released.get()` 为 true 而提前返回。

**修复：**
- 新增 `VideoDecoder.stop()` 方法：释放 codec 但不设 `released` 标志、不退出 handler 线程
- 新增 `VideoDecoder.resetIfNeeded()` 方法：如已 released 则重建 handler 线程
- `handlerThread` / `handler` 改为 `var` 以支持重建
- `AirCastService.stopAirCast()` 改用 `videoDecoder.stop()` 代替 `release()`
- `AirCastService.startAirCast()` 开头调用 `videoDecoder.resetIfNeeded()`
- `onConnectionDestroy()` 添加 `videoDecoder.stop()` 释放 codec

#### Bug 3: 分辨率自适应不支持原生分辨率

**现象：** 设备屏幕为 2160x1350，但自适应分辨率被设置为 1920x1080。

**根因：** `detectDeviceResolution()` 将设备分辨率四舍五入到预设值。2160 >= 1920 → 返回 P1080_60 (1920x1080)。

**修复：**
- 新增 `DisplaySize` data class 替代 `Resolution` 枚举作为返回值
- `detectDeviceResolution()` 直接返回实际设备分辨率（确保偶数、上限 4K）
- 分辨率 <= 2560x1600 时使用 60fps，更高用 30fps
- `startAirCast()` 分别处理自适应和固定分辨率路径

---

### 功能调整 (2026-07-04)

#### 放弃的功能

| 功能 | 原因 |
|------|------|
| Google Cast | 实现成本高，AirPlay + DLNA 已覆盖主要使用场景 |
| 密码认证 | PIN 认证已满足安全需求，密码认证冗余 |
| 音乐元数据/封面/进度/DACP | 保持日志输出，UI 不展示 |
| 屏幕录制 | 与投屏接收端定位不符 |

#### 自定义 PIN 码生效

**问题：** `nativeSetPinAuth` 原为空桩函数，用户设置的 PIN 码不生效。

**修复：**
- `native_bridge.cpp` `nativeSetPinAuth` 从空桩改为实际调用 `raop_set_plist(ctx->raop, "pin", pin_val + 10000)`
- 从 JNI 字符串解析用户 PIN，验证为 4 位数字
- 禁用时 `raop_set_plist(ctx->raop, "pin", 0)` 清除 PIN
- `AirCastService.kt` 读取 `pinCode` 偏好并在 `initialize` 后调用 `nativeBridge.setPinAuth(true, pinCode)`

#### NSD 服务注销修复

**问题：** `AirPlayRegistrar.unregister()` 没有调用 `nsdManager.unregisterService()`，导致 mDNS 服务未被注销。

**修复：**
- 保存 `raopListener` 和 `airplayListener` 为成员变量
- `registerService()` 中记录 listener 引用
- `unregister()` 中调用 `nsdManager.unregisterService(listener)` 真正注销

#### 原生回调桩函数实现

**问题：** `video_pause`/`resume`/`reset`/`audio_flush`/`video_flush` 5 个回调为空桩，仅打日志。

**修复：**
- `NativeCallbacks.kt` 新增 5 个回调方法
- `android_raop_callbacks.h` 结构体新增 5 个 jmethodID
- `android_raop_callbacks.c` 中 `android_callbacks_init` 获取新方法 ID
- 桩函数改为通过 JNI 调用 Kotlin 回调
- `AirCastService.kt` 实现回调处理：暂停/重置/flush 时停止 videoDecoder，音频 flush 时清空 audioPlayer

---

### Phase 4: 性能优化 (2026-07-04)

#### 4.1 DirectByteBuffer 帧缓冲池

**问题：** 每帧视频在 native 层调用 `NewByteArray` + `SetByteArrayRegion`（1080p 约 2MB，60fps 下约 120MB/s 堆分配），导致频繁 GC 暂停和帧丢失。

**解决方案：** 预分配 DirectByteBuffer 池，native 层直接 `memcpy` 写入，零 per-frame 分配。

**实现细节：**

`android_raop_callbacks.h`：
```c
#define FRAME_POOL_SIZE 8
#define FRAME_BUFFER_SIZE (4 * 1024 * 1024)  // 4 MB

typedef struct {
    // ...
    jobject frame_buffers[FRAME_POOL_SIZE];   // GlobalRef to DirectByteBuffer
    uint8_t *frame_addrs[FRAME_POOL_SIZE];     // Native addresses
    int frame_pool_free[FRAME_POOL_SIZE];       // Free stack
    int frame_pool_head;                        // Stack top
    pthread_mutex_t frame_pool_lock;            // Protects free stack
    sem_t frame_pool_sem;                       // Counting semaphore
    int frame_pool_initialized;
} android_callback_ctx_t;
```

`android_raop_callbacks.c` — `_video_process` 重写：
```c
// 1. 获取空闲 buffer (阻塞等待)
sem_wait(&ctx->frame_pool_sem);
pthread_mutex_lock(&ctx->frame_pool_lock);
int idx = ctx->frame_pool_free[--ctx->frame_pool_head];
pthread_mutex_unlock(&ctx->frame_pool_lock);

// 2. memcpy 帧数据到 DirectByteBuffer
memcpy(ctx->frame_addrs[idx], data, len);

// 3. 设置 ByteBuffer position/limit
(*env)->SetShortField(env, ctx->frame_buffers[idx], pos_id, 0);
(*env)->SetShortField(env, ctx->frame_buffers[idx], limit_id, len);

// 4. JNI 传给 Java
(*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
    ctx->frame_buffers[idx], ntp_time, is_h265);
```

`native_bridge.cpp` — `nativeInitFramePool`：
- 接收 Java `ByteBuffer[]` 数组
- 为每个 buffer 创建 `GlobalRef`
- 通过 `GetDirectBufferAddress` 获取原生地址

`native_bridge.cpp` — `nativeReturnFrameBuffer`：
- 通过 `IsSameObject` 匹配归还的 buffer
- 将索引推回空闲栈
- `sem_post` 释放信号量

`NativeBridge.kt` — `initialize()`：
```kotlin
val buffers = Array(POOL_SIZE) { ByteBuffer.allocateDirect(BUFFER_SIZE) }
nativeInitFramePool(nativeHandle, buffers)
```

**效果：** 消除 ~120MB/s 的 per-frame jbyteArray 分配和 GC 压力。

#### 4.2 MediaCodec 异步回调模式

**问题：** 同步 `dequeueInputBuffer`/`dequeueOutputBuffer` 需要轮询，增加延迟。

**解决方案：** `MediaCodec.setCallback()` 异步模式，codec 主动通知缓冲区可用。

**`VideoDecoder.kt` 重写：**

```kotlin
private inner class DecoderCallback : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        // 从 pendingFrame 直接写入 codec 输入缓冲
        val inputBuffer = codec.getInputBuffer(index) ?: return
        inputBuffer.clear()
        pendingFrame?.let { frame ->
            inputBuffer.put(frame.data)
            codec.queueInputBuffer(index, 0, frame.length, ...)
            // 帧消费后归还到池
            nativeBridge.returnFrameBuffer(frame.data)
            pendingFrame = null
        }
    }

    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
        // 直接渲染到 Surface
        codec.releaseOutputBuffer(index, true)
    }
}
```

**NAL 解析优化：**
- 使用 `NalRef(offset, length, data)` 偏移引用，避免临时 `ByteArray` 拷贝
- `parseNalRefs()` 扫描 Annex B 起始码（`00 00 00 01` 或 `00 00 01`），记录偏移和长度
- `csdFromNalRefs()` 从 NAL 引用构建 CSD 字节数组

**帧覆盖策略：**
- `pendingFrame` 使用最新帧覆盖旧帧（实时流场景：丢旧帧保新帧）
- 避免帧积压导致的延迟累积

**低延迟模式：**
- `MediaFormat.KEY_LOW_LATENCY = 1` (API 30+)
- 减少解码器内部缓冲，降低端到端延迟

#### 4.3 分辨率系统重构

**原问题：** 手动模式限制在设备原生分辨率以下，分辨率选项不足。

**重构：**

`Constants.kt` Resolution 枚举扩展为 9 个选项：
```kotlin
enum class Resolution(val key: String, val width: Int, val height: Int, val fps: Int, val aspectLabel: String) {
    AUTO("auto", 0, 0, 60, "自动"),
    P4K_2160("3840x2160", 3840, 2160, 30, "16:9"),
    P2560_1600("2560x1600", 2560, 1600, 60, "16:10"),
    P2560_1440("2560x1440", 2560, 1440, 60, "16:9"),
    P2160_1350("2160x1350", 2160, 1350, 60, "16:10"),
    P1920_1200("1920x1200", 1920, 1200, 60, "16:10"),
    P1920_1080("1920x1080", 1920, 1080, 60, "16:9"),
    P1080_675("1080x675", 1080, 675, 60, "16:10"),
    P1280_720("1280x720", 1280, 720, 60, "16:9");

    val displayLabel: String
        get() = if (this == AUTO) "自动 (原生分辨率)"
        else "${width}x${height} ($aspectLabel, ${fps}fps)"
}
```

- 手动模式不限制设备分辨率（用户可选择 4K 即使设备只有 1080p）
- 设置对话框显示 `displayLabel`：如 "2160x1350 (16:10, 60fps)"
- FPS 自动计算：4K+ 用 30fps，其余用 60fps

---

### Phase 5: 稳定性与安全 (2026-07-04)

#### 5.1 安全修复

| 问题 | 修复 |
|------|------|
| PIN fallback 为 1234 | `random_pin()` 失败时禁用 PIN（requirePin=false） |
| JNI 字符串 NULL 未检查 | 所有 `GetStringUTFChars` 添加 NULL 检查 |
| 全局明文流量 | `network_security_config.xml`，仅 localhost/.local 允许明文 |
| UPnP XML 注入 | `xmlEscape()` 方法转义特殊字符 |
| ALACDecoder new 失败 | try-catch `std::bad_alloc` 替代无效的 `if (!dec)` |

#### 5.2 线程安全

| 问题 | 修复 |
|------|------|
| GENA 订阅者非线程安全 | `mutableMapOf` → `ConcurrentHashMap` |
| JNI AttachCurrentThread 泄漏 | pthread TLS + destructor 自动 detach |

#### 5.3 稳定性

| 问题 | 修复 |
|------|------|
| WakeLock 10 分钟超时 | 移除超时，整个会话期间持有 |
| DLNA 启动失败不重试 | 最多重试 3 次，间隔 2 秒 |
| mDNS 注册失败不重试 | 最多重试 3 次，指数退避 |
| 异常被吞噬 | catch 块添加日志记录 |
| AAC 编解码器未声明 | `setCodecs(aac = true)` |

#### 5.4 UPnP 优化

| 优化 | 详情 |
|------|------|
| 线程池化 | `Executors.newCachedThreadPool` 替代每连接裸线程 |
| 连接限制 | 最大并发 50，超限拒绝并关闭 |
| GENA 复用线程池 | `sendNotify` 从新 Thread 改为线程池 execute |

#### 5.5 编译问题

- `JNI_VERSION_1_8` 在 NDK 27 不可用 → 回退到 `JNI_VERSION_1_6`
- macOS 26 下 Gradle 8.11.1 原生库兼容问题 → `org.gradle.native=false`

---

### 项目改名 (2026-07-04)

将项目从 "AirCast" 改名为 "Atarayo-Cast"，包名从 `com.project.aircast` 改为 `com.atarayocast.app`。

**修改范围：**
- 21 个源文件中的 `package` 声明和 `import` 语句
- JNI 函数名：`Java_com_project_aircast_*` → `Java_com_atarayocast_app_*`
- `build.gradle.kts` 的 `namespace` 和 `applicationId`
- `proguard-rules.pro` 的 keep 规则
- `settings.gradle.kts` 的 `rootProject.name`
- `strings.xml` 的 `app_name`
- 源文件目录从 `com/project/aircast/` 移动到 `com/atarayocast/app/`
- `Constants.kt` 中的 Action 字符串常量

**验证：** clean build 成功，所有功能正常。

---

## v0.2 — 视频优化 + 音量控制 + UI 增强 (2026-07-04)

### 1. Surface 生命周期修复

**问题：** 进入设置页面再返回后画面崩坏（黑屏/花屏）。首次修复尝试引入 CSD 缓存和 surface 生命周期管理，反而引入了竞态条件导致首次投屏也无法显示。

**根因：**
- `setSurface(null)` 中调用 `releaseCodec()` 销毁了 mid-stream 的 codec
- 返回后 Surface 重建，但新帧不含 CSD（SPS/PPS），codec 无法重新配置
- CSD 缓存在 handler 消息队列中存在竞态

**最终修复：** Surface 切换时保持 codec 存活，不释放不重建。使用 `setOutputSurface()` (API 23+) 重新挂载 Surface。输出渲染前检查 surface 是否为空，为空则跳过渲染。

### 2. Phase A 视频性能优化

**MediaFormat 配置键补全：**
- `KEY_PRIORITY=0` (实时优先级)
- `KEY_OPERATING_RATE=120` (目标帧率×2余量)
- `KEY_FRAME_RATE=60` (输入帧率)
- `KEY_COLOR_STANDARD=BT.709` (纠正 YUV→RGB 转换矩阵)
- `KEY_COLOR_RANGE=LIMITED` (16-235 有限范围)
- `KEY_COLOR_TRANSFER=SDR_VIDEO` (BT.709 SDR 传输函数)
- `KEY_MAX_WIDTH=3840, KEY_MAX_HEIGHT=2160` (自适应播放)
- 编译错误修复：`COLOR_TRANSFER_BT709` 不存在 → 使用 `COLOR_TRANSFER_SDR_VIDEO`；`MediaCodec.getCodecInfo()` 是 API 29+ → 改用 `MediaCodecList`

**自适应播放：** `setSize()` 不再调用 `releaseCodec()`，codec 通过 SPS 自动处理分辨率变化。

**渲染时间戳 API：** `releaseOutputBuffer(index, true)` → `releaseOutputBuffer(index, 0L)`。

### 3. 块状压缩伪影修复

**问题：** 投屏画面出现块状压缩伪影（宏块化失真）。

**根因 1（Kotlin 层）：** "最新帧优先"策略盲目丢弃 pending 帧，当 IDR 关键帧被丢弃后，后续 P/B 帧丢失参考帧导致宏块伪影。
- 新增 `isIdrFrame()` 方法：扫描 NAL start codes 检测 IDR
- `handleFrame()` 中：当 pending 帧是 IDR 且新帧不是 IDR 时，不替换 pending
- `feedFrameToCodec()` 对 IDR 帧设置 `BUFFER_FLAG_KEY_FRAME` 标志

**根因 2（C 层）：** 原生帧池满时盲目丢弃所有帧（包括 IDR 关键帧）。
- 新增 `_is_idr_frame()` C 函数检测 IDR
- 新增 `_frame_pool_acquire_idr()` 函数：IDR 帧池满时 `sem_timedwait` 等待 15ms
- 非 IDR 帧池满立即丢弃

**根因 3（首帧丢失）：** `handleFrame()` 中首帧（含 SPS+PPS+IDR）被 `configureCodec()` 提取 CSD 后直接归还缓冲区。IDR slice 数据丢失。
- 修复：`configureCodec()` 后将首帧设为 pendingFrame 并立即喂给解码器

### 4. H.265 协商诊断

**问题：** H.265 设置已开启但实际仍使用 H.264。

**诊断日志发现：**
- `features bitmask = 0x4005A7FFEE6 (bit 42 = SET)` — bit 42 正确设置
- `video_set_codec: codec=1 (H264)` — macOS 仍发送 H.264
- `model=AppleTV3,2` — Apple TV 3代(2012) 不支持 HEVC

**修复尝试：** `GLOBAL_MODEL` 从 `AppleTV3,2` 改为 `AppleTV6,2` (Apple TV 4K 1代, 2017)。但 macOS 仍选择 H.264。结论：macOS 的编解码器选择逻辑是苹果私有的，已设置所有已知接收端信号（model + features bit），不再继续尝试。

### 5. 帧池扩大

- `FRAME_POOL_SIZE` 从 8 改为 16 → 最终 24
- `POOL_SIZE` 从 8 改为 16 → 最终 24
- 帧池容量三倍化，容纳更多在途帧，降低丢帧率

### 6. JNI 方法 ID 缓存

**问题：** `_video_process()` 每帧执行 `GetObjectClass` + `GetMethodID("clear")` + `GetMethodID("limit")` + `DeleteLocalRef`，30fps 下每秒 180+ 次冗余 JNI 调用。

**修复：**
- `android_raop_callbacks.h` 结构体新增 `bytebuffer_class` (GlobalRef) + `bb_clear` / `bb_limit` / `bb_position` 方法 ID
- `android_callbacks_init()` 中通过 `FindClass("java/nio/ByteBuffer")` 缓存类和方法 ID
- `_video_process()` 改用缓存的方法 ID

### 7. 解码线程优先级

`HandlerThread("VideoDecoder")` → `HandlerThread("VideoDecoder", Process.THREAD_PRIORITY_DISPLAY)`。系统负载高时减少解码延迟。

### 8. 音量控制架构

**问题 1：** 调整 Mac 音量时安卓设备系统音量归零。
- 根因：AirPlay 音量是负 dB 衰减值（0.0=最大，-144.0=静音），被误当作 0.0-1.0 线性比例直接乘以 maxVol。

**问题 2：** 纯线性映射后仍有问题——调音量键时声音突然变 100%，Mac 音量 0 时仍有声。
- 根因：控制了系统媒体音量而非投屏音量。

**最终修复：**
- **不再控制系统音量**，改用 `AudioTrack.setVolume(gain)` 控制投屏音频增益
- dB→线性：`gain = 10^(dB/20)`（0.0-1.0 连续值）
- `AudioPlayer.kt` 新增 `airplayGain` 字段 + `setVolume(gain)` 方法
- 焦点 duck：`airplayGain * 0.3f`（不覆盖 airplayGain）
- 焦点 restore：恢复 `airplayGain`（不再硬编码 1.0f）

### 9. UI 功能增强

**自动全屏：**
- 投屏连接成功时自动进入全屏
- 投屏结束时自动退出全屏

**移除遗留按钮：**
- 移除底部"暂停"按钮（btnStartStop）及底部 scrim
- 移除 `updateStartStopButton()` 方法
- 底部不再有任何按钮（全屏控制仅通过顶部工具栏）

**刷新按钮：**
- 顶部工具栏新增刷新按钮
- 点击后用 Canvas 在 Surface 上画黑色，清除画面伪影
- 不触碰 MediaCodec，下一个解码帧自然覆盖

**断开连接：**
- 关闭按钮改为断开连接而非停止服务
- 新增 `nativeRestartHttpd` JNI 方法：重启 RAOP HTTPD 断开 Mac 连接
- `disconnectClient()`：重启 HTTPD + 停止本地播放 + 状态→WAITING
- mDNS 注册保持，Mac 可立即重新连接

**"当前无投屏输入"提示：**
- 新增居中 TextView，灰色文字
- 未投屏时显示，投屏开始后隐藏

### 10. 编译修复

- `COLOR_TRANSFER_BT709` 不存在 → 使用 `COLOR_TRANSFER_SDR_VIDEO`
- `MediaCodec.getCodecInfo()` 是 API 29+ → 改用 `MediaCodecList(MediaCodecList.REGULAR_CODECS)`
- `android:drawable/ic_popup_rotate` 不存在 → 改用 `ic_menu_rotate`

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-07-04 | 基线版本：AirPlay + DLNA + 全部功能 |
| v0.2 | 2026-07-04 | 视频优化 + 音量控制 + UI 增强 + Bug 修复 |

---

## 开发环境

- **开发机:** macOS (Apple Silicon)
- **IDE:** Android Studio
- **JDK:** Android Studio JBR (JDK 21)
- **NDK:** 27.0.12077973
- **CMake:** 3.22.1
- **Gradle:** 8.11.1
- **AGP:** 8.7.3
- **Kotlin:** 2.0.21
- **测试设备:** Lenovo YT-K606F (Android 平板, 2160x1350, Wi-Fi ADB)
