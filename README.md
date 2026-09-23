# K書吧 Android App

[K書吧](https://github.com/agan0617/BookReader)（讀 AI 寫的書的閱讀器）的 Android 外殼，解決**瀏覽器在背景會把朗讀停掉**的問題。

- 整個畫面是一個 WebView，載入 https://agan0617.github.io/BookReader/。書架、同步、閱讀設定都沿用網頁，網頁更新 App 不用重裝
- 只有朗讀換掉：網頁偵測到 `window.KBookNative` 就把整本書的段落送進來，由 `TtsService` 用手機的語音引擎（優先 Google）在 **mediaPlayback 前景服務**裡念；切 App、關螢幕都會繼續
- 通知欄、鎖定畫面、藍牙耳機都能播放／暫停／上一段／下一段；定時停止由原生計時
- 沒用任何第三方函式庫，只有 Android 平台 API

## 結構

| 檔案 | 內容 |
|---|---|
| `MainActivity.java` | WebView、JS 橋接（`KBookNative`）、檔案挑選器、外部連結交給瀏覽器、通知與省電權限 |
| `TtsService.java` | 前景服務：語音佇列（預排 3 段避免段間空檔）、MediaSession、通知、音訊焦點、耳機拔出暫停、定時停止 |

網頁那一側的橋接在 BookReader 的 `index.html`（搜尋 `KBookNative`）：`start` 送 `{bookId, bookTitle, chapters:[{title, blocks}], ch, b, rate, volume, voice, sleep}`，App 念到哪一段用 `window.KBookNativeEvent({type:'pos', ch, b, playing, …})` 回報。段落切法要跟網頁 `openChapter` 取 `R.blocks` 的規則一致。

## 建置

需要 Android Studio 內建的 JDK 21（`C:\Program Files\Android\Android Studio\jbr`）與 SDK 34。

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
```

產出 `app/build/outputs/apk/release/app-release.apk`（用這台電腦的 debug 金鑰簽章，換電腦建置的版本要先移除舊的才能裝）。

⚠️ 從 Claude 桌面版開的 shell 建置時，`%TEMP%` 會被 MSIX 沙盒導走，Gradle 會報「Unable to establish loopback connection」。把 `TEMP`／`TMP` 設到 `D:\Temp\jdk`，並加 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Temp\jdk -Djava.io.tmpdir=D:\Temp\jdk`。

## 小米（HyperOS）

第一次打開會請你允許「不限制電池用量」。如果關螢幕久了還是被停，到「設定 → 應用程式 → K書吧 → 省電策略」選「無限制」，並在最近使用的 App 裡把 K書吧 鎖住（下拉卡片上的鎖頭）。
