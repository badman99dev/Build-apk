package com.badman99.m3u8extractor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

public class MainActivity extends AppCompatActivity {

    private EditText imdbInput;
    private Button extractBtn;
    private ProgressBar progress;
    private TextView logText;
    private TextView m3u8Link;
    private LinearLayout m3u8Box;
    private PlayerView playerView;
    private ExoPlayer player;
    private Handler handler;
    private WebView extractWebView;
    private volatile boolean m3u8Found;

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
        destroyWebView();
        m3u8Found = false;

        log("Loading player page in WebView...", "info");

        extractWebView = new WebView(this);
        WebSettings ws = extractWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36");

        extractWebView.addJavascriptInterface(new JsInterface(), "JSI");

        extractWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                log("Page loaded: " + url, "ok");
                startPolling(view);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!m3u8Found && url.contains(".m3u8")) {
                    m3u8Found = true;
                    String masterUrl = url;
                    if (!url.contains("index.m3u8") && url.contains("/")) {
                        int idx = url.lastIndexOf('/');
                        String base = url.substring(0, idx + 1);
                        if (!base.contains("index.m3u8")) {
                            masterUrl = url;
                        }
                    }
                    final String finalUrl = masterUrl;
                    handler.post(() -> {
                        log("M3U8 intercepted!", "ok");
                        showM3U8(finalUrl);
                    });
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                log("WebView error: " + description, "err");
            }
        });

        String pageUrl = "https://gemma416okl.com/play/" + imdbId;
        extractWebView.loadUrl(pageUrl);

        handler.postDelayed(() -> {
            if (!m3u8Found) {
                log("WebView timeout after 45s", "err");
                finishExtraction();
            }
        }, 45000);
    }

    private void startPolling(WebView view) {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (m3u8Found) return;
                view.evaluateJavascript(
                    "(function(){" +
                    "  try {" +
                    "    var vids = document.querySelectorAll('video');" +
                    "    for(var i=0;i<vids.length;i++){" +
                    "      if(vids[i].src && vids[i].src.indexOf('.m3u8') > -1){" +
                    "        window.JSI.onM3U8(vids[i].src);" +
                    "        return;" +
                    "      }" +
                    "    }" +
                    "  } catch(e){}" +
                    "})()", null);
                if (!m3u8Found) {
                    handler.postDelayed(this, 2000);
                }
            }
        };
        handler.postDelayed(poll, 3000);
    }

    private class JsInterface {
        @JavascriptInterface
        public void onM3U8(String url) {
            if (!m3u8Found && url != null && url.contains(".m3u8")) {
                m3u8Found = true;
                handler.post(() -> {
                    log("M3U8 from JS!", "ok");
                    showM3U8(url);
                });
            }
        }

        @JavascriptInterface
        public void onConfig(String json) {
            log("Config: " + json, "info");
        }

        @JavascriptInterface
        public void onError(String msg) {
            log("JS: " + msg, "err");
        }
    }

    private void showM3U8(String url) {
        m3u8Box.setVisibility(View.VISIBLE);
        m3u8Link.setText(url);
        log("URL: " + url, "ok");
        startPlayer(url);
        finishExtraction();
        destroyWebView();
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
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void destroyWebView() {
        if (extractWebView != null) {
            extractWebView.stopLoading();
            extractWebView.setWebViewClient(null);
            extractWebView.destroy();
            extractWebView = null;
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
        destroyWebView();
    }
}
