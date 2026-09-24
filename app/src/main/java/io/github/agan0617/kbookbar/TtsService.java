package io.github.agan0617.kbookbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 朗讀用的前景服務。瀏覽器的 speechSynthesis 在 Android 背景會被停掉，所以把朗讀搬到這裡：
 * 手機的語音引擎（優先 Google）＋ mediaPlayback 前景服務＋ MediaSession（通知欄、鎖定畫面、藍牙耳機）。
 *
 * 網頁（K書吧）透過 MainActivity 的 JS bridge 把整本書的段落送進來（start），之後念到哪一段
 * 用 {@link Listener} 回報給網頁。網頁不在前景時照樣念，回到前景再同步畫面。
 */
public class TtsService extends Service implements TextToSpeech.OnInitListener {

    public interface Listener { void onEvent(String json); }

    public static final String ACTION_START = "kbookbar.START";
    public static final String ACTION_PLAY = "kbookbar.PLAY";
    public static final String ACTION_PAUSE = "kbookbar.PAUSE";
    public static final String ACTION_NEXT = "kbookbar.NEXT";
    public static final String ACTION_PREV = "kbookbar.PREV";
    public static final String ACTION_STOP = "kbookbar.STOP";

    static final String GOOGLE_TTS = "com.google.android.tts";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    private static final int AHEAD = 3; // 預先排進語音佇列的段數，段與段之間才不會有空檔
    private static final int MAX_CHUNK = 1500;
    // 標題（章名、### 小節名）念慢一點；網頁沒送 pre（舊網頁）時，標題前後各停一下
    private static final int HEAD_GAP_MS = 700;
    private static final float HEAD_RATE = 0.85f;
    private static final String GAP_ID = "gap"; // 靜音片段的 id，parse 不出來，回呼一律忽略

    // ── 給 MainActivity 用的靜態入口（同一個 process） ──
    static volatile TtsService instance;
    static volatile Listener listener;
    static volatile String pendingStart;
    static volatile String stateJson = "{\"active\":false,\"playing\":false}";

    private static class Chapter {
        String title;
        List<String> blocks = new ArrayList<>();
        java.util.Set<Integer> heads = new java.util.HashSet<>();
        int[] pre; // 每段開始前停幾毫秒（1× 語速的基準，網頁算好送來）；舊網頁沒送就是 null
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusReq;
    private PowerManager.WakeLock wake;
    private boolean noisyRegistered;

    private String bookId = "", bookTitle = "";
    private final List<Chapter> chapters = new ArrayList<>();
    private int ch, b;           // 現在念的段
    private int qCh, qB;         // 下一段要排進佇列的位置
    private boolean playing, active, resumeOnFocus;
    private int gen;             // 每次清空佇列就 +1，舊的回呼一律忽略
    private float rate = 1f, volume = 1f;
    private String voiceName = "";
    private boolean stopAtChEnd;
    private int sleepCh = -1;
    private long sleepAt;
    private final Runnable sleepTask = () -> { sleepAt = 0; pause(); emit("sleep"); };

