# K書吧 Android App

[K書吧](https://github.com/agan0617/KBookBar)（讀 AI 寫的書的閱讀器）的 Android 外殼，解決**瀏覽器在背景會把朗讀停掉**的問題。

- 整個畫面是一個 WebView，載入 https://agan0617.github.io/KBookBar/。書架、同步、閱讀設定都沿用網頁，網頁更新 App 不用重裝
- 只有朗讀換掉：網頁偵測到 `window.KBookNative` 就把整本書的段落送進來，由 `TtsService` 用手機的語音引擎（優先 Google）在 **mediaPlayback 前景服務**裡念；切 App、關螢幕都會繼續
- 通知欄、鎖定畫面、藍牙耳機都能播放／暫停／上一段／下一段；定時停止由原生計時
- 沒用任何第三方函式庫，只有 Android 平台 API

## 用你自己的書架

App 不綁任何人的書：裝好之後連上**你自己的**私有書架 repo，就是你自己的K書吧。

1. **先建好書架 repo 和 token**：照 [KBookBar 的〈用你自己的書架〉](https://github.com/agan0617/KBookBar#用你自己的書架)第 1、2 步——建一個 Private repo（勾 Add a README file）、產生只授權那個 repo、Contents 為 Read and write 的 fine-grained token。用手機做的話，在瀏覽器裡產生 token 後直接複製
2. **安裝 App**：到這個 repo 的 [Releases](../../releases) 下載最新的 `.apk`，在手機上打開安裝。第一次會問要不要允許這個來源（瀏覽器或檔案管理員）安裝不明應用程式，允許即可
3. **打開 App → 連上書架**：按右上角的連線狀態（或書架上的「連上書架」）→ 貼上 token → 展開「**換 repo（一般不用動）**」，改成 **`你的帳號/你的 repo 名`** → 連線。看到「已連上 你的帳號/你的 repo 名」就好了
4. **允許通知與不限制電池**：App 第一次會問，都允許——朗讀要靠通知欄的前景服務才能在背景、關螢幕時繼續念

注意：

- **App 跟手機瀏覽器是兩台不同的裝置**。就算手機的 Chrome 已經連過書架，App 裡還是要再貼一次 token、再換一次 repo（App 的 WebView 存的資料跟瀏覽器分開）。連上之後書和進度是同一份，「繼續閱讀」底下的「所有裝置的閱讀進度」會列出「手機 App」和其他裝置各讀到哪，點一格就從那裡接著讀
- 朗讀用的是手機的語音引擎，建議裝 Google 的「語音服務」（Speech Services by Google）並下載中文語音。朗讀設定的語音清單裡，還沒下載的聲音選了會帶你去下載頁
- 更新 App：下載新版 `.apk` 直接裝上去，書架和登入都會保留。網頁的功能更新則不用重裝，App 每次打開都會抓最新的網頁
- **看版本**：書架最下面那行小字是「網頁 年.月.日 · App 1.x」；按「檢查更新」會跟線上的網頁和這裡 Releases 最新的版本比，App 有新版會出現「下載」連結（App 裡每半天也會自己查一次）
- **離線也能開**：網頁的 Service Worker（`sw.js`）會在 WebView 裡跑，把頁面、腳本、字型存在手機上；書檔本來就存在本機。沒網路時打開 App 一樣能讀，右上角會顯示「離線・本機書架」，網路回來會自動重連、補傳進度。第一次得在有網路時打開過一次
- App 固定載入 https://agan0617.github.io/KBookBar/ 。如果你 fork 了 KBookBar 自己架網頁，把 `MainActivity.java` 的 `HOME` 改成你的網址，再照下面〈建置〉自己建一份 APK

## 結構

| 檔案 | 內容 |
|---|---|
| `MainActivity.java` | WebView、JS 橋接（`KBookNative`）、檔案挑選器、外部連結交給瀏覽器、通知與省電權限 |
| `TtsService.java` | 前景服務：語音佇列（預排 3 段避免段間空檔）、MediaSession、通知、音訊焦點、耳機拔出暫停、定時停止 |

網頁那一側的橋接在 KBookBar 的 `index.html`（搜尋 `KBookNative`）：`start` 送 `{bookId, bookTitle, chapters:[{title, blocks}], ch, b, rate, volume, voice, sleep}`，App 念到哪一段用 `window.KBookNativeEvent({type:'pos', ch, b, playing, …})` 回報。段落切法要跟網頁 `openChapter` 取 `R.blocks` 的規則一致。

## 建置

需要 Android Studio 內建的 JDK 21（`C:\Program Files\Android\Android Studio\jbr`）與 SDK 34。

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
```

改版時三個地方一起改：`app/build.gradle` 的 `versionCode`（+1）、`versionName`，以及 `BuildConfigVersion.NAME`；發 Release 的 tag 用 `v` + versionName（例如 `v1.5`），網頁的「檢查更新」拿最新 Release 的 tag 跟裝著的 App 比。

產出 `app/build/outputs/apk/release/app-release.apk`（用這台電腦的 debug 金鑰簽章，換電腦建置的版本要先移除舊的才能裝）。

⚠️ 從 Claude 桌面版開的 shell 建置時，`%TEMP%` 會被 MSIX 沙盒導走，Gradle 會報「Unable to establish loopback connection」。把 `TEMP`／`TMP` 設到 `D:\Temp\jdk`，並加 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Temp\jdk -Djava.io.tmpdir=D:\Temp\jdk`。

## 小米（HyperOS）

第一次打開會請你允許「不限制電池用量」。如果關螢幕久了還是被停，到「設定 → 應用程式 → K書吧 → 省電策略」選「無限制」，並在最近使用的 App 裡把 K書吧 鎖住（下拉卡片上的鎖頭）。
