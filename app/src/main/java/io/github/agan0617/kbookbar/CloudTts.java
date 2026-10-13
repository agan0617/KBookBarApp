package io.github.agan0617.kbookbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 雲端語音（1.8）：Azure 的神經語音，比手機的 Google 語音自然很多。邊聽邊合成——念到哪一段才把那段送去
 * 合成成 mp3，存在快取裡用 MediaPlayer 播；預先合成後面幾段，段與段之間才不會卡。
 *
 * 金鑰、區域由 Ken 在 App 裡自己貼（選雲端語音時跳出來問），只存在這支手機的 SharedPreferences，
 * 不寫進 repo（這個 repo 是公開的）。免費方案（F0）每月 50 萬字，用完 Azure 回 429，不會扣錢；
 * 這時朗讀服務改用手機語音念。
 */
final class CloudTts {

    static final String PREFIX = "azure:";
    private static final String PREFS = "cloud";
    static final String DEFAULT_REGION = "eastasia";
    private static final String FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    // Azure 語音本身比手機語音快：合成時就放慢（比播放時放慢自然），App 的語速設定再疊在上面。
    // 倍率是網頁朗讀設定的「雲端語音速度」（1.11），存在這支手機；修正讀音的試聽也用它
    static final float DEFAULT_BASE_RATE = 0.8f;
    private static final long CACHE_MAX = 300L * 1024 * 1024; // 快取超過就刪最舊的（重聽同一段不用再花額度）

    /** {Azure 語音名稱, 語系, 顯示名稱} */
    private static final String[][] VOICES = {
            {"zh-TW-HsiaoChenNeural", "zh-TW", "曉臻（女）"},
            {"zh-TW-HsiaoYuNeural", "zh-TW", "曉雨（女）"},
            {"zh-TW-YunJheNeural", "zh-TW", "雲哲（男）"},
            {"ja-JP-NanamiNeural", "ja-JP", "七海（女）"},
            {"ja-JP-KeitaNeural", "ja-JP", "圭太（男）"},
    };

    private CloudTts() { }

    static boolean isCloud(String voice) { return voice != null && voice.startsWith(PREFIX); }

    // ─────────────────────────────── 金鑰設定 ───────────────────────────────

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String key(Context c) { return prefs(c).getString("key", ""); }
    static String region(Context c) { return prefs(c).getString("region", DEFAULT_REGION); }
    static boolean configured(Context c) { return !key(c).isEmpty(); }
    static void save(Context c, String key, String region) { holdUntil = 0; prefs(c).edit().putString("key", key).putString("region", region).apply(); }
    static void clear(Context c) { prefs(c).edit().remove("key").apply(); }
    static float baseRate(Context c) { return prefs(c).getFloat("baseRate", DEFAULT_BASE_RATE); }
    static void setBaseRate(Context c, float r) { prefs(c).edit().putFloat("baseRate", Math.max(0.5f, Math.min(1.5f, r))).apply(); }

    /** 區域（eastasia）換成網址；填完整網址的話照用（測試時指到本機的假伺服器） */
    private static String base(String region) {
        return region.startsWith("http") ? region.replaceAll("/+$", "") : "https://" + region + ".tts.speech.microsoft.com";
    }

    /** 給網頁列語音清單：排在手機語音前面，網頁會依語系分進「中文（台灣）」「日文」 */
    static JSONArray voices(Context c) {
        JSONArray arr = new JSONArray();
        boolean ok = configured(c);
        try {
            for (String[] v : VOICES) {
                JSONObject o = new JSONObject();
                o.put("id", PREFIX + v[0]);
                o.put("label", "雲端 · " + v[2] + (ok ? "" : "（要先設定金鑰）"));
                o.put("lang", v[1]);
                o.put("group", v[1].startsWith("ja") ? "日文" : "中文");
                o.put("installed", true);
                arr.put(o);
            }
        } catch (JSONException ignored) { }
        return arr;
    }

    // ─────────────────────────────── 合成 ───────────────────────────────

    /** 合成失敗的原因：金鑰／區域錯（AUTH）、太多請求或額度用完（LIMIT）、網路（NET）、這一段本身合成不了（BAD） */
    static final class CloudError extends IOException {
        static final int AUTH = 1, LIMIT = 2, NET = 3, BAD = 4;
        final int kind;
        CloudError(int kind, String msg) { super(msg); this.kind = kind; }
    }

