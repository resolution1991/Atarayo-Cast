# Atarayo-Cast 项目上下文

本文档记录 Codex 接手项目时已确认的项目状态、开发环境和后续迭代注意事项。后续迭代优先更新本文档，避免分散记录。

## 当前状态

- 项目仓库：`https://github.com/resolution1991/Atarayo-Cast.git`
- 本地源码目录：`/Users/algernon/Documents/AirCast`
- 注意：`/Users/algernon/Documents/Cast-App` 当前只有一个空 Git 仓库，没有源码、提交或 remote。
- 当前分支：`main`
- 当前基线目标：`v0.3`（由本次发布提交和 tag 固化）。
- v0.3 包含 Codex 修复：权限回调误启动、KeepScreenOn collector 累积、native 初始化失败处理、依赖路径配置、版本号对齐、设置页分辨率选中态、AirPlay 启动阶段块状伪影、持续投屏参考帧丢失重同步、AirPlay `maxFPS` 60fps 协商。
- 版本定位：Android 设备作为 AirPlay 接收端和 DLNA Media Renderer。
- 已确认构建产物：`/Users/algernon/Documents/AirCast/app/build/outputs/apk/debug/app-debug.apk`

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
cd /Users/algernon/Documents/AirCast
./build.sh
```

安装到已连接设备：

```bash
cd /Users/algernon/Documents/AirCast
./build.sh install
```

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
- `MainActivity.kt` 使用已废弃的 `FLAG_FULLSCREEN`。
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
4. 发布配置：当前 debug/release 元数据已对齐到 `versionName = "0.3"`、`versionCode = 3`；后续每次 tag/release 仍需同步更新。
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
