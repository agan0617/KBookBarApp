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
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
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

    static final String HOME = "https://agan0617.github.io/KBookBar/";
    private static final String HOST = "agan0617.github.io";
    private static final int REQ_FILE = 10;
    private static final int REQ_NOTIF = 11;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private TextToSpeech voiceProbe; // 只用來列語音清單（服務還沒啟動時也要列得出來）
    private CloudTts.Player previewCloud; // 修正讀音時用雲端語音試聽
    private AlertDialog keyDialog;
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
            // 雲端語音的金鑰被拒絕：跳設定框重貼（不轉給網頁，網頁另外會收到 error 的提示）
            if (json.contains("\"type\":\"cloudKey\"")) { showKeyDialog("Azure 拒絕了這組金鑰，請確認金鑰與區域後重貼。"); return; }
            keepScreenOn(json);
            if (web != null) web.evaluateJavascript("window.KBookNativeEvent&&window.KBookNativeEvent(" + json + ")", null);
        });
        keepScreenOn(TtsService.stateJson);
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
        keepScreenOn(TtsService.stateJson);
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

    /** 念的時候螢幕不自動鎖定（WebView 不支援網頁的 Wake Lock）；暫停、停止後照手機設定的時間鎖定 */
    private void keepScreenOn(String stateJson) {
        if (stateJson != null && stateJson.contains("\"playing\":true")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onDestroy() {
        TtsService.listener = null;
        if (voiceProbe != null) voiceProbe.shutdown();
        if (previewCloud != null) previewCloud.release();
        if (keyDialog != null) keyDialog.dismiss();
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

    private void toWeb(String js) { if (web != null) web.evaluateJavascript("window.KBookNativeEvent&&window.KBookNativeEvent(" + js + ")", null); }

    private void toast(String msg) {
        try { toWeb(new JSONObject().put("type", "error").put("message", msg).toString()); } catch (Exception ignored) { }
    }

    /**
     * 雲端語音的金鑰設定框（1.8）：第一次選雲端語音、或金鑰被拒絕時跳出來。按儲存先拿金鑰試一次 Azure，
     * 對了才存；存好就通知網頁重列語音、朗讀服務改用雲端語音。
     */
    private void showKeyDialog(String note) {
        if (isFinishing() || (keyDialog != null && keyDialog.isShowing())) return;
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView msg = new TextView(this);
        msg.setText((note != null ? note + "\n\n" : "")
                + "雲端語音用的是 Azure 語音服務。到 Azure 入口網站的語音服務資源 →「金鑰與端點」，"
                + "複製「金鑰 1」和「位置/區域」貼在這裡。金鑰只存在這支手機。");
        box.addView(msg);
        EditText key = new EditText(this);
        key.setHint("金鑰 1");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);
        box.addView(key);
        EditText region = new EditText(this);
        region.setHint("區域，例如 eastasia");
        region.setText(CloudTts.region(this));
        region.setSingleLine(true);
        box.addView(region);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("設定雲端語音")
                .setView(box)
                .setPositiveButton("儲存", null)
                .setNegativeButton("取消", null);
        if (CloudTts.configured(this)) {
            key.setHint("已儲存，要換才需要重貼");
            b.setNeutralButton("清除金鑰", (d, w) -> { CloudTts.clear(this); toWeb("{type:'voices'}"); toast("已清除雲端語音的金鑰"); });
        }
        keyDialog = b.create();
        keyDialog.setOnShowListener(d -> keyDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) k = CloudTts.key(this);
            String r = region.getText().toString().trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (r.isEmpty()) r = CloudTts.DEFAULT_REGION;
            if (k.isEmpty()) { msg.setText("請貼上金鑰。"); return; }
            String fk = k, fr = r;
            v.setEnabled(false);
            msg.setText("檢查金鑰中…");
            new Thread(() -> {
                String err = null;
                try { CloudTts.check(fk, fr); } catch (CloudTts.CloudError e) { err = e.getMessage(); }
                String fe = err;
                main.post(() -> {
                    if (keyDialog == null || !keyDialog.isShowing()) return;
                    if (fe != null) { msg.setText("沒有成功：" + fe + "。確認後再按一次儲存。"); v.setEnabled(true); return; }
                    CloudTts.save(this, fk, fr);
                    keyDialog.dismiss();
                    toWeb("{type:'voices'}");
                    toast("雲端語音設定好了");
                    TtsService t = TtsService.instance; if (t != null) t.cloudConfigured();
                });
            }).start();
        }));
        keyDialog.show();
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
                // 選雲端語音但還沒設定金鑰：先跳設定框（這段時間朗讀服務先用手機語音念）
                if (CloudTts.isCloud(id) && !CloudTts.configured(MainActivity.this)) showKeyDialog(null);
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

        /** 試聽一小段（修正讀音時用）：用列語音清單的那個引擎念，不動朗讀服務 */
        @JavascriptInterface public void preview(String text, String voice, float rate, float volume) {
            main.post(() -> {
                if (text == null || text.isEmpty()) return;
                if (CloudTts.isCloud(voice) && CloudTts.configured(MainActivity.this)) {
                    if (voiceProbe != null) voiceProbe.stop();
                    if (previewCloud == null) previewCloud = new CloudTts.Player(MainActivity.this, new CloudTts.Player.Callback() {
                        @Override public void onStart(String id) { }
                        @Override public void onDone(String id) { }
                        @Override public void onError(String id, CloudTts.CloudError e) { toast("雲端語音試聽失敗：" + e.getMessage()); }
                    });
                    previewCloud.speak(voice, text, rate, volume, "preview", true);
                    return;
                }
                if (!voicesReady || voiceProbe == null) return;
                if (previewCloud != null) previewCloud.stop();
                TtsService.applyVoice(voiceProbe, CloudTts.isCloud(voice) ? "" : voice);
                voiceProbe.setSpeechRate(rate);
                Bundle p = new Bundle();
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
                voiceProbe.speak(text, TextToSpeech.QUEUE_FLUSH, p, "preview");
            });
        }

        /** 朗讀服務最後念到的位置 {bookId, ch, b, t, at}，服務停了也還在（1.7） */
        @JavascriptInterface public String lastPos() {
            return getSharedPreferences(TtsService.PREFS, MODE_PRIVATE).getString(TtsService.KEY_LAST, "");
        }

        @JavascriptInterface public String getState() {
            return TtsService.instance == null ? "{\"active\":false,\"playing\":false}" : TtsService.stateJson;
        }

        /** 雲端語音排最前面，後面接手機語音引擎的清單（引擎還沒好就只有雲端的） */
        @JavascriptInterface public String getVoices() {
            org.json.JSONArray all = CloudTts.voices(MainActivity.this);
            if (voicesReady && voiceProbe != null) {
                try {
                    org.json.JSONArray phone = new org.json.JSONArray(TtsService.voicesJson(voiceProbe));
                    for (int i = 0; i < phone.length(); i++) all.put(phone.get(i));
                } catch (Exception ignored) { }
            }
            return all.toString();
        }

        /** 換或清除雲端語音的金鑰（網頁朗讀設定的「雲端語音金鑰…」） */
        @JavascriptInterface public void cloudSetup() { main.post(() -> showKeyDialog(null)); }

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