    /** 試金鑰：拿語音清單，200 就是對的（在背景執行緒呼叫） */
    static void check(String key, String region) throws CloudError {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(base(region) + "/cognitiveservices/voices/list").openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(15000);
            h.setRequestProperty("Ocp-Apim-Subscription-Key", key);
            int code = h.getResponseCode();
            if (code == 200) return;
            throw fail(code);
        } catch (CloudError e) { throw e; }
        catch (IOException e) { android.util.Log.w("KBookCloud", "check: " + e); throw new CloudError(CloudError.NET, "連不上 Azure，確認網路"); }
        finally { if (h != null) h.disconnect(); }
    }

    // 金鑰不對、額度用完時，一分鐘內排在後面的段落直接當失敗，不再一段段送去碰壁（朗讀服務已經改用手機語音）
    private static volatile long holdUntil;
    private static volatile CloudError holdErr;

    private static CloudError hold(CloudError e) {
        if (e.kind == CloudError.AUTH || e.kind == CloudError.LIMIT) { holdErr = e; holdUntil = System.currentTimeMillis() + 60000; }
        return e;
    }

    private static CloudError fail(int code) {
        if (code == 401 || code == 403) return new CloudError(CloudError.AUTH, "金鑰或區域不對（HTTP " + code + "）");
        if (code == 429) return new CloudError(CloudError.LIMIT, "太多請求或本月免費額度用完（HTTP 429）");
        if (code >= 500) return new CloudError(CloudError.NET, "Azure 暫時有問題（HTTP " + code + "）");
        return new CloudError(CloudError.BAD, "這一段合成不了（HTTP " + code + "）");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 一段文字合成成 mp3（在背景執行緒呼叫）。合成過的直接拿快取；太多請求、網路斷了會稍等再試兩次 */
    static File synth(Context c, String voice, String text, String rate) throws IOException {
        String name = voice.substring(PREFIX.length());
        File dir = new File(c.getCacheDir(), "cloudtts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new CloudError(CloudError.BAD, "快取資料夾建不起來");
        File f = new File(dir, sha1(name + "\n" + rate + "\n" + text) + ".mp3");
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f; }
        CloudError held = holdErr;
        if (held != null && System.currentTimeMillis() < holdUntil) throw new CloudError(held.kind, held.getMessage());

        String lang = name.substring(0, 5);
        byte[] body = ("<speak version='1.0' xml:lang='" + lang + "'><voice name='" + name + "'><prosody rate='" + rate + "'>" + esc(text) + "</prosody></voice></speak>")
                .getBytes(StandardCharsets.UTF_8);
        String url = base(region(c)) + "/cognitiveservices/v1", key = key(c);
        CloudError last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(attempt == 1 ? 1000 : 3000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CloudError(CloudError.NET, "取消"); }
            }
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(10000); h.setReadTimeout(30000);
                h.setDoOutput(true); h.setRequestMethod("POST");
                h.setRequestProperty("Ocp-Apim-Subscription-Key", key);
                h.setRequestProperty("Content-Type", "application/ssml+xml");
                h.setRequestProperty("X-Microsoft-OutputFormat", FORMAT);
                h.setRequestProperty("User-Agent", "KBookBarApp/" + BuildConfigVersion.NAME);
                h.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = h.getOutputStream()) { o.write(body); }
                int code = h.getResponseCode();
                if (code != 200) {
                    last = fail(code);
                    if (last.kind == CloudError.LIMIT || last.kind == CloudError.NET) continue;
                    throw hold(last);
                }
                File tmp = new File(dir, f.getName() + ".part");
                try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (tmp.length() == 0 || !tmp.renameTo(f)) { tmp.delete(); throw new CloudError(CloudError.BAD, "Azure 回了空的音檔"); }
                prune(dir);
                return f;
            } catch (CloudError e) { throw e; }
            catch (IOException e) {
                android.util.Log.w("KBookCloud", "synth attempt " + attempt + ": " + e);
                new File(dir, f.getName() + ".part").delete();
                last = new CloudError(CloudError.NET, "連不上 Azure（網路不通或訊號不好）");
            }
            finally { if (h != null) h.disconnect(); }
        }
        throw hold(last);
    }

    private static int writes;

    private static synchronized void prune(File dir) {
        if (++writes % 20 != 0) return;
        File[] fs = dir.listFiles(); if (fs == null) return;
        long total = 0; for (File x : fs) total += x.length();
        if (total <= CACHE_MAX) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File x : fs) { if (total <= CACHE_MAX * 8 / 10) break; total -= x.length(); x.delete(); }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    // ─────────────────────────────── 播放佇列 ───────────────────────────────

    /**
     * 照手機語音引擎的用法包一層：speak／silence 排進佇列，onStart／onDone／onError 用同樣的 id 回報，
     * 朗讀服務的換段、存進度、念完整本書的邏輯就不用分兩套。回呼一律在主執行緒。
     * 語速用 MediaPlayer 的播放速度調（合成一律 1×，換語速不用重新合成，快取也共用）。
     */
    static final class Player {
        interface Callback {
            void onStart(String id);
            void onDone(String id);
            void onError(String id, CloudError e);
        }

        private static final class Item {
            String id, voice, text;
            float speed, volume;
            int silenceMs = -1;
            Future<File> audio;
        }

        private final Context ctx;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService synthPool = Executors.newSingleThreadExecutor(); // 一次只送一個請求（免費方案每分鐘 20 次）
        private final ExecutorService waitPool = Executors.newCachedThreadPool(); // 等音檔的，不能卡在前一個沒等完的後面
        /** 合成中的段落：跳段、換語速會清掉佇列重排，同一段還在合成就共用那個請求，不重送（省額度） */
        private static final ConcurrentHashMap<String, Future<File>> inflight = new ConcurrentHashMap<>();
        private final ArrayDeque<Item> queue = new ArrayDeque<>();
        private Item current;
        private MediaPlayer mp;
        private int epoch; // 每次 stop 就 +1，舊的回呼一律忽略

        Player(Context ctx, Callback cb) { this.ctx = ctx.getApplicationContext(); this.cb = cb; }

        void speak(String voice, String text, float speed, float volume, String id, boolean flush) {
            if (flush) stop();
            Item it = new Item();
            it.id = id; it.voice = voice; it.text = text == null ? "" : text.trim();
            it.speed = speed; it.volume = volume;
            if (!it.text.isEmpty()) {
                String rate = String.format(java.util.Locale.ROOT, "%.2f", baseRate(ctx));
                String k = it.voice + "\n" + rate + "\n" + it.text;
                Future<File> old = inflight.get(k);
                if (old != null && old.isDone()) inflight.remove(k, old); // 已經送完的（成功的會在快取裡）
                it.audio = inflight.computeIfAbsent(k, x -> synthPool.submit(() -> {
                    try { return synth(ctx, it.voice, it.text, rate); } finally { inflight.remove(x); }
                }));
            }
            add(it);
        }

        void silence(int ms, String id) {
            Item it = new Item();
            it.id = id; it.silenceMs = ms;
            add(it);
        }

        void stop() {
            epoch++;
            // 還沒開始送的取消；正在送的讓它送完存進快取（中斷的話那段額度就白花了）
            for (Item it : queue) if (it.audio != null && it.audio.cancel(false)) inflight.values().remove(it.audio);
            queue.clear();
            current = null;
            main.removeCallbacksAndMessages(null);
            releasePlayer();
        }

        void release() { stop(); synthPool.shutdownNow(); waitPool.shutdownNow(); }

        private void add(Item it) { queue.add(it); if (current == null) next(); }

        private void next() {
            current = queue.poll();
            if (current == null) return;
            Item it = current; int ep = epoch;
            if (it.silenceMs >= 0) { main.postDelayed(() -> { if (ep == epoch) finish(it); }, it.silenceMs); return; }
            if (it.audio == null) { cb.onStart(it.id); finish(it); return; } // 空白段落
            waitPool.execute(() -> {
                try {
                    File f = it.audio.get();
                    main.post(() -> { if (ep == epoch) play(it, f); });
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    CloudError err = c instanceof CloudError ? (CloudError) c : new CloudError(CloudError.NET, String.valueOf(c.getMessage()));
                    main.post(() -> { if (ep == epoch) failed(it, err); });
                }
            });
        }

        private void play(Item it, File f) {
            releasePlayer();
            MediaPlayer p = new MediaPlayer();
            mp = p;
            try {
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                p.setDataSource(f.getPath());
                p.prepare();
                p.setVolume(it.volume, it.volume);
                p.setOnCompletionListener(x -> { if (mp == p) { releasePlayer(); finish(it); } });
                p.setOnErrorListener((x, what, extra) -> {
                    if (mp == p) { releasePlayer(); f.delete(); failed(it, new CloudError(CloudError.BAD, "音檔放不出來（" + what + "）")); }
                    return true;
                });
                cb.onStart(it.id);
                if (mp != p) return; // onStart 裡停掉了（例如定時關閉）
                p.setPlaybackParams(new PlaybackParams().setSpeed(it.speed));
                if (!p.isPlaying()) p.start();
            } catch (Exception e) {
                releasePlayer(); f.delete();
                failed(it, new CloudError(CloudError.BAD, "音檔放不出來（" + e.getMessage() + "）"));
            }
        }

        private void finish(Item it) { if (current != it) return; current = null; cb.onDone(it.id); if (current == null) next(); }

        private void failed(Item it, CloudError e) { if (current != it) return; current = null; cb.onError(it.id, e); if (current == null) next(); }

        private void releasePlayer() {
            if (mp == null) return;
            MediaPlayer p = mp; mp = null;
            try { p.reset(); } catch (Exception ignored) { }
            p.release();
        }
    }
}