    // ─────────────────────────────── 生命週期 ───────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kbookbar:tts");
        wake.setReferenceCounted(false);
        createChannel();
        session = new MediaSession(this, "KBookBar");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { jump(1); }
            @Override public void onSkipToPrevious() { jump(-1); }
            @Override public void onStop() { stopAll(); }
        });
        tts = new TextToSpeech(this, this, GOOGLE_TTS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(a)) {
            goForeground();
            String json = pendingStart; pendingStart = null;
            if (json != null) load(json);
        } else if (ACTION_PLAY.equals(a)) resume();
        else if (ACTION_PAUSE.equals(a)) pause();
        else if (ACTION_NEXT.equals(a)) jump(1);
        else if (ACTION_PREV.equals(a)) jump(-1);
        else if (ACTION_STOP.equals(a)) stopAll();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        abandonFocus();
        unregisterNoisy();
        if (wake.isHeld()) wake.release();
        session.release();
        instance = null;
        stateJson = "{\"active\":false,\"playing\":false}";
        super.onDestroy();
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (!ttsReady) { emitError("手機上的語音引擎沒辦法啟動"); return; }
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> onUtterStart(id)); }
            @Override public void onDone(String id) { main.post(() -> onUtterDone(id)); }
            @Override public void onError(String id) { main.post(() -> onUtterError(id)); }
            @Override public void onStop(String id, boolean interrupted) { }
        });
        applyVoice();
        if (playing) restart();
    }

    // ─────────────────────────────── 對外操作 ───────────────────────────────

    /** start 的 JSON：{bookId, bookTitle, chapters:[{title, blocks:[...], heads:[標題段的編號], pre:[每段前停幾毫秒]}], ch, b, rate, volume, voice, sleep} */
    private void load(String json) {
        try {
            JSONObject o = new JSONObject(json);
            bookId = o.optString("bookId");
            bookTitle = o.optString("bookTitle");
            chapters.clear();
            JSONArray arr = o.getJSONArray("chapters");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                Chapter chap = new Chapter();
                chap.title = c.optString("title");
                JSONArray bl = c.getJSONArray("blocks");
                for (int k = 0; k < bl.length(); k++) chap.blocks.add(bl.getString(k));
                JSONArray hd = c.optJSONArray("heads"); // 舊版網頁沒送，就全部當內文念
                if (hd != null) for (int k = 0; k < hd.length(); k++) chap.heads.add(hd.optInt(k, -1));
                JSONArray pr = c.optJSONArray("pre");
                if (pr != null) { chap.pre = new int[pr.length()]; for (int k = 0; k < pr.length(); k++) chap.pre[k] = Math.max(0, pr.optInt(k, 0)); }
                chapters.add(chap);
            }
            ch = clamp(o.optInt("ch"), 0, chapters.size() - 1);
            b = Math.max(0, o.optInt("b"));
            rate = (float) o.optDouble("rate", 1);
            volume = (float) o.optDouble("volume", 1);
            voiceName = o.optString("voice", "");
            applyVoice();
            setSleep(o.optString("sleep", "0"));
            active = true;
            play();
        } catch (JSONException e) {
            emitError("朗讀資料讀不懂：" + e.getMessage());
        }
    }

    void resume() { if (active) play(); }

    void pause() {
        if (!playing) return;
        playing = false; gen++;
        if (tts != null) tts.stop();
        if (wake.isHeld()) wake.release();
        unregisterNoisy();
        updateSession(); updateNotification(); emitPos();
    }

    void jump(int delta) {
        if (!active || chapters.isEmpty()) return;
        int nb = b + delta, nc = ch;
        while (nb < 0 && nc > 0) { nc--; nb += chapters.get(nc).blocks.size(); }
        while (nc < chapters.size() - 1 && nb >= chapters.get(nc).blocks.size()) { nb -= chapters.get(nc).blocks.size(); nc++; }
        ch = nc; b = clamp(nb, 0, Math.max(0, chapters.get(nc).blocks.size() - 1));
        if (playing) restart(); else { updateSession(); emitPos(); }
    }

    void seek(int c, int blk) {
        if (!active || chapters.isEmpty()) return;
        ch = clamp(c, 0, chapters.size() - 1); b = Math.max(0, blk);
        if (playing) restart(); else emitPos();
    }

    void stopAll() {
        playing = false; active = false; gen++;
        if (tts != null) tts.stop();
        main.removeCallbacks(sleepTask); sleepAt = 0; stopAtChEnd = false;
        emitPos();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    void setRate(float r) { rate = r; if (playing) restart(); }
    void setVolume(float v) { volume = v; if (playing) restart(); }
    void setVoice(String name) { voiceName = name == null ? "" : name; applyVoice(); if (playing) restart(); }

    /** "0"＝取消、"ch"＝念完這章、其他＝分鐘數 */
    void setSleep(String v) {
        main.removeCallbacks(sleepTask); sleepAt = 0; stopAtChEnd = false; sleepCh = -1;
        if ("ch".equals(v)) { stopAtChEnd = true; sleepCh = ch; }
        else {
            int m = 0; try { m = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
            if (m > 0) { sleepAt = System.currentTimeMillis() + m * 60000L; main.postDelayed(sleepTask, m * 60000L); }
        }
        emitPos();
    }

    // ─────────────────────────────── 念書本體 ───────────────────────────────

    private void play() {
        if (chapters.isEmpty()) return;
        if (!requestFocus()) { emitError("拿不到音訊（可能正在通話）"); return; }
        playing = true;
        wake.acquire(4 * 60 * 60 * 1000L);
        registerNoisy();
        goForeground();
        restart();
    }

    /** 清空佇列，從目前這一段開頭重新排 */
    private void restart() {
        gen++;
        if (!ttsReady) { updateSession(); emitPos(); return; } // 引擎好了 onInit 會再叫一次
        tts.stop();
        tts.setSpeechRate(rate);
        qCh = ch; qB = b;
        boolean first = true;
        for (int i = 0; i < AHEAD; i++) { if (!enqueueNext(first)) break; first = false; }
        updateSession(); updateNotification(); emitPos();
    }

    /** 把 (qCh, qB) 那一段排進佇列；沒有下一段了回傳 false */
    private boolean enqueueNext(boolean flush) {
        while (qCh < chapters.size() && qB >= chapters.get(qCh).blocks.size()) { qCh++; qB = 0; }
        if (qCh >= chapters.size()) return false;
        List<String> chunks = split(chapters.get(qCh).blocks.get(qB));
        boolean head = !chunks.isEmpty() && chapters.get(qCh).heads.contains(qB);
        if (chunks.isEmpty()) chunks.add("　");
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        Chapter chap = chapters.get(qCh);
        // 接著上一段念到新的一段：先停一下（剛按播放的那段不停）。網頁有送 pre 就照它（換段、標題前後、換章長短不同），
        // 舊網頁只有 heads，就只在標題前後停
        if (!flush) {
            int gap = chap.pre != null ? (qB < chap.pre.length ? chap.pre[qB] : 0)
                    : (head || (qB > 0 && chap.heads.contains(qB - 1))) ? HEAD_GAP_MS : 0;
            gap = Math.round(gap / (float) Math.sqrt(Math.max(1f, rate))); // 語速快時停頓跟著縮短，但縮得比語速慢
            if (gap > 0) tts.playSilentUtterance(gap, TextToSpeech.QUEUE_ADD, GAP_ID);
        }
        // 語速在 speak 當下就跟著這一句排進佇列，所以改完馬上改回來不影響前後句
        if (head) tts.setSpeechRate(rate * HEAD_RATE);
        for (int s = 0; s < chunks.size(); s++) {
            String id = gen + "|" + qCh + "|" + qB + "|" + s + "|" + (s == chunks.size() - 1 ? 1 : 0);
            tts.speak(chunks.get(s), flush && s == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
        }
        if (head) tts.setSpeechRate(rate);
        qB++;
        return true;
    }

    private int errs; // 連續出錯次數：語音沒下載、沒網路時每句都會錯，不擋的話會一路「跳」完整本書

    private void onUtterError(String id) {
        int[] p = parse(id); if (p == null || p[0] != gen || !playing) return;
        if (++errs > 3) { errs = 0; pause(); emitError("朗讀一直出錯，可能是這個語音還沒下載好或沒有網路，換個語音試試"); return; }
        onUtterDone(id);
    }

    private void onUtterStart(String id) {
        int[] p = parse(id); if (p == null || p[0] != gen) return;
        errs = 0;
        if (stopAtChEnd && sleepCh >= 0 && p[1] > sleepCh) { stopAtChEnd = false; sleepCh = -1; pause(); emit("sleep"); return; }
        if (p[3] == 0) {
            boolean chapterChanged = p[1] != ch;
            ch = p[1]; b = p[2];
            if (chapterChanged) updateSession();
            emitPos();
        }
    }

    private void onUtterDone(String id) {
        int[] p = parse(id); if (p == null || p[0] != gen || !playing) return;
        if (p[4] != 1) return; // 一段裡的中間片段
        if (!enqueueNext(false) && p[1] == lastQueuedCh() && isLastBlock(p[1], p[2])) {
            // 全書念完
            playing = false; active = true; b = p[2];
            if (wake.isHeld()) wake.release();
            updateSession(); updateNotification(); emit("end");
        }
    }

    private int lastQueuedCh() { return Math.min(qCh, chapters.size() - 1); }
    private boolean isLastBlock(int c, int blk) { return c == chapters.size() - 1 && blk >= chapters.get(c).blocks.size() - 1; }

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;…]+[。！？!?；;…]*[」』”’\"'）)]*|[。！？!?；;…]+");

    /** 太長的段落照句子切開（引擎單次有長度上限） */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        text = text.trim();
        if (text.isEmpty()) return out;
        if (text.length() <= MAX_CHUNK) { out.add(text); return out; }
        StringBuilder buf = new StringBuilder();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            String s = m.group();
            if (buf.length() + s.length() > MAX_CHUNK && buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
            while (s.length() > MAX_CHUNK) { out.add(s.substring(0, MAX_CHUNK)); s = s.substring(MAX_CHUNK); }
            buf.append(s);
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private static int[] parse(String id) {
        if (id == null) return null;
        String[] a = id.split("\\|");
        if (a.length != 5) return null;
        try {
            return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])};
        } catch (NumberFormatException e) { return null; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ─────────────────────────────── 語音選擇 ───────────────────────────────

    private void applyVoice() {
        if (!ttsReady) return;
        if (!applyVoice(tts, voiceName)) emitError("選的語音還沒下載好，先用預設語音念；下載完成後再選一次");
    }

    /** 設成指定的語音；沒指定或找不到就用台灣國語預設。選的語音還沒下載時回傳 false（改用預設） */
    static boolean applyVoice(TextToSpeech t, String name) {
        if (name != null && !name.isEmpty()) {
            try {
                for (Voice v : t.getVoices()) if (v.getName().equals(name)) {
                    if (!notInstalled(v)) { t.setVoice(v); return true; }
                    t.setLanguage(Locale.TAIWAN);
                    return false;
                }
            } catch (Exception ignored) { }
        }
        t.setLanguage(Locale.TAIWAN);
        return true;
    }

    static boolean notInstalled(Voice v) {
        return v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private static final Pattern SPEAKER = Pattern.compile("-x-([a-z]+)-(local|network)$");

    /** Google 的語音名稱像 cmn-tw-x-ctc-local：ctc 是哪一位聲音，local／network 是本機／線上 */
    private static String speaker(Voice v) {
        Matcher m = SPEAKER.matcher(v.getName());
        return m.find() ? m.group(1) : v.getName();
    }

    /**
     * 給網頁列語音清單：中文、日文的每一位聲音（本機／線上各一筆），沒下載的也列、標「未下載」，
     * 選了會帶去下載頁。同一語言的聲音依代號編成「聲音 1、2、3…」，比 ctc／jab 這種代號好認。
     */
    static String voicesJson(TextToSpeech t) {
        JSONArray arr = new JSONArray();
        try {
            List<Voice> list = new ArrayList<>();
            for (Voice v : t.getVoices()) {
                if (group(v) == null) continue;
                // 「zh-TW-language」這種是舊 API 的語言代表，不是一位聲音
                if (v.getFeatures() != null && v.getFeatures().contains("legacySetLanguageVoice")) continue;
                list.add(v);
            }
            // 每個語系裡的聲音代號排序後編號
            java.util.Map<String, List<String>> speakers = new java.util.HashMap<>();
            for (Voice v : list) {
                List<String> s = speakers.computeIfAbsent(v.getLocale().toLanguageTag(), k -> new ArrayList<>());
                if (!s.contains(speaker(v))) s.add(speaker(v));
            }
            for (List<String> s : speakers.values()) java.util.Collections.sort(s);
            list.sort((x, y) -> {
                int rx = rank(x), ry = rank(y);
                if (rx != ry) return rx - ry;
                int ix = notInstalled(x) ? 1 : 0, iy = notInstalled(y) ? 1 : 0;
                if (ix != iy) return ix - iy;
                int sx = speaker(x).compareTo(speaker(y));
                if (sx != 0) return sx;
                return Boolean.compare(x.isNetworkConnectionRequired(), y.isNetworkConnectionRequired());
            });
            for (Voice v : list) {
                JSONObject o = new JSONObject();
                String tag = v.getLocale().toLanguageTag();
                int n = speakers.get(tag).indexOf(speaker(v)) + 1;
                String label = v.getLocale().getDisplayName(Locale.TRADITIONAL_CHINESE) + " · 聲音 " + n
                        + " · " + (v.isNetworkConnectionRequired() ? "線上" : "本機")
                        + (notInstalled(v) ? "（未下載）" : "");
                o.put("id", v.getName());
                o.put("label", label);
                o.put("lang", tag);
                o.put("group", group(v));
                o.put("installed", !notInstalled(v));
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr.toString();
    }

    private static String group(Voice v) {
        String lang = v.getLocale().getLanguage();
        if (lang.equals("zh") || lang.equals("cmn") || lang.equals("yue")) return "中文";
        if (lang.equals("ja")) return "日文";
        return null;
    }

    /** 分組排序：台灣國語、大陸普通話、粵語、日文 */
    private static int rank(Voice v) {
        String c = v.getLocale().getCountry();
        return "日文".equals(group(v)) ? 3 : c.equals("TW") ? 0 : c.equals("HK") ? 2 : 1;
    }

    // ─────────────────────────────── 音訊焦點、耳機拔出 ───────────────────────────────

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (playing) { resumeOnFocus = true; pause(); }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocus) { resumeOnFocus = false; resume(); }
    });

    private boolean requestFocus() {
        if (focusReq == null) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build();
        }
        return audio.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    }

    private void abandonFocus() { if (focusReq != null) audio.abandonAudioFocusRequest(focusReq); }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ─────────────────────────────── 通知欄、MediaSession ───────────────────────────────

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        c.setDescription(getString(R.string.channel_desc));
        c.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    private void goForeground() {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void updateNotification() {
        if (!active) return;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private PendingIntent svc(String action, int code) {
        Intent i = new Intent(this, TtsService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private String chapterTitle() {
        return (ch >= 0 && ch < chapters.size()) ? chapters.get(ch).title : "";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action prev = new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一段", svc(ACTION_PREV, 1)).build();
        Notification.Action toggle = playing
                ? new Notification.Action.Builder(android.R.drawable.ic_media_pause, "暫停", svc(ACTION_PAUSE, 2)).build()
                : new Notification.Action.Builder(android.R.drawable.ic_media_play, "播放", svc(ACTION_PLAY, 3)).build();
        Notification.Action next = new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一段", svc(ACTION_NEXT, 4)).build();
        Notification.Action stop = new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "結束", svc(ACTION_STOP, 5)).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_book)
                .setContentTitle(chapterTitle().isEmpty() ? "K書吧" : chapterTitle())
                .setContentText(bookTitle)
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(prev).addAction(toggle).addAction(next).addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void updateSession() {
        session.setActive(active);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapterTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, bookTitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "K書吧")
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, rate)
                .build());
    }

    // ─────────────────────────────── 回報給網頁 ───────────────────────────────

    private JSONObject baseState() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("active", active);
        o.put("playing", playing);
        o.put("bookId", bookId);
        o.put("ch", ch);
        o.put("b", b);
        o.put("sleepAt", sleepAt);
        o.put("stopAtChEnd", stopAtChEnd);
        return o;
    }

    private void emitPos() { emit("pos"); }

    private void emit(String type) {
        try {
            JSONObject o = baseState();
            stateJson = o.toString();
            o.put("type", type);
            send(o.toString());
        } catch (JSONException ignored) { }
    }

    private void emitError(String msg) {
        try {
            JSONObject o = baseState();
            o.put("type", "error");
            o.put("message", msg);
            send(o.toString());
        } catch (JSONException ignored) { }
    }

    private static void send(String json) {
        Listener l = listener;
        if (l != null) l.onEvent(json);
    }
}
