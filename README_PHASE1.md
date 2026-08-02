# Android SRT Camera - Phase 1 验证指南

## 目标

验证完整链路：**手机摄像头 → H264 硬编码 → SRT 推流 → MediaMTX → OBS 显示**

## 项目结构

```
android-srt-camera/
├── settings.gradle.kts          # Gradle 项目配置
├── build.gradle.kts             # 项目级构建文件
├── gradle.properties            # Gradle 属性
├── gradle/wrapper/
│   └── gradle-wrapper.properties # Gradle 版本配置
├── app/
│   ├── build.gradle.kts         # App 模块构建文件（含 StreamPack 依赖）
│   ├── proguard-rules.pro       # 代码混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml  # 权限 + Activity 声明
│       ├── java/com/srtcamera/
│       │   ├── MainActivity.kt  # 主界面：预览 + 设置 + 推流控制 + 自动重连
│       │   └── StreamConfig.kt  # 推流配置数据类
│       └── res/                 # 布局、颜色、字符串、图标资源
└── .gitignore
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| SRT 推流 SDK | StreamPack | 3.1.2 (Maven Central) |
| 底层 SRT 库 | srtdroid (libsrt JNI 封装) | 随 StreamPack 发布 |
| 摄像头采集 | Camera2 (StreamPack 内置) | — |
| 视频编码 | MediaCodec H264 硬编码 (StreamPack 内置) | — |
| 音频编码 | MediaCodec AAC (StreamPack 内置) | — |
| 最低 Android 版本 | Android 8.0 (API 26) | — |
| 目标 Android 版本 | Android 15 (API 35) | — |

## 构建步骤

### 1. 打开项目

1. 打开 Android Studio
2. 选择 **File → Open**
3. 选择 `android-srt-camera/` 目录
4. 等待 Gradle sync 完成（首次会下载 StreamPack 依赖和 native 库，需要几分钟）

> **注意**：项目不包含 `gradlew` 和 `gradle-wrapper.jar`。Android Studio 打开时会自动生成 Gradle Wrapper。如果没有自动生成，在 Android Studio 的 Terminal 中运行：
> ```
> gradle wrapper --gradle-version 8.11.1
> ```

### 2. 检查编译

1. 等待 Gradle sync 完成
2. 点击 **Build → Make Project**（Ctrl+F9）
3. 如果编译成功，进入下一步
4. 如果有编译错误，查看下方「常见问题」部分

### 3. 配置 PC 端 MediaMTX

确保 T0 测试环境可用：

```bash
# 在 PC 上启动 MediaMTX
cd t0-env
# 双击 start_server.bat 或运行：
# mediamtx/mediamtx.exe mediamtx-t0.yml
```

MediaMTX 默认监听 SRT 端口 **8890**。

### 4. 获取 PC 的局域网 IP

手机需要通过 WiFi 连接到 PC，所以需要 PC 的局域网 IP：

```bash
# Windows
ipconfig
# 找到 "无线局域网适配器" 或 "以太网适配器" 下的 IPv4 地址
# 例如：192.168.1.100
```

### 5. 安装到手机

1. 手机开启 **开发者选项** 和 **USB 调试**
2. USB 连接手机和 PC
3. Android Studio 顶部设备下拉选择你的手机
4. 点击 **Run**（Shift+F10）安装并启动 App

### 6. 端到端验证

在手机 App 上：

1. **授权**：首次打开会请求摄像头和麦克风权限，点击允许
2. **看到画面**：摄像头预览显示在屏幕上半部分
3. **填写设置**：
   - Server IP：填入 PC 的局域网 IP（如 `192.168.1.100`）
   - Port：`8890`
   - Stream ID：`publish:phone`
   - Resolution：`1280x720`
   - Bitrate：`2000000`
4. **点击 Start Streaming**
5. 状态显示 **Streaming**（绿色）

在 PC 上验证：

```bash
# 方法1：ffprobe 检查流
ffprobe -v error -rw_timeout 5000000 -show_entries stream=codec_name,width,height,r_frame_rate -of json "srt://localhost:8890?streamid=read:phone"

# 方法2：截取一帧画面
ffmpeg -y -rw_timeout 8000000 -i "srt://localhost:8890?streamid=read:phone" -frames:v 1 phone_frame.jpg

