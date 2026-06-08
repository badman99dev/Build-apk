package com.badman99.m3u8extractor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private EditText imdbInput;
    private Button extractBtn, copyBtn, playBtn;
    private ProgressBar progress;
    private TextView logText, m3u8Link;
    private LinearLayout resultBox;
    private Spinner langSpinner;
    private PlayerView playerView;
    private ExoPlayer player;
    private OkHttpClient client;
    private Handler handler;

    private ArrayList<StreamInfo> streams = new ArrayList<>();
    private String currentM3u8;
    private volatile boolean cancelled;

    private static final String PLAYER_BASE = "https://gemma416okl.com/play/";
    private static final String FILE_PATH = "https://keymi417exx.com/playlist/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://gemma416okl.com/";

    static class StreamInfo {
        String title;
        String m3u8;
        StreamInfo(String t, String u) { title = t; m3u8 = u; }
        @Override public String toString() { return title; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imdbInput = findViewById(R.id.imdbInput);
        extractBtn = findViewById(R.id.extractBtn);
        progress = findViewById(R.id.progress);
        logText = findViewById(R.id.logText);
        m3u8Link = findViewById(R.id.m3u8Link);
        resultBox = findViewById(R.id.resultBox);
        copyBtn = findViewById(R.id.copyBtn);
        playBtn = findViewById(R.id.playBtn);
        langSpinner = findViewById(R.id.langSpinner);
        playerView = findViewById(R.id.playerView);
        handler = new Handler(Looper.getMainLooper());

        client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Uri data = getIntent().getData();
        if (data != null && data.getPath() != null) {
            String path = data.getPath();
            if (path.startsWith("/play/")) {
                imdbInput.setText(path.replace("/play/", ""));
            }
        }

        extractBtn.setOnClickListener(v -> {
            String id = imdbInput.getText().toString().trim();
            if (id.isEmpty()) {
                Toast.makeText(this, "Enter IMDb ID", Toast.LENGTH_SHORT).show();
                return;
            }
            startExtraction(id);
        });

        copyBtn.setOnClickListener(v -> {
            if (currentM3u8 != null) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("m3u8", currentM3u8));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
        });

        playBtn.setOnClickListener(v -> {
            if (currentM3u8 != null) startPlayer(currentM3u8);
        });

        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < streams.size()) {
                    currentM3u8 = streams.get(pos).m3u8;
                    m3u8Link.setText(currentM3u8);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void log(String msg) {
        handler.post(() -> logText.append(msg + "\n"));
    }

    private void log(String msg, String type) {
        handler.post(() -> {
            String color;
            switch (type) {
                case "ok": color = "#3fb950"; break;
                case "err": color = "#f85149"; break;
                case "warn": color = "#eab308"; break;
                default: color = "#58a6ff"; break;
            }
            logText.append("<font color='" + color + "'>" + msg + "</font><br>");
        });
    }

    private void startExtraction(String imdbId) {
        extractBtn.setEnabled(false);
        extractBtn.setAlpha(0.5f);
        progress.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);
        langSpinner.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        logText.setText("");
        releasePlayer();
        streams.clear();
        cancelled = false;

        new Thread(() -> {
            try {
                step1_fetchPage(imdbId);
            } catch (Exception e) {
                log("Error: " + e.getMessage(), "err");
            }
            handler.post(() -> {
                extractBtn.setEnabled(true);
                extractBtn.setAlpha(1f);
                progress.setVisibility(View.GONE);
            });
        }).start();
    }

    private void step1_fetchPage(String imdbId) {
        String url = PLAYER_BASE + imdbId;
        log("━━━ Step 1: Fetch player page ━━━", "info");
        log("GET " + url);

        String html = httpGet(url, REFERER);
        if (html == null) {
            log("FAILED: Could not fetch page", "err");
            return;
        }

        log("Response: " + html.length() + " bytes", "ok");

        if (html.contains("We are offline")) {
            log("Page says offline - domain may have changed", "err");
            return;
        }

        log("━━━ Step 2: Extract player config ━━━", "info");
        Matcher m = Pattern.compile("let p3 = (\\{[^;]+\\});").matcher(html);
        if (!m.find()) {
            log("No p3 config found in HTML", "err");
            return;
        }

        try {
            String jsonStr = m.group(1).replace("\\/", "/");
            JSONObject p3 = new JSONObject(jsonStr);

            String file = p3.optString("file", "");
            String key = p3.optString("key", "");
            String userIp = p3.optString("userIp", "");
            String referrer = p3.optString("referrer", "gemma416okl.com");
            String movie = p3.optString("movie", "");

            log("Config parsed", "ok");
            log("  Key: " + key.substring(0, Math.min(20, key.length())) + "...");
            log("  UserIP: " + userIp);
            log("  Movie: " + movie);
            log("  File: " + file.substring(0, Math.min(60, file.length())) + "...");

            step2_fetchPlaylist(file, key);

        } catch (Exception e) {
            log("JSON parse error: " + e.getMessage(), "err");
        }
    }

    private void step2_fetchPlaylist(String fileUrl, String csrfKey) {
        log("━━━ Step 3: Fetch main playlist ━━━", "info");
        log("POST " + fileUrl.substring(0, Math.min(70, fileUrl.length())) + "...");
        log("X-CSRF-TOKEN: " + csrfKey.substring(0, Math.min(20, csrfKey.length())) + "...");

        String resp = httpPost(fileUrl, REFERER, csrfKey);
        if (resp == null) {
            log("FAILED: Playlist POST error", "err");
            return;
        }

        log("Response: " + resp.length() + " chars", "ok");

        try {
            Object parsed = parsePlaylistResponse(resp);
            if (parsed == null) {
                log("Could not parse playlist", "err");
                return;
            }
            step3_resolveItems(parsed, csrfKey, 0);
        } catch (Exception e) {
            log("Parse error: " + e.getMessage(), "err");
        }
    }

    private Object parsePlaylistResponse(String resp) throws Exception {
        resp = resp.trim();
        if (resp.startsWith("[")) {
            return new JSONArray(resp);
        } else if (resp.startsWith("{")) {
            return new JSONObject(resp);
        } else if (resp.startsWith("#1")) {
            log("Encrypted (#1), decrypting...", "warn");
            String decrypted = decryptSaltD(decryptPepper(resp.substring(2), -1)).trim();
            if (decrypted.startsWith("[")) return new JSONArray(decrypted);
            if (decrypted.startsWith("{")) return new JSONObject(decrypted);
            if (decrypted.contains(".m3u8")) return decrypted;
            log("Decrypted: " + decrypted.substring(0, Math.min(100, decrypted.length())));
            return null;
        } else if (resp.startsWith("#0")) {
            log("Encrypted (#0), decrypting...", "warn");
            String decrypted = decryptSaltD(resp.substring(2)).trim();
            if (decrypted.startsWith("[")) return new JSONArray(decrypted);
            if (decrypted.startsWith("{")) return new JSONObject(decrypted);
            if (decrypted.contains(".m3u8")) return decrypted;
            return null;
        } else if (resp.startsWith("#EXTM3U")) {
            log("Direct M3U8 response!", "ok");
            return resp;
        } else if (resp.startsWith("http") && resp.contains(".m3u8")) {
            log("Direct M3U8 URL from sub-playlist!", "ok");
            return resp;
        } else if (resp.startsWith("http")) {
            log("Got URL: " + resp.substring(0, Math.min(80, resp.length())), "info");
            return resp;
        } else {
            log("Unknown format: " + resp.substring(0, Math.min(50, resp.length())), "warn");
            return null;
        }
    }

    private void step3_resolveItems(Object data, String csrfKey, int depth) throws Exception {
        if (depth > 5 || cancelled) return;

        if (data instanceof JSONArray) {
            JSONArray arr = (JSONArray) data;
            for (int i = 0; i < arr.length(); i++) {
                if (cancelled) return;
                Object item = arr.get(i);
                if (item instanceof JSONObject) {
                    resolveItem((JSONObject) item, csrfKey, depth);
                } else if (item instanceof String) {
                    handleFileString((String) item, "", csrfKey, depth);
                }
            }
        } else if (data instanceof JSONObject) {
            resolveItem((JSONObject) data, csrfKey, depth);
        } else if (data instanceof String) {
            handleFileString((String) data, "", csrfKey, depth);
        }
    }

    private void handleFileString(String file, String title, String csrfKey, int depth) throws Exception {
        if (file.contains(".m3u8")) {
            log("  🎯 [" + (title.isEmpty() ? "Stream" : title) + "] M3U8 found!", "ok");
            log("    " + file.substring(0, Math.min(80, file.length())) + "...", "ok");
            streams.add(new StreamInfo(title.isEmpty() ? "Stream" : title, file));
            handler.post(this::showResults);
        } else if (file.startsWith("~")) {
            String subUrl = FILE_PATH + file.substring(1) + ".txt";
            log("  [" + title + "] Sub-playlist → POST " + subUrl.substring(0, Math.min(60, subUrl.length())) + "...");
            String subResp = httpPost(subUrl, REFERER, csrfKey);
            if (subResp != null) {
                Object subParsed = parsePlaylistResponse(subResp);
                step3_resolveItems(subParsed, csrfKey, depth + 1);
            }
        } else if (file.startsWith("http") && file.contains(".txt")) {
            log("  [" + title + "] Sub-playlist → " + file.substring(0, Math.min(60, file.length())) + "...");
            String subResp = httpPost(file, REFERER, csrfKey);
            if (subResp != null) {
                Object subParsed = parsePlaylistResponse(subResp);
                step3_resolveItems(subParsed, csrfKey, depth + 1);
            }
        } else if (file.startsWith("http")) {
            log("  [" + title + "] URL: " + file.substring(0, Math.min(60, file.length())), "warn");
        }
    }

    private void resolveItem(JSONObject item, String csrfKey, int depth) throws Exception {
        String title = item.optString("title", "?");
        String file = item.optString("file", "");

        if (!file.isEmpty()) {
            handleFileString(file, title, csrfKey, depth);
        }

        if (item.has("folder")) {
            JSONArray folder = item.getJSONArray("folder");
            for (int i = 0; i < folder.length(); i++) {
                resolveItem(folder.getJSONObject(i), csrfKey, depth + 1);
            }
        }
    }

    private String httpGet(String url, String referer) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/json,*/*")
                    .build();
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                resp.close();
                return body;
            }
            log("  HTTP " + resp.code(), "warn");
            resp.close();
        } catch (Exception e) {
            log("  GET error: " + e.getMessage(), "err");
        }
        return null;
    }

    private String httpPost(String url, String referer, String csrfKey) {
        try {
            RequestBody body = RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded"));
            Request req = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Content-type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrfKey)
                    .build();
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                String r = resp.body().string();
                resp.close();
                return r;
            }
            log("  POST HTTP " + resp.code(), "warn");
            resp.close();
        } catch (Exception e) {
            log("  POST error: " + e.getMessage(), "err");
        }
        return null;
    }

    // PlayerJS decryption - custom base64 with caesar shift
    private static final String ABC = buildAbc();
    private static final String KEY_STR = ABC + "0123456789+/=";

    private static String buildAbc() {
        int[] codes = {65,66,67,68,69,70,71,72,73,74,75,76,77,
                97,98,99,100,101,102,103,104,105,106,107,108,109,
                78,79,80,81,82,83,84,85,86,87,88,89,90,
                110,111,112,113,114,115,116,117,118,119,120,121,122};
        StringBuilder sb = new StringBuilder();
        for (int c : codes) sb.append((char) c);
        return sb.toString();
    }

    private String decryptPepper(String t, int eVal) {
        t = t.replace("+", "#").replace("#", "+");
        int shift = 1 * eVal;
        if (eVal < 0) shift += ABC.length() / 2;
        String s = ABC.substring(2 * shift) + ABC.substring(0, 2 * shift);
        StringBuilder result = new StringBuilder();
        for (char c : t.toCharArray()) {
            int idx = ABC.indexOf(c);
            if (idx >= 0) {
                result.append(s.charAt(idx));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String decryptSaltD(String t) {
        t = t.replaceAll("[^A-Za-z0-9+/=]", "");
        StringBuilder r = new StringBuilder();
        int l = 0;
        while (l < t.length()) {
            int i = KEY_STR.indexOf(t.charAt(l++));
            int s = KEY_STR.indexOf(t.charAt(l++));
            int n = l < t.length() ? KEY_STR.indexOf(t.charAt(l++)) : 64;
            int a = l < t.length() ? KEY_STR.indexOf(t.charAt(l++)) : 64;
            int e = ((15 & s) << 4) | (n >> 2);
            int o = ((3 & n) << 6) | a;
            r.append((char) ((i << 2) | (s >> 4)));
            if (n != 64) r.append((char) e);
            if (a != 64) r.append((char) o);
        }
        return saltUd(r.toString());
    }

    private String saltUd(String t) {
        StringBuilder e = new StringBuilder();
        int o = 0;
        while (o < t.length()) {
            int i = t.charAt(o);
            if (i < 128) { e.append((char) i); o++; }
            else if (i > 191 && i < 224) {
                int s = t.charAt(o + 1);
                e.append((char) (((31 & i) << 6) | (63 & s)));
                o += 2;
            } else {
                int s = t.charAt(o + 1);
                int c3 = t.charAt(o + 2);
                e.append((char) (((15 & i) << 12) | ((63 & s) << 6) | (63 & c3)));
                o += 3;
            }
        }
        return e.toString();
    }

    private void showResults() {
        handler.post(() -> {
            progress.setVisibility(View.GONE);
            if (streams.isEmpty()) {
                log("No streams found", "err");
                extractBtn.setEnabled(true);
                extractBtn.setAlpha(1f);
                return;
            }

            log("━━━ Results ━━━", "ok");
            log(streams.size() + " stream(s) found", "ok");

            resultBox.setVisibility(View.VISIBLE);

            if (streams.size() > 1) {
                ArrayAdapter<StreamInfo> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, streams);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                langSpinner.setAdapter(adapter);
                langSpinner.setVisibility(View.VISIBLE);
            } else {
                langSpinner.setVisibility(View.GONE);
            }

            currentM3u8 = streams.get(0).m3u8;
            m3u8Link.setText(currentM3u8);

            // auto play first
            startPlayer(currentM3u8);

            extractBtn.setEnabled(true);
            extractBtn.setAlpha(1f);
        });
    }

    private void startPlayer(String m3u8Url) {
        playerView.setVisibility(View.VISIBLE);
        releasePlayer();

        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setDefaultRequestProperties(Collections.singletonMap("Referer", REFERER))
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        HlsMediaSource hlsSource = new HlsMediaSource.Factory(dsFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(m3u8Url));

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new com.google.android.exoplayer2.source.DefaultMediaSourceFactory(dsFactory))
                .build();

        playerView.setPlayer(player);
        player.setMediaSource(hlsSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }
}
