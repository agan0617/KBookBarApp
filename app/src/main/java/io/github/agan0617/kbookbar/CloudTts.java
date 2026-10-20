package io.github.agan0617.kbookbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
            // 台灣國語只有雲哲一個男聲，對白換聲音（1.13）的男角要另一個男聲：多語言的雲逸講台灣國語
            // （語系寫 zh-TW，合成時包 <lang xml:lang='zh-TW'>），或大陸腔的雲希、雲澤
            {"zh-CN-YunyiMultilingualNeural", "zh-TW", "雲逸（男，多語言講台灣國語）"},
            {"zh-CN-YunxiNeural", "zh-CN", "雲希（男，大陸腔）"},
            {"zh-CN-YunzeNeural", "zh-CN", "雲澤（男，大陸腔，偏老）"},
            // 說書用的大陸男聲（1.15，Ken 2026-10-20 要都能選）；「#風格」是 Azure 的說話風格（mstts:express-as）
            {"zh-CN-YunzeNeural#documentary-narration", "zh-CN", "雲澤（男，大陸腔，紀錄片旁白）"},
            {"zh-CN-YunxiNeural#narration-relaxed", "zh-CN", "雲希（男，大陸腔，輕鬆旁白）"},
            {"zh-CN-YunyangNeural#narration-professional", "zh-CN", "雲揚（男，大陸腔，專業旁白）"},
            {"zh-CN-YunjianNeural", "zh-CN", "雲健（男，大陸腔）"},
            {"zh-CN-YunyeNeural", "zh-CN", "雲野（男，大陸腔）"},
            {"zh-CN-YunfengNeural", "zh-CN", "雲楓（男，大陸腔）"},
            // 對白換聲音的女角（1.14，Ken 2026-10-20 選的）：女角曉曉、少女曉伊、老女曉秋
            {"zh-CN-XiaoxiaoNeural", "zh-CN", "曉曉（女，大陸腔）"},
            {"zh-CN-XiaoyiNeural", "zh-CN", "曉伊（女，大陸腔，少女）"},
            {"zh-CN-XiaoqiuNeural", "zh-CN", "曉秋（女，大陸腔，偏年長）"},
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

    /** 音高倍率（對白換聲音用，1.13）換成 SSML 的相對音高；1 倍回傳空字串（不加 pitch，快取鍵也跟舊版一樣） */
    static String pitchAttr(float pitch) {
        int pct = Math.round((pitch - 1f) * 100f);
        return pct == 0 ? "" : (pct > 0 ? "+" : "") + pct + "%";
    }

    // 每份 SSML 的開頭：mstts 命名空間給說話風格用
    private static final String SPEAK_OPEN = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='";

    /**
     * 一個聲音的 SSML 片段。name 可以帶「#風格」（zh-CN-YunzeNeural#documentary-narration，1.15），包一層
     * mstts:express-as；多語言聲音（雲逸）用台灣國語念：包一層 <lang>，不然它照自己的語系（大陸腔）念
     */
    private static String voiceXml(String name, String rate, String pitch, String text) {
        int hash = name.indexOf('#');
        String voice = hash < 0 ? name : name.substring(0, hash), style = hash < 0 ? "" : name.substring(hash + 1);
        String inner = voice.contains("Multilingual") ? "<lang xml:lang='zh-TW'>" + esc(text) + "</lang>" : esc(text);
        String body = "<prosody rate='" + rate + "'" + (pitch.isEmpty() ? "" : " pitch='" + pitch + "'") + ">" + inner + "</prosody>";
        if (!style.isEmpty()) body = "<mstts:express-as style='" + style + "'>" + body + "</mstts:express-as>";
        return "<voice name='" + voice + "'>" + body + "</voice>";
    }

    /** 一段文字合成成 mp3（在背景執行緒呼叫）。合成過的直接拿快取；太多請求、網路斷了會稍等再試兩次 */
    static File synth(Context c, String voice, String text, String rate, String pitch) throws IOException {
        String name = voice.substring(PREFIX.length());
        String ssml = SPEAK_OPEN + name.substring(0, 5) + "'>" + voiceXml(name, rate, pitch, text) + "</speak>";
        return post(c, sha1(name + "\n" + rate + (pitch.isEmpty() ? "" : "\n" + pitch) + "\n" + text), ssml);
    }

    /** 一個合成片段：哪個聲音、音高、文字 */
    static final class Part {
        final String voice, text; final float pitch;
        Part(String voice, String text, float pitch) { this.voice = voice; this.text = text; this.pitch = pitch; }
    }

    /**
     * 一段話裡的旁白、對白（不同聲音）合成成同一個音檔（1.14）。分開合成的話每換一次聲音就換一個音檔，
     * 音檔頭尾的靜音加上播放器重新載入，每句對白前都會頓一下
     */
    static File synthMulti(Context c, List<Part> parts, String rate) throws IOException {
        StringBuilder key = new StringBuilder("multi\n").append(rate);
        StringBuilder ssml = new StringBuilder(SPEAK_OPEN + "zh-TW'>");
        for (Part p : parts) {
            String name = p.voice.substring(PREFIX.length()), pitch = pitchAttr(p.pitch);
            key.append('\n').append(name).append('|').append(pitch).append('|').append(p.text);
            ssml.append(voiceXml(name, rate, pitch, p.text));
        }
        return post(c, sha1(key.toString()), ssml.append("</speak>").toString());
    }

    /** 送 SSML 合成，存進快取（檔名是 hash）；已經有就直接用 */
    private static File post(Context c, String hash, String ssml) throws IOException {
        File dir = new File(c.getCacheDir(), "cloudtts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new CloudError(CloudError.BAD, "快取資料夾建不起來");
        File f = new File(dir, hash + ".mp3");
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f; }
        CloudError held = holdErr;
        if (held != null && System.currentTimeMillis() < holdUntil) throw new CloudError(held.kind, held.getMessage());

        byte[] body = ssml.getBytes(StandardCharsets.UTF_8);
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
        // 1.16：所有段落的播放器共用同一個音訊工作階段，念書期間再放一條無聲音軌墊著。不然每段一個新播放器、
        // 段與段之間又完全不出聲，手機的音效處理、藍牙耳機每段都重新起步，聽起來是每段開頭先變小聲
        private final int session;
        private AudioTrack keep;
        private final Runnable idleOff = () -> keepAlive(false);

        Player(Context ctx, Callback cb) {
            this.ctx = ctx.getApplicationContext(); this.cb = cb;
            session = ((AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE)).generateAudioSessionId();
        }

        private static AudioAttributes attrs() {
            return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        }

        /** 無聲音軌（0.5 秒的全零樣本循環播放），跟播放器同一個工作階段；念書時開著，停了就關 */
        private void keepAlive(boolean on) {
            if (on) {
                main.removeCallbacks(idleOff);
                if (keep != null) return;
                try {
                    int rate = 8000, frames = rate / 2;
                    AudioTrack t = new AudioTrack(attrs(), new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                            frames * 2, AudioTrack.MODE_STATIC, session);
                    t.write(new short[frames], 0, frames);
                    t.setLoopPoints(0, frames, -1);
                    t.play();
                    keep = t;
                } catch (Exception e) { android.util.Log.w("KBookCloud", "keepAlive: " + e); }
            } else if (keep != null) {
                AudioTrack t = keep; keep = null;
                try { t.stop(); } catch (Exception ignored) { }
                t.release();
            }
        }

        void speak(String voice, String text, float speed, float volume, String id, boolean flush) {
            speak(voice, text, speed, volume, id, flush, 1f);
        }

        /** pitch：音高倍率（對白換聲音的男女老少，1.13），1 倍就是原本的聲音 */
        void speak(String voice, String text, float speed, float volume, String id, boolean flush, float pitch) {
            if (flush) stop();
            Item it = new Item();
            it.id = id; it.voice = voice; it.text = text == null ? "" : text.trim();
            it.speed = speed; it.volume = volume;
            if (!it.text.isEmpty()) {
                String rate = String.format(java.util.Locale.ROOT, "%.2f", baseRate(ctx));
                String p = pitchAttr(pitch);
                String k = it.voice + "\n" + rate + "\n" + p + "\n" + it.text;
                Future<File> old = inflight.get(k);
                if (old != null && old.isDone()) inflight.remove(k, old); // 已經送完的（成功的會在快取裡）
                it.audio = inflight.computeIfAbsent(k, x -> synthPool.submit(() -> {
                    try { return synth(ctx, it.voice, it.text, rate, p); } finally { inflight.remove(x); }
                }));
            }
            add(it);
        }

        /** 一段話裡好幾個聲音（旁白＋對白）合成成一個音檔排進佇列（1.14）；只有一個聲音就照舊（快取共用） */
        void speakMulti(List<Part> parts, float speed, float volume, String id, boolean flush) {
            if (parts.size() == 1) { Part p = parts.get(0); speak(p.voice, p.text, speed, volume, id, flush, p.pitch); return; }
            if (flush) stop();
            Item it = new Item();
            it.id = id; it.speed = speed; it.volume = volume;
            StringBuilder all = new StringBuilder();
            for (Part p : parts) all.append(p.text);
            it.text = all.toString().trim();
            if (!it.text.isEmpty()) {
                String rate = String.format(java.util.Locale.ROOT, "%.2f", baseRate(ctx));
                StringBuilder k = new StringBuilder("multi\n").append(rate);
                for (Part p : parts) k.append('\n').append(p.voice).append('|').append(p.pitch).append('|').append(p.text);
                List<Part> ps = new ArrayList<>(parts);
                Future<File> old = inflight.get(k.toString());
                if (old != null && old.isDone()) inflight.remove(k.toString(), old);
                it.audio = inflight.computeIfAbsent(k.toString(), x -> synthPool.submit(() -> {
                    try { return synthMulti(ctx, ps, rate); } finally { inflight.remove(x); }
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
            keepAlive(false);
        }

        void release() { stop(); synthPool.shutdownNow(); waitPool.shutdownNow(); }

        private void add(Item it) { queue.add(it); keepAlive(true); if (current == null) next(); }

        private void next() {
            current = queue.poll();
            // 佇列空了（全書念完、或後面的還沒排進來）：5 秒內沒有新的段落就把無聲音軌關掉
            if (current == null) { main.removeCallbacks(idleOff); main.postDelayed(idleOff, 5000); return; }
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
                p.setAudioSessionId(session);
                p.setAudioAttributes(attrs());
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
