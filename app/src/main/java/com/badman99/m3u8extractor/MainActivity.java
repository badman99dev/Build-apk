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
    private LinearLayout resultBox, selectorBox, seasonRow, episodeRow, langRow;
    private Spinner seasonSpinner, episodeSpinner, langSpinner;
    private PlayerView playerView;
    private ExoPlayer player;
    private OkHttpClient client;
    private Handler handler;
    private String csrfKey;

    private ArrayList<SeasonInfo> seasons = new ArrayList<>();
    private ArrayList<EpisodeInfo> currentEpisodes = new ArrayList<>();
    private ArrayList<StreamInfo> currentLanguages = new ArrayList<>();
    private String currentM3u8;

    private static final String PLAYER_BASE = "https://gemma416okl.com/play/";
    private static final String FILE_PATH = "https://keymi417exx.com/playlist/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://gemma416okl.com/";

    static class SeasonInfo {
        String title;
        String id;
        JSONArray folder;
        SeasonInfo(String t, String i, JSONArray f) { title = t; id = i; folder = f; }
        @Override public String toString() { return title; }
    }

    static class EpisodeInfo {
        String title;
        String id;
        JSONArray folder;
        EpisodeInfo(String t, String i, JSONArray f) { title = t; id = i; folder = f; }
        @Override public String toString() { return title; }
    }

    static class StreamInfo {
        String title;
        String file;
        String m3u8;
        StreamInfo(String t, String f) { title = t; file = f; m3u8 = null; }
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
        selectorBox = findViewById(R.id.selectorBox);
        seasonRow = findViewById(R.id.seasonRow);
        episodeRow = findViewById(R.id.episodeRow);
        langRow = findViewById(R.id.langRow);
        seasonSpinner = findViewById(R.id.seasonSpinner);
        episodeSpinner = findViewById(R.id.episodeSpinner);
        langSpinner = findViewById(R.id.langSpinner);
        playerView = findViewById(R.id.playerView);
        handler = new Handler(Looper.getMainLooper());

        client = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build();

        Uri data = getIntent().getData();
        if (data != null && data.getPath() != null && data.getPath().startsWith("/play/")) {
            imdbInput.setText(data.getPath().replace("/play/", ""));
        }

        extractBtn.setOnClickListener(v -> {
            String id = imdbInput.getText().toString().trim();
            if (id.isEmpty()) { Toast.makeText(this, "Enter IMDb ID", Toast.LENGTH_SHORT).show(); return; }
            startExtraction(id);
        });

        copyBtn.setOnClickListener(v -> {
            if (currentM3u8 != null) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("m3u8", currentM3u8));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
        });

        playBtn.setOnClickListener(v -> { if (currentM3u8 != null) startPlayer(currentM3u8); });

        seasonSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < seasons.size()) loadEpisodes(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        episodeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < currentEpisodes.size()) loadLanguages(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < currentLanguages.size()) selectLanguage(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
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
        selectorBox.setVisibility(View.GONE);
        resultBox.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        logText.setText("");
        releasePlayer();
        seasons.clear();
        currentEpisodes.clear();
        currentLanguages.clear();
        currentM3u8 = null;
        csrfKey = null;

        new Thread(() -> {
            try {
                String url = PLAYER_BASE + imdbId;
                log("━━━ Step 1: GET " + url, "info");
                String html = httpGet(url, REFERER);
                if (html == null) { log("FAILED: page fetch", "err"); done(); return; }
                log("Response: " + html.length() + " bytes", "ok");

                Matcher m = Pattern.compile("let p3 = (\\{[^;]+\\});").matcher(html);
                if (!m.find() && html.contains("HDVBPlayer")) {
                    m = Pattern.compile("new HDVBPlayer\\((\\{[^;]+\\})\\)").matcher(html);
                }
                if (!m.find()) { log("No config found", "err"); done(); return; }

                JSONObject p3 = new JSONObject(m.group(1).replace("\\/", "/"));
                String file = p3.optString("file", "");
                csrfKey = p3.optString("key", "");
                String userIp = p3.optString("userIp", "");
                log("Config: key=" + csrfKey.substring(0, Math.min(15, csrfKey.length())) + "... ip=" + userIp, "ok");

                if (file.startsWith("/playlist/")) {
                    file = "https://keymi417exx.com" + file;
                }

                log("━━━ Step 2: POST playlist ━━━", "info");
                String resp = httpPost(file, REFERER, csrfKey);
                if (resp == null) { log("FAILED: playlist", "err"); done(); return; }
                log("Response: " + resp.length() + " chars", "ok");

                Object parsed = parseResponse(resp);
                if (parsed == null) { log("Parse failed", "err"); done(); return; }

                JSONArray arr = null;
                if (parsed instanceof JSONArray) arr = (JSONArray) parsed;
                else if (parsed instanceof String && ((String) parsed).contains(".m3u8")) {
                    currentM3u8 = ((String) parsed).trim();
                    handler.post(() -> showResult(currentM3u8));
                    done(); return;
                }

                if (arr != null) analyzeStructure(arr);

            } catch (Exception e) {
                log("Error: " + e.getMessage(), "err");
            }
            done();
        }).start();
    }

    private void done() {
        handler.post(() -> { extractBtn.setEnabled(true); extractBtn.setAlpha(1f); progress.setVisibility(View.GONE); });
    }

    private void analyzeStructure(JSONArray arr) throws Exception {
        JSONObject first = arr.getJSONObject(0);

        // Check if has seasons (folder with episode numbers)
        boolean hasSeasons = first.has("folder") && first.opt("episode") == null && !first.optString("file", "").startsWith("~");
        boolean hasEpisodes = first.has("episode") || (first.has("folder") && first.opt("file") == null);

        if (hasSeasons) {
            log("━━━ Series detected: " + arr.length() + " seasons ━━━", "ok");
            seasons.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                seasons.add(new SeasonInfo(s.optString("title", "Season " + (i+1)), s.optString("id", ""), s.optJSONArray("folder")));
            }
            handler.post(() -> {
                selectorBox.setVisibility(View.VISIBLE);
                seasonRow.setVisibility(View.VISIBLE);
                episodeRow.setVisibility(View.VISIBLE);
                langRow.setVisibility(View.VISIBLE);
                ArrayAdapter<SeasonInfo> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, seasons);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                seasonSpinner.setAdapter(adapter);
            });
            loadEpisodes(0);
        } else if (hasEpisodes) {
            log("━━━ Episodes detected: " + arr.length() + " episodes ━━━", "ok");
            seasons.clear();
            seasons.add(new SeasonInfo("Episodes", "", arr));
            handler.post(() -> {
                selectorBox.setVisibility(View.VISIBLE);
                seasonRow.setVisibility(View.GONE);
                episodeRow.setVisibility(View.VISIBLE);
                langRow.setVisibility(View.VISIBLE);
            });
            loadEpisodes(0);
        } else {
            log("━━━ Movie: " + arr.length() + " stream(s) ━━━", "ok");
            currentLanguages.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                currentLanguages.add(new StreamInfo(item.optString("title", "Stream " + (i+1)), item.optString("file", "")));
            }
            handler.post(() -> {
                selectorBox.setVisibility(View.VISIBLE);
                seasonRow.setVisibility(View.GONE);
                episodeRow.setVisibility(View.GONE);
                langRow.setVisibility(View.VISIBLE);
                ArrayAdapter<StreamInfo> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentLanguages);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                langSpinner.setAdapter(adapter);
            });
        }
    }

    private void loadEpisodes(int seasonPos) {
        if (seasonPos >= seasons.size()) return;
        JSONArray folder = seasons.get(seasonPos).folder;
        currentEpisodes.clear();
        if (folder == null) return;

        try {
            for (int i = 0; i < folder.length(); i++) {
                JSONObject ep = folder.getJSONObject(i);
                if (ep.has("episode") || ep.has("folder")) {
                    currentEpisodes.add(new EpisodeInfo(ep.optString("title", ep.optString("episode", "E" + (i+1))), ep.optString("id", ""), ep.optJSONArray("folder")));
                } else if (ep.has("file")) {
                    currentEpisodes.add(new EpisodeInfo(ep.optString("title", "E" + (i+1)), ep.optString("id", ""), null));
                }
            }
        } catch (Exception e) { log("Episode parse error: " + e.getMessage(), "err"); }

        handler.post(() -> {
            ArrayAdapter<EpisodeInfo> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentEpisodes);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            episodeSpinner.setAdapter(adapter);
        });
        if (!currentEpisodes.isEmpty()) loadLanguages(0);
    }

    private void loadLanguages(int epPos) {
        if (epPos >= currentEpisodes.size()) return;
        EpisodeInfo ep = currentEpisodes.get(epPos);
        currentLanguages.clear();

        try {
            if (ep.folder != null && ep.folder.length() > 0) {
                for (int i = 0; i < ep.folder.length(); i++) {
                    Object item = ep.folder.get(i);
                    if (item instanceof JSONObject) {
                        JSONObject lang = (JSONObject) item;
                        if (lang.has("file")) {
                            currentLanguages.add(new StreamInfo(lang.optString("title", "Lang " + (i+1)), lang.optString("file", "")));
                        } else if (lang.has("folder")) {
                            JSONArray sub = lang.getJSONArray("folder");
                            for (int j = 0; j < sub.length(); j++) {
                                JSONObject subItem = sub.getJSONObject(j);
                                if (subItem.has("file")) {
                                    currentLanguages.add(new StreamInfo(subItem.optString("title", lang.optString("title", "L" + (i+1))), subItem.optString("file", "")));
                                }
                            }
                        }
                    }
                }
            } else if (ep.folder == null) {
                // episode itself might be a direct stream (no folder)
            }
        } catch (Exception e) { log("Lang parse error: " + e.getMessage(), "err"); }

        if (currentLanguages.isEmpty()) {
            log("No languages found for this episode", "warn");
        }

        handler.post(() -> {
            ArrayAdapter<StreamInfo> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentLanguages);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            langSpinner.setAdapter(adapter);
        });
    }

    private void selectLanguage(int pos) {
        if (pos >= currentLanguages.size()) return;
        StreamInfo si = currentLanguages.get(pos);
        String file = si.file;
        if (file.isEmpty()) { log("Empty file for " + si.title, "err"); return; }

        resultBox.setVisibility(View.GONE);
        m3u8Link.setText("Loading...");
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String m3u8 = resolveFile(file, si.title);
                if (m3u8 != null) {
                    currentM3u8 = m3u8;
                    handler.post(() -> showResult(m3u8));
                } else {
                    log("No M3U8 for " + si.title, "err");
                }
            } catch (Exception e) {
                log("Resolve error: " + e.getMessage(), "err");
            }
            handler.post(() -> progress.setVisibility(View.GONE));
        }).start();
    }

    private String resolveFile(String file, String title) throws Exception {
        if (file.startsWith("~")) {
            String subUrl = FILE_PATH + file.substring(1) + ".txt";
            log("[" + title + "] POST " + subUrl.substring(0, Math.min(50, subUrl.length())) + "...", "info");
            String resp = httpPost(subUrl, REFERER, csrfKey);
            if (resp == null) return null;
            Object parsed = parseResponse(resp);
            if (parsed instanceof String) return ((String) parsed).trim();
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof JSONObject) {
                        JSONObject obj = (JSONObject) item;
                        String f = obj.optString("file", "");
                        if (!f.isEmpty()) {
                            String result = resolveFile(f, obj.optString("title", title));
                            if (result != null) return result;
                        }
                    } else if (item instanceof String) {
                        return ((String) item).trim();
                    }
                }
            }
            return null;
        } else if (file.contains(".m3u8")) {
            log("[" + title + "] 🎯 M3U8: " + file.substring(0, Math.min(60, file.length())), "ok");
            return file;
        } else if (file.startsWith("http") && file.contains(".txt")) {
            String resp = httpPost(file, REFERER, csrfKey);
            if (resp == null) return null;
            Object parsed = parseResponse(resp);
            if (parsed instanceof String) return ((String) parsed).trim();
            return null;
        }
        return null;
    }

    private void showResult(String m3u8) {
        resultBox.setVisibility(View.VISIBLE);
        m3u8Link.setText(m3u8);
        startPlayer(m3u8);
    }

    private Object parseResponse(String resp) throws Exception {
        resp = resp.trim();
        if (resp.startsWith("[")) return new JSONArray(resp);
        if (resp.startsWith("{")) return new JSONObject(resp);
        if (resp.startsWith("#1")) {
            log("Decrypting #1...", "warn");
            String d = decryptSaltD(decryptPepper(resp.substring(2), -1)).trim();
            if (d.startsWith("[")) return new JSONArray(d);
            if (d.startsWith("{")) return new JSONObject(d);
            return d;
        }
        if (resp.startsWith("#0")) {
            log("Decrypting #0...", "warn");
            String d = decryptSaltD(resp.substring(2)).trim();
            if (d.startsWith("[")) return new JSONArray(d);
            if (d.startsWith("{")) return new JSONObject(d);
            return d;
        }
        if (resp.startsWith("http") && resp.contains(".m3u8")) {
            log("Direct M3U8 URL!", "ok");
            return resp;
        }
        if (resp.startsWith("http")) return resp;
        if (resp.startsWith("#EXTM3U")) return resp;
        log("Unknown: " + resp.substring(0, Math.min(40, resp.length())), "warn");
        return null;
    }

    private String httpGet(String url, String referer) {
        try {
            Request req = new Request.Builder().url(url).get()
                    .header("User-Agent", UA).header("Referer", referer)
                    .header("Accept", "text/html,application/json,*/*").build();
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) { String b = resp.body().string(); resp.close(); return b; }
            resp.close();
        } catch (Exception e) { log("GET error: " + e.getMessage(), "err"); }
        return null;
    }

    private String httpPost(String url, String referer, String csrf) {
        try {
            RequestBody body = RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded"));
            Request req = new Request.Builder().url(url).post(body)
                    .header("User-Agent", UA).header("Referer", referer)
                    .header("Content-type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf).build();
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) { String b = resp.body().string(); resp.close(); return b; }
            resp.close();
        } catch (Exception e) { log("POST error: " + e.getMessage(), "err"); }
        return null;
    }

    // PlayerJS decryption
    private static final String ABC = buildAbc();
    private static final String KEY_STR = ABC + "0123456789+/=";
    private static String buildAbc() {
        int[] c = {65,66,67,68,69,70,71,72,73,74,75,76,77,97,98,99,100,101,102,103,104,105,106,107,108,109,78,79,80,81,82,83,84,85,86,87,88,89,90,110,111,112,113,114,115,116,117,118,119,120,121,122};
        StringBuilder sb = new StringBuilder(); for (int v : c) sb.append((char) v); return sb.toString();
    }
    private String decryptPepper(String t, int eVal) {
        t = t.replace("+", "#").replace("#", "+");
        int shift = 1 * eVal; if (eVal < 0) shift += ABC.length() / 2;
        String s = ABC.substring(2 * shift) + ABC.substring(0, 2 * shift);
        StringBuilder r = new StringBuilder();
        for (char c : t.toCharArray()) { int idx = ABC.indexOf(c); r.append(idx >= 0 ? s.charAt(idx) : c); }
        return r.toString();
    }
    private String decryptSaltD(String t) {
        t = t.replaceAll("[^A-Za-z0-9+/=]", "");
        StringBuilder r = new StringBuilder(); int l = 0;
        while (l < t.length()) {
            int i = KEY_STR.indexOf(t.charAt(l++)); int s2 = KEY_STR.indexOf(t.charAt(l++));
            int n = l < t.length() ? KEY_STR.indexOf(t.charAt(l++)) : 64;
            int a = l < t.length() ? KEY_STR.indexOf(t.charAt(l++)) : 64;
            int e = ((15 & s2) << 4) | (n >> 2); int o = ((3 & n) << 6) | a;
            r.append((char) ((i << 2) | (s2 >> 4)));
            if (n != 64) r.append((char) e); if (a != 64) r.append((char) o);
        }
        return saltUd(r.toString());
    }
    private String saltUd(String t) {
        StringBuilder e = new StringBuilder(); int o = 0;
        while (o < t.length()) {
            int i = t.charAt(o);
            if (i < 128) { e.append((char) i); o++; }
            else if (i > 191 && i < 224) { int s = t.charAt(o+1); e.append((char) (((31&i)<<6)|(63&s))); o+=2; }
            else { int s = t.charAt(o+1); int c3 = t.charAt(o+2); e.append((char) (((15&i)<<12)|((63&s)<<6)|(63&c3))); o+=3; }
        }
        return e.toString();
    }

    private void startPlayer(String m3u8Url) {
        playerView.setVisibility(View.VISIBLE);
        releasePlayer();
        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setDefaultRequestProperties(Collections.singletonMap("Referer", REFERER))
                .setConnectTimeoutMs(15000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true);
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

    private void releasePlayer() { if (player != null) { player.release(); player = null; } }

    @Override
    protected void onDestroy() { super.onDestroy(); releasePlayer(); }
}
