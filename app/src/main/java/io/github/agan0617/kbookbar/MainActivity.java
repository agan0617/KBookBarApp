package io.github.agan0617.kbookbar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * K書吧 App：整個畫面是一個 WebView，載入 GitHub Pages 上的 K書吧 網頁（書架、同步、閱讀都沿用網頁）。
 * 只有朗讀換掉：網頁偵測到 window.KBookNative 就改叫這裡，由 {@link TtsService} 在前景服務裡念，
 * 切 App、鎖螢幕都不會停。
 */
public class MainActivity extends Activity {

    static final String HOME = "https://agan0617.github.io/BookReader/";
    private static final String HOST = "agan0617.github.io";
    private static final int REQ_FILE = 10;
    private static final int REQ_NOTIF = 11;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private TextToSpeech voiceProbe; // 只用來列語音清單（服務還沒啟動時也要列得出來）
    private volatile boolean voicesReady;
    private boolean pageFailed;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100); // 字級交給網頁自己的設定
        s.setUserAgentString(s.getUserAgentString() + " KBookBarApp/" + BuildConfigVersion.NAME);
        // 有網路就一律抓最新網頁（GitHub Pages 會被快取 10 分鐘，網頁改版 App 會一直看到舊版）；
        // 抓回來的仍會寫進快取，離線時才用快取裡的
        s.setCacheMode(isOnline() ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        web.addJavascriptInterface(new Bridge(), "KBookNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (url != null && url.startsWith("http")) pageFailed = false;
            }

            // 抓不到最新網頁（剛開機網路還沒通、訊號差）：先退回快取；快取也沒有就顯示重試頁
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (!r.isForMainFrame()) return;
                pageFailed = true;
                WebSettings ws = v.getSettings();
                if (ws.getCacheMode() != WebSettings.LOAD_CACHE_ELSE_NETWORK) {
                    ws.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    v.loadUrl(HOME);
                } else {
                    v.loadDataWithBaseURL(null,
                            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                            + "<body style='font-family:sans-serif;text-align:center;padding:30vh 24px 0;color:#888;background:transparent'>"
                            + "<p>連不上網路，K書吧打不開。</p><p><a href='" + HOME + "' style='color:#5b8bd6'>網路恢復後點這裡重試</a></p></body>",
                            "text/html", "utf-8", null);
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // 其他網址（例如 GitHub 產 token 的頁面）交給外部瀏覽器
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(Intent.createChooser(i, "選擇書檔"), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });

        TtsService.listener = json -> main.post(() -> {
            if (web != null) web.evaluateJavascript("window.KBookNativeEvent&&window.KBookNativeEvent(" + json + ")", null);
        });
        voiceProbe = new TextToSpeech(this, status -> {
            voicesReady = status == TextToSpeech.SUCCESS;
            // 引擎初始化完成時 Activity 可能已經關掉（web 已經 destroy），要先檢查
            if (voicesReady) main.post(() -> { if (web != null) web.evaluateJavascript("window.KBookNativeEvent&&window.KBookNativeEvent({type:'voices'})", null); });
        }, TtsService.GOOGLE_TTS);

        if (saved != null) web.restoreState(saved); else web.loadUrl(HOME);
        askPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        // 上次沒載成功、現在有網路了：抓最新版重來
        if (pageFailed && isOnline() && web != null) {
            pageFailed = false;
            web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            web.loadUrl(HOME);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override public void onBackPressed() {
        // 網頁的閱讀畫面用 history.pushState，返回鍵先交給網頁；已經在書架就退到背景（不結束，朗讀照常）
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        TtsService.listener = null;
        if (voiceProbe != null) voiceProbe.shutdown();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FILE) { super.onActivityResult(req, res, data); return; }
        if (fileCb == null) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) result = new Uri[]{data.getData()};
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
    }

    private boolean isOnline() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** 通知權限（Android 13+，沒有它通知欄的播放控制不會出現）＋第一次打開時請 Ken 關掉省電限制 */
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        PowerManager pm = getSystemService(PowerManager.class);
        if (!sp.getBoolean("askedBattery", false) && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            sp.edit().putBoolean("askedBattery", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("讓朗讀在背景不被停掉")
                    .setMessage("小米的省電機制會把背景的 App 關掉。接下來請允許 K書吧「不限制電池用量」，關螢幕聽書才不會念到一半停掉。")
                    .setPositiveButton("去設定", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton("之後再說", null)
                    .show();
        }
    }

    /** 給網頁呼叫的介面（window.KBookNative）。這些方法跑在 JavaBridge 執行緒，實際動作丟回主執行緒。 */
    private class Bridge {
        @JavascriptInterface public String version() { return BuildConfigVersion.NAME; }

        @JavascriptInterface public void start(String json) {
            main.post(() -> {
                TtsService.pendingStart = json;
                startForegroundService(new Intent(MainActivity.this, TtsService.class).setAction(TtsService.ACTION_START));
            });
        }

        @JavascriptInterface public void pause() { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.pause(); }); }
        @JavascriptInterface public void resume() { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.resume(); }); }
        @JavascriptInterface public void stop() { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.stopAll(); }); }
        @JavascriptInterface public void jump(int delta) { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.jump(delta); }); }
        @JavascriptInterface public void seek(int ch, int b) { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.seek(ch, b); }); }
        @JavascriptInterface public void setRate(float r) { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.setRate(r); }); }
        @JavascriptInterface public void setVolume(float v) { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.setVolume(v); }); }
        @JavascriptInterface public void setVoice(String id) {
            main.post(() -> {
                // 選到還沒下載的語音：帶去 Google 語音的下載頁
                if (id != null && !id.isEmpty() && voiceProbe != null) {
                    try {
                        for (android.speech.tts.Voice v : voiceProbe.getVoices()) {
                            if (!v.getName().equals(id) || !TtsService.notInstalled(v)) continue;
                            if (web != null) web.evaluateJavascript("window.KBookNativeEvent&&window.KBookNativeEvent({type:'error',message:'這個語音要先下載：在接下來的畫面下載「" + v.getLocale().getDisplayName(java.util.Locale.TRADITIONAL_CHINESE) + "」，好了回來再選一次'})", null);
                            startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(TtsService.GOOGLE_TTS));
                            break;
                        }
                    } catch (Exception ignored) { }
                }
                TtsService t = TtsService.instance; if (t != null) t.setVoice(id);
            });
        }
        @JavascriptInterface public void setSleep(String v) { main.post(() -> { TtsService t = TtsService.instance; if (t != null) t.setSleep(v); }); }

        @JavascriptInterface public String getState() {
            return TtsService.instance == null ? "{\"active\":false,\"playing\":false}" : TtsService.stateJson;
        }

        @JavascriptInterface public String getVoices() {
            if (!voicesReady || voiceProbe == null) return "[]";
            return TtsService.voicesJson(voiceProbe);
        }

        /** 診斷用：引擎回報的全部語音（含沒安裝的）與手機上有哪些語音引擎 */
        @JavascriptInterface public String diag() {
            try {
                JSONObject o = new JSONObject();
                o.put("ready", voicesReady);
                if (voiceProbe != null) {
                    org.json.JSONArray engines = new org.json.JSONArray();
                    for (TextToSpeech.EngineInfo e : voiceProbe.getEngines()) engines.put(e.name + " / " + e.label);
                    o.put("engines", engines);
                    o.put("default", voiceProbe.getDefaultEngine());
                    org.json.JSONArray all = new org.json.JSONArray();
                    java.util.Set<android.speech.tts.Voice> vs = voiceProbe.getVoices();
                    if (vs != null) for (android.speech.tts.Voice v : vs) {
                        String l = v.getLocale().getLanguage();
                        if (!(l.equals("zh") || l.equals("cmn") || l.equals("yue") || l.equals("ja"))) continue;
                        all.put(v.getName() + " | " + v.getLocale().toLanguageTag() + " | net=" + v.isNetworkConnectionRequired() + " | " + v.getFeatures());
                    }
                    o.put("voices", all);
                }
                return o.toString();
            } catch (Exception e) { return "{\"error\":\"" + e + "\"}"; }
        }

        @JavascriptInterface public String engine() {
            try {
                JSONObject o = new JSONObject();
                o.put("engine", voiceProbe == null ? "" : voiceProbe.getDefaultEngine());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
    }
}
