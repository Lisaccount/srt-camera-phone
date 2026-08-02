# SRT Camera — Phone Streamer (srt-camera-phone)

[简体中文](README.md) | **English** | [日本語](README.ja.md) | [한국어](README.ko.md)

> Turn your Android phone into a **wireless camera** —
> stream the phone's camera to your PC over SRT with low latency, and use it as a system webcam for live streaming, meetings, or monitoring.

Companion PC receiver: **[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)** (turns this stream into a Windows virtual camera).

## ⬇️ Download the APK

Go to the **[Releases page](https://github.com/Lisaccount/srt-camera-phone/releases/latest)** and download `app-debug.apk`,
then install it on your Android phone (you must allow "install from unknown sources" in system settings). Requires Android 8.0 (API 26) or newer.

---

## What is this / What problem does it solve

Phone cameras are often far better than the one built into a PC. This app encodes your
phone's camera to H.264 in real time and pushes it out over the low-latency **SRT** protocol, letting you:

- 📱 **Use your phone as an HD wireless webcam**: no capture card, no cable — just WiFi / LAN.
- 🎥 **Flexible camera placement**: position the phone freely as a high-quality angle for live commerce, teaching, or outdoor use.
- 🔁 **24/7 stable streaming**: a persistent foreground service plus auto-reconnect, ideal for long unattended streaming.
- 📊 **Live status monitoring**: shows CPU, memory, network, battery, temperature, and uptime while streaming.

```
 📱 Phone camera + mic
        │  Camera2 capture → H.264/AAC hardware encode
        ▼
   ┌───────────────────────┐
   │  This app: SRT push    │
   │  foreground svc + retry │
   └───────────────────────┘
        │  SRT over WiFi / LAN / 4G
        ▼
 PC receiver (MediaMTX / OBS / stream-to-virtualcam)
```

---

## Quick start

### 1. Install
Install the APK on your phone (Android 8.0 / API 26+). On first launch, grant **Camera**, **Microphone**, and **Notification** permissions.

### 2. Enter the push target
- **Server IP**: the LAN IP of the receiving PC (e.g. `192.168.1.10`).
- **Port**: the SRT port the receiver listens on (e.g. `8890` or `9000`).
- **Stream ID**: as required by the receiver (e.g. `publish:phone`; can be left as default when connecting directly to the PC virtual camera).
- **Resolution / bitrate**: default 1280×720, adjust for your network.

### 3. Start streaming
Tap **Start** and the phone keeps pushing to the PC. It continues in the background
(kept alive by the foreground service) and auto-reconnects after a network drop. You're set once the PC shows the picture.

---

## Typical pairing

- **With [stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)**: the PC registers this stream as a system camera, ready to pick in OBS / streaming tools / Zoom.
- **With MediaMTX / OBS**: use it as an SRT source in your existing streaming/recording pipeline.

---

## FAQ

**Nothing goes out / the PC receives nothing?**
Make sure the phone and PC are on the **same WiFi**; the Server IP is the PC's LAN IP; the port matches the receiver; and the PC firewall allows that port.

**High latency / stutter?**
Lower the bitrate or resolution; get closer to the router; raise the SRT latency on the receiver side.

**Disconnects when the screen turns off?**
The app uses a foreground service + WakeLock to stay alive; please disable battery optimization / background restrictions for this app in system settings.

---

## Build from source (developers)

<details>
<summary>Expand build instructions</summary>

**Tech stack**

| Component | Technology |
|-----------|------------|
| SRT push | StreamPack 3.1.2 (Maven Central) |
| SRT core | srtdroid (libsrt JNI) |
| Capture | Camera2 (built into StreamPack) |
| Encode | MediaCodec H.264 / AAC hardware encode |
| Min version | Android 8.0 (API 26), target API 35 |

**Build steps**
1. Open the project root in Android Studio and wait for Gradle sync (first sync downloads StreamPack and native libs).
2. `Build → Make Project`, or `./gradlew assembleDebug` on the command line.
3. `local.properties` (your local SDK path) is not version-controlled — Android Studio generates it automatically.

A more detailed verification walkthrough is in [`README_PHASE1.md`](README_PHASE1.md).

</details>

## License

[MIT](LICENSE)
