package com.badman99.m3u8extractor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private EditText imdbInput;
    private Button extractBtn;
    private ProgressBar progress;
    private TextView logText;
    private TextView m3u8Link;
    private LinearLayout m3u8Box;
    private PlayerView playerView;
    private ExoPlayer player;
    private OkHttpClient client;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imdbInput = findViewById(R.id.imdbInput);
        extractBtn = findViewById(R.id.extractBtn);
        progress = findViewById(R.id.progress);
        logText = findViewById(R.id.logText);
        m3u8Link = findViewById(R.id.m3u8Link);
        m3u8Box = findViewById(R.id.m3u8Box);
        playerView = findViewById(R.id.playerView);
        handler = new Handler(Looper.getMainLooper());
        client = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build();

        Uri data = getIntent().getData();
        if (data != null && data.getPath() != null) {
            String path = data.getPath();
            if (path.startsWith("/play/")) {
                imdbInput.setText(path.replace("/play/", ""));
            }
        }

        m3u8Link.setOnClickListener(v -> {
            String url = m3u8Link.getText().toString();
            if (!url.isEmpty()) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("m3u8", url));
                Toast.makeText(this, "M3U8 copied!", Toast.LENGTH_SHORT).show();
            }
        });

        extractBtn.setOnClickListener(v -> {
            String id = imdbInput.getText().toString().trim();
            if (id.isEmpty()) {
                Toast.makeText(this, "Enter IMDb ID", Toast.LENGTH_SHORT).show();
                return;
            }
            startExtraction(id);
        });
    }

    private void log(String msg, String type) {
        handler.post(() -> {
            String color;
            switch (type) {
                case "ok": color = "#3fb950"; break;
                case "err": color = "#f85149"; break;
                case "info": default: color = "#58a6ff"; break;
            }
            logText.append("<font color='" + color + "'>" + msg + "</font><br>");
        });
    }

    private void startExtraction(String imdbId) {
        extractBtn.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        m3u8Box.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        logText.setText("");
        releasePlayer();

        new Thread(() -> {
            try {
                log("Step 1: Fetching page source...", "info");
                String pageUrl = "https://gemma416okl.com/play/" + imdbId;
                String html = fetchGet(pageUrl, "https://gemma416okl.com/");

                if (html == null || html.contains("We are offline")) {
                    log("Page offline or unreachable", "err");
                    log("Trying CORS proxies...", "info");
                    html = fetchViaCorsProxy(pageUrl);
                }

                if (html == null) {
                    log("FAILED: Could not fetch page", "err");
                    finishExtraction();
                    return;
                }

                log("Got source: " + html.length() + " bytes", "ok");

                log("Step 2: Extracting player config...", "info");
                JSONObject config = extractConfig(html);

                if (config == null) {
                    log("No player config found", "err");
                    finishExtraction();
                    return;
                }

                log("Config keys: " + config.keys().toString(), "ok");
                String fileUrl = config.optString("file", "");
                String referrer = config.optString("referrer", "gemma416okl.com");
                int hls = config.optInt("hls", 0);
                log("File URL: " + fileUrl, "info");
                log("HLS: " + hls + " | Referrer: " + referrer, "info");

                String m3u8Url = null;

                if (!fileUrl.isEmpty()) {
                    log("Step 3: Fetching playlist...", "info");
                    String playlistData = fetchGet(fileUrl.replace("\\/", "/"), "https://" + referrer + "/");

                    if (playlistData != null) {
                        log("Playlist: " + playlistData.length() + " chars", "ok");

                        try {
                            JSONObject pj = new JSONObject(playlistData);
                            if (pj.has("file")) m3u8Url = pj.getString("file").replace("\\/", "/");
                            if (pj.has("hls_url")) m3u8Url = pj.getString("hls_url").replace("\\/", "/");
                            if (pj.has("stream")) m3u8Url = pj.getString("stream").replace("\\/", "/");
                        } catch (Exception e) {
                            if (playlistData.trim().startsWith("#EXTM3U")) {
                                m3u8Url = fileUrl.replace("\\/", "/");
                            } else if (playlistData.trim().startsWith("http")) {
                                m3u8Url = playlistData.trim();
                            } else {
                                log("Encoded playlist, trying CDN pattern...", "info");
                            }
                        }
                    }
                }

                if (m3u8Url == null) {
                    log("Step 4: Scanning for m3u8 pattern...", "info");
                    Pattern p = Pattern.compile("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*");
                    Matcher m = p.matcher(html);
                    if (m.find()) m3u8Url = m.group();
                }

                if (m3u8Url == null) {
                    log("M3U8 not found", "err");
                    finishExtraction();
                    return;
                }

                log("M3U8 FOUND: " + m3u8Url, "ok");

                String finalUrl = m3u8Url;
                handler.post(() -> {
                    m3u8Box.setVisibility(View.VISIBLE);
                    m3u8Link.setText(finalUrl);
                    log("Starting player...", "ok");
                    startPlayer(finalUrl);
                    finishExtraction();
                });

            } catch (Exception e) {
                log("Error: " + e.getMessage(), "err");
                finishExtraction();
            }
        }).start();
    }

    private String fetchGet(String url, String referer) {
        try {
            Request.Builder rb = new Request.Builder().url(url).get();
            rb.header("Referer", referer);
            rb.header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36");
            rb.header("Accept", "text/html,application/json,*/*");

            Response resp = client.newCall(rb.build()).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().string();
            }
            resp.close();
        } catch (Exception e) {
            log("GET error: " + e.getMessage(), "err");
        }
        return null;
    }

    private String fetchViaCorsProxy(String url) {
        String[] proxies = {
                "https://api.allorigins.win/raw?url=",
                "https://corsproxy.io/?"
        };
        for (String proxy : proxies) {
            try {
                log("Trying proxy: " + proxy.split("/")[2], "info");
                String resp = fetchGet(proxy + java.net.URLEncoder.encode(url, "UTF-8"), "");
                if (resp != null && !resp.contains("We are offline") && resp.contains("player")) {
                    return resp;
                }
            } catch (Exception e) {
                log("Proxy error: " + e.getMessage(), "err");
            }
        }
        return null;
    }

    private JSONObject extractConfig(String html) {
        Pattern[] patterns = {
                Pattern.compile("let\\s+p3\\s*=\\s*(\\{[^;]+\\});", Pattern.DOTALL),
                Pattern.compile("(\\{\"file\":\\s*\"[^\"]+\"[^}]*\\})"),
                Pattern.compile("o_params\\s*=\\s*(\\{[^;]+\\});", Pattern.DOTALL)
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String jsonStr = m.group(1).replaceAll("(?<!\\\\\\")\\'(?!\\\")", "\"");
                try {
                    return new JSONObject(jsonStr);
                } catch (Exception e) {
                    log("JSON parse error, cleaning...", "info");
                    try {
                        String cleaned = jsonStr.replaceAll("(\\w+)\\s*:", "\"$1\":")
                                .replaceAll(":\\s*'([^']*)'", ":\"$1\"")
                                .replaceAll(",\\s*}", "}");
                        return new JSONObject(cleaned);
                    } catch (Exception e2) {
                        log("Still can't parse", "err");
                    }
                }
            }
        }
        return null;
    }

    private void startPlayer(String m3u8Url) {
        playerView.setVisibility(View.VISIBLE);
        releasePlayer();

        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                .setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", "https://gemma416okl.com/"))
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
        player.playWhenReady = true;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void finishExtraction() {
        handler.post(() -> {
            extractBtn.setEnabled(true);
            progress.setVisibility(View.GONE);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }
}
