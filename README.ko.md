# SRT Camera — 휴대폰 송출 앱 (srt-camera-phone)

[简体中文](README.md) | [English](README.en.md) | [日本語](README.ja.md) | **한국어**

> Android 휴대폰을 **무선 카메라** 로 바꿉니다.
> 휴대폰 카메라 영상을 SRT 프로토콜로 낮은 지연으로 PC에 송출하고, PC 측과 결합하면 방송·회의·모니터링용 시스템 카메라로 사용할 수 있습니다.

함께 쓰는 PC 수신 측: **[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)** (이 영상을 Windows 가상 카메라로 변환).

## ⬇️ APK 다운로드

**[Releases 페이지](https://github.com/Lisaccount/srt-camera-phone/releases/latest)** 에서 `app-debug.apk` 를 내려받아
Android 휴대폰에 설치하세요(시스템 설정에서 "알 수 없는 출처 앱 설치"를 허용해야 합니다). Android 8.0(API 26) 이상이 필요합니다.

---

## 무엇인가 / 어떤 문제를 해결하는가

휴대폰 카메라는 PC 내장 카메라보다 훨씬 좋은 경우가 많습니다. 이 앱은 휴대폰
카메라 영상을 실시간으로 H.264 인코딩하여, 낮은 지연의 **SRT** 프로토콜로 송출합니다. 이를 통해:

- 📱 **휴대폰을 고화질 무선 웹캠으로**: 캡처 카드도 케이블도 필요 없이 WiFi / LAN만으로.
- 🎥 **유연한 카메라 배치**: 휴대폰을 자유롭게 두어 고화질 앵글로 라이브 커머스·강의·야외 촬영에.
- 🔁 **24시간 안정 송출**: 상주 포그라운드 서비스 + 자동 재연결로 장시간 무인 방송에 적합.
- 📊 **실시간 상태 모니터링**: 송출 중 CPU·메모리·네트워크·배터리·온도·가동 시간 표시.

```
 📱 휴대폰 카메라 + 마이크
        │  Camera2 캡처 → H.264/AAC 하드웨어 인코딩
        ▼
   ┌───────────────────────┐
   │  본 앱: SRT 송출        │
   │  포그라운드 + 재연결     │
   └───────────────────────┘
        │  SRT over WiFi / LAN / 4G
        ▼
 PC 수신 측 (MediaMTX / OBS / stream-to-virtualcam)
```

---

## 빠른 시작

### 1. 설치
휴대폰에 APK 설치(Android 8.0 / API 26 이상). 첫 실행 시 **카메라**·**마이크**·**알림** 권한을 허용하세요.

### 2. 송출 대상 입력
- **서버 IP**: 수신 PC의 LAN IP(예: `192.168.1.10`).
- **포트**: 수신 측이 대기하는 SRT 포트(예: `8890` 또는 `9000`).
- **Stream ID**: 수신 측 요구에 맞게 입력(예: `publish:phone`. PC 가상 카메라에 직접 연결 시 기본값으로 두어도 됨).
- **해상도 / 비트레이트**: 기본 1280×720, 네트워크에 따라 조정.

### 3. 송출 시작
**Start** 를 누르면 휴대폰이 PC로 계속 송출합니다. 백그라운드에서도 계속되며
(포그라운드 서비스로 유지), 네트워크 끊김 후 자동 재연결합니다. PC에 영상이 나오면 성공입니다.

---

## 대표적인 조합

- **[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam) 와 함께**: PC가 이 영상을 시스템 카메라로 등록하여 OBS / 방송 도구 / Zoom에서 바로 선택 가능.
- **MediaMTX / OBS 와 함께**: 기존 방송/녹화 파이프라인의 SRT 소스로 연결.

---

## FAQ

**송출이 안 되거나 PC가 수신하지 못하나요?**
휴대폰과 PC가 **같은 WiFi** 인지 확인. 서버 IP는 PC의 LAN IP, 포트는 수신 측과 일치,
PC 방화벽이 해당 포트를 허용하는지 확인.

**지연이 크거나 끊기나요?**
비트레이트나 해상도를 낮추세요. 라우터에 가까이. 수신 측에서 SRT latency를 적절히 늘리세요.

**화면이 꺼지면 끊기나요?**
본 앱은 포그라운드 서비스 + WakeLock으로 유지됩니다. 시스템 설정에서 본 앱의 배터리 최적화 / 백그라운드 제한을 꺼 주세요.

---

## 소스에서 빌드 (개발자용)

<details>
<summary>빌드 지침 펼치기</summary>

**기술 스택**

| 구성 요소 | 기술 |
|------|------|
| SRT 송출 | StreamPack 3.1.2 (Maven Central) |
| SRT 코어 | srtdroid (libsrt JNI) |
| 캡처 | Camera2 (StreamPack 내장) |
| 인코딩 | MediaCodec H.264 / AAC 하드웨어 인코딩 |
| 최소 버전 | Android 8.0 (API 26), 타깃 API 35 |

**빌드 단계**
1. Android Studio에서 프로젝트 루트를 열고 Gradle sync 대기(최초 sync는 StreamPack과 native 라이브러리를 다운로드).
2. `Build → Make Project`, 또는 명령줄에서 `./gradlew assembleDebug`.
3. `local.properties`(로컬 SDK 경로)는 버전 관리 대상이 아님 —— Android Studio가 자동 생성.

더 자세한 검증 절차는 [`README_PHASE1.md`](README_PHASE1.md) 참조.

</details>

## 라이선스

[MIT](LICENSE)
