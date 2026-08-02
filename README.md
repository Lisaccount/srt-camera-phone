# SRT Camera 手机推流端 (srt-camera-phone)

**简体中文** | [English](README.en.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

> 把你的 Android 手机变成一台**无线摄像头**——
> 通过 SRT 协议把手机摄像头画面低延迟推到电脑，配合 PC 端即可当作系统摄像头用于直播、会议、监控。

配套 PC 接收端：**[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)**（把这路流变成 Windows 虚拟摄像头）。

## ⬇️ 下载 APK

前往 **[Releases 页面](https://github.com/Lisaccount/srt-camera-phone/releases/latest)** 下载 `app-debug.apk`，
传到 Android 手机安装（需在系统设置里允许「安装未知来源应用」）。要求 Android 8.0（API 26）及以上。

---

## 这是什么 / 能解决什么问题

手机的摄像头往往比电脑自带的好得多。这个 app 把手机摄像头实时编码成 H.264，
用低延迟的 **SRT** 协议推流出去，让你：

- 📱 **手机当高清无线摄像头**：无需采集卡、无需数据线，走 WiFi / 局域网即可。
- 🎥 **灵活机位**：手机可自由摆放、当作后置高清机位，用于直播带货、教学、户外。
- 🔁 **7×24 稳定推流**：前台服务常驻 + 断线自动重连，适合长时间无人值守直播。
- 📊 **实时状态监控**：推流时显示 CPU、内存、网络、电量、温度、运行时长。

```
 📱 手机摄像头 + 麦克风
        │  Camera2 采集 → H.264/AAC 硬编码
        ▼
   ┌───────────────────────┐
   │  本 App：SRT 推流       │
   │  前台服务 + 自动重连     │
   └───────────────────────┘
        │  SRT over WiFi / 局域网 / 4G
        ▼
 PC 接收端 (MediaMTX / OBS / stream-to-virtualcam)
```

---

## 快速上手（使用者）

### 1. 安装
在手机上安装 APK（Android 8.0 / API 26 及以上）。首次运行授予 **摄像头**、**麦克风**、**通知** 权限。

### 2. 填写推流目标
- **服务器 IP**：接收端电脑的局域网 IP（如 `192.168.1.10`）。
- **端口**：接收端监听的 SRT 端口（如 `8890` 或 `9000`）。
- **Stream ID**：按接收端要求填写（如 `publish:phone`；直连 PC 虚拟摄像头时可留默认）。
- **分辨率 / 码率**：默认 1280×720，可按网络情况调整。

### 3. 开始推流
点 **开始**，手机即持续把画面推向电脑。切到后台仍会继续（前台服务保活），
断网后会自动重连。电脑端看到画面即成功。

---

## 典型搭配

- **配合 [stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)**：电脑把这路流注册成系统摄像头，
  OBS / 抖音直播伴侣 / 腾讯会议 直接选用。
- **配合 MediaMTX / OBS**：作为一路 SRT 源接入你已有的直播/录制流程。

---

## 常见问题

**推不出去 / 电脑收不到？**
确认手机与电脑在**同一 WiFi**；服务器 IP 填电脑局域网 IP；端口与接收端一致；
电脑防火墙放行该端口。

**延迟高 / 卡顿？**
降低码率或分辨率；靠近路由器；接收端适当调大 SRT latency。

**息屏后断了？**
app 使用前台服务 + WakeLock 保活；请在系统里关闭对本 app 的电池优化 / 后台限制。

---

## 从源码构建（开发者）

<details>
<summary>展开构建说明</summary>

**技术栈**

| 组件 | 技术 |
|------|------|
| SRT 推流 | StreamPack 3.1.2 (Maven Central) |
| 底层 SRT | srtdroid (libsrt JNI) |
| 采集 | Camera2 (StreamPack 内置) |
| 编码 | MediaCodec H.264 / AAC 硬编码 |
| 最低版本 | Android 8.0 (API 26)，目标 API 35 |

**构建步骤**
1. 用 Android Studio 打开本项目根目录，等待 Gradle sync（首次会下载 StreamPack 与 native 库）。
2. `Build → Make Project`，或命令行 `./gradlew assembleDebug`。
3. `local.properties`（含本机 SDK 路径）不纳入版本库，Android Studio 会自动生成。

更详细的验证链路见 [`README_PHASE1.md`](README_PHASE1.md)。

</details>

## License

[MIT](LICENSE)
