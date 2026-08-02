# SRT Camera — スマホ送出アプリ (srt-camera-phone)

[简体中文](README.md) | [English](README.en.md) | **日本語** | [한국어](README.ko.md)

> Android スマホを **ワイヤレスカメラ** に変えます。
> スマホのカメラ映像を SRT プロトコルで低遅延に PC へ送出し、PC 側と組み合わせて配信・会議・監視用のシステムカメラとして使えます。

対応する PC 受信側：**[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam)**（この映像を Windows バーチャルカメラに変換）。

## ⬇️ APK をダウンロード

**[Releases ページ](https://github.com/Lisaccount/srt-camera-phone/releases/latest)** から `app-debug.apk` をダウンロードし、
Android スマホにインストールしてください（システム設定で「提供元不明のアプリのインストール」を許可する必要があります）。Android 8.0（API 26）以上が必要です。

---

## これは何か / どんな課題を解決するか

スマホのカメラは PC 内蔵カメラよりずっと高性能なことが多いです。本アプリはスマホの
カメラ映像をリアルタイムに H.264 エンコードし、低遅延の **SRT** プロトコルで送出します。これにより：

- 📱 **スマホを高画質ワイヤレス Webカメラに**：キャプチャカードもケーブルも不要、WiFi / LAN だけで OK。
- 🎥 **柔軟なカメラ配置**：スマホを自由に置いて高画質なアングルとして、ライブコマース・授業・屋外撮影に。
- 🔁 **24時間365日の安定送出**：常駐フォアグラウンドサービス + 自動再接続で、長時間の無人配信に最適。
- 📊 **リアルタイム状態監視**：送出中に CPU・メモリ・ネットワーク・バッテリー・温度・稼働時間を表示。

```
 📱 スマホのカメラ + マイク
        │  Camera2 取得 → H.264/AAC ハードウェアエンコード
        ▼
   ┌───────────────────────┐
   │  本アプリ：SRT 送出     │
   │  フォアグラウンド + 再接続│
   └───────────────────────┘
        │  SRT over WiFi / LAN / 4G
        ▼
 PC 受信側 (MediaMTX / OBS / stream-to-virtualcam)
```

---

## クイックスタート

### 1. インストール
スマホに APK をインストール（Android 8.0 / API 26 以上）。初回起動時に **カメラ**・**マイク**・**通知** の権限を許可します。

### 2. 送出先を入力
- **サーバー IP**：受信 PC の LAN IP（例：`192.168.1.10`）。
- **ポート**：受信側が待ち受ける SRT ポート（例：`8890` または `9000`）。
- **Stream ID**：受信側の要件に合わせて入力（例：`publish:phone`。PC バーチャルカメラへ直接接続する場合は既定のままで可）。
- **解像度 / ビットレート**：既定 1280×720、ネットワークに応じて調整。

### 3. 送出開始
**Start** をタップすると、スマホは PC へ送出し続けます。バックグラウンドでも継続し
（フォアグラウンドサービスで保持）、ネットワーク切断後は自動再接続します。PC に映像が出れば成功です。

---

## 代表的な組み合わせ

- **[stream-to-virtualcam](https://github.com/Lisaccount/stream-to-virtualcam) と併用**：PC がこの映像をシステムカメラとして登録し、OBS / 配信ツール / Zoom でそのまま選択可能。
- **MediaMTX / OBS と併用**：既存の配信/録画フローの一つの SRT ソースとして接続。

---

## FAQ

**送出できない / PC で受信できない？**
スマホと PC が **同じ WiFi** か確認。サーバー IP は PC の LAN IP、ポートは受信側と一致、
PC のファイアウォールが該当ポートを許可しているか確認。

**遅延が大きい / カクつく？**
ビットレートや解像度を下げる。ルーターに近づく。受信側で SRT の latency を適切に増やす。

**画面が消えると切れる？**
本アプリはフォアグラウンドサービス + WakeLock で保持します。システム設定で本アプリの電池最適化 / バックグラウンド制限をオフにしてください。

---

## ソースからビルド（開発者向け）

<details>
<summary>ビルド手順を開く</summary>

**技術スタック**

| コンポーネント | 技術 |
|------|------|
| SRT 送出 | StreamPack 3.1.2 (Maven Central) |
| SRT コア | srtdroid (libsrt JNI) |
| 取得 | Camera2 (StreamPack 内蔵) |
| エンコード | MediaCodec H.264 / AAC ハードウェアエンコード |
| 最低バージョン | Android 8.0 (API 26)、ターゲット API 35 |

**ビルド手順**
1. Android Studio でプロジェクトルートを開き、Gradle sync を待つ（初回は StreamPack と native ライブラリをダウンロード）。
2. `Build → Make Project`、またはコマンドラインで `./gradlew assembleDebug`。
3. `local.properties`（ローカルの SDK パス）はバージョン管理対象外 —— Android Studio が自動生成します。

より詳しい検証手順は [`README_PHASE1.md`](README_PHASE1.md) を参照。

</details>

## ライセンス

[MIT](LICENSE)