# 方法3：OBS 查看实时画面
# 添加媒体源 → 输入：srt://localhost:8890?streamid=read:phone
```

### 7. 验收标准

| 检查项 | 预期结果 |
|--------|----------|
| App 启动，摄像头预览正常 | 看到实时画面 |
| 点击 Start，状态变绿色 Streaming | 推流开始 |
| PC 端 ffprobe 能读取流 | H264 + AAC |
| PC 端截帧画面正确 | 看到手机摄像头画面 |
| OBS 能显示实时画面 | 低延迟画面 |
| 点击 Stop，推流停止 | 状态变灰色 Stopped |
| 拔 WiFi 30 秒后恢复 | 自动重连，状态恢复 Streaming |

## 常见问题

### Q1: Gradle sync 失败 — 找不到 StreamPack 依赖

确保 `settings.gradle.kts` 中包含 `mavenCentral()` 仓库。StreamPack 3.1.2 已发布到 Maven Central。

### Q2: 编译错误 — VideoConfig / AudioConfig 构造函数参数不匹配

StreamPack 3.1.2 的 `VideoConfig` 和 `AudioConfig` 可能需要额外参数。打开 `MainActivity.kt`，找到有 `NOTE` 注释的地方，添加：

```kotlin
// VideoConfig - 如果 3 参数版本不编译，改为：
VideoConfig(
    mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,  // H264
    startBitrate = config.bitrate,
    resolution = config.resolution,
    fps = config.fps
)

// AudioConfig - 如果 3 参数版本不编译，改为：
AudioConfig(
    mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
    startBitrate = 128_000,
    sampleRate = 44_100,
    channelConfig = AudioFormat.CHANNEL_IN_STEREO
)
```

需要额外 import：
```kotlin
import android.media.MediaFormat
```

### Q3: 编译错误 — SrtMediaDescriptor 工厂函数不存在

如果 `SrtMediaDescriptor(host=..., port=..., streamId=...)` 不编译，改用 URL 方式：

```kotlin
// 替换 SrtMediaDescriptor 构造为 URL 方式
import android.net.Uri

val srtUrl = "srt://${config.server}:${config.port}?streamid=${config.streamId}&latency=${config.latency}"
// 然后用 StreamPack 的 URL 解析方式创建 descriptor
```

### Q4: 运行时崩溃 — Camera2 权限问题

确保手机已授予摄像头和麦克风权限。如果 App 崩溃，查看 logcat 日志：
```
adb logcat -s SrtCamera
```

### Q5: 连接超时 — 手机无法连接到 PC

1. 确认手机和 PC 在同一 WiFi 网络
2. 确认 PC 的防火墙允许 8890 端口入站
3. 确认 MediaMTX 正在运行（PC 上 `netstat -ano | findstr 8890`）
4. 尝试用 PC 的不同 IP（有线 vs 无线）

### Q6: OBS 画面花屏或卡顿

1. 检查 WiFi 信号强度
2. 降低码率（Bitrate）到 1000000
3. 降低分辨率到 640x480
4. 检查 MediaMTX 日志是否有丢包警告

### Q7: compileSdk 36 错误

如果 StreamPack 3.1.2 要求 compileSdk 36，将 `app/build.gradle.kts` 中的 `compileSdk = 35` 改为 `compileSdk = 36`，并确保 AGP 版本 ≥ 8.8.0。

## API 参考

### StreamPack 关键类

| 类 | 包路径 | 用途 |
|----|--------|------|
| `SingleStreamer` | `core.streamers.single` | 音视频单输出推流器 |
| `VideoConfig` | `core.streamers.single` | 视频配置（分辨率/帧率/码率） |
| `AudioConfig` | `core.streamers.single` | 音频配置（采样率/声道/码率） |
| `SrtMediaDescriptor` | `ext.srt.configuration.mediadescriptor` | SRT 连接描述符 |
| `CameraSourceFactory` | `core.elements.sources.video.camera` | 摄像头源工厂 |
| `MicrophoneSourceFactory` | `core.elements.sources.audio.audiorecord` | 麦克风源工厂 |
| `PreviewView` | `ui.views` | 摄像头预览视图 |
| `StreamerPipeline` | `core.pipelines` | 推流管线配置 |

### SRT URL 格式

| 角色 | URL |
|------|-----|
| 手机推流 (Caller) | `srt://<PC_IP>:8890?streamid=publish:phone` |
| PC 读取 (OBS/ffprobe) | `srt://<PC_IP>:8890?streamid=read:phone` |

## 下一步

Phase 1 验证通过后，Phase 2 将添加：
- [ ] Foreground Service（锁屏继续推流）
- [ ] 24 小时稳定性测试
- [ ] 内存泄漏检测
- [ ] 温度监控
- [ ] 更完善的重连策略（指数退避）
