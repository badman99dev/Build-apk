package com.badman99.m3u8extractor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
    private Button extractBtn, copyBtn, playBtn;
    private ProgressBar progress;
    private TextView statusText, m3u8Link;
    private LinearLayout resultBox;
    private PlayerView playerView;
    private ExoPlayer player;
    private Handler handler;
    private WebView webView;
    private volatile boolean found;
    private String m3u8Url;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imdbInput = findViewById(R.id.imdbInput);
        extractBtn = findViewById(R.id.extractBtn);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        m3u8Link = findViewById(R.id.m3u8Link);
        resultBox = findViewById(R.id.resultBox);
        copyBtn = findViewById(R.id.copyBtn);
        playBtn = findViewById(R.id.playBtn);
        playerView = findViewById(R.id.playerView);
        handler = new Handler(Looper.getMainLooper());

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
            if (m3u8Url != null) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("m3u8", m3u8Url));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
        });

        playBtn.setOnClickListener(v -> {
            if (m3u8Url != null) startPlayer(m3u8Url);
        });
    }

    private void setStatus(String msg) {
        handler.post(() -> {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(msg);
        });
    }

    private void startExtraction(String imdbId) {
        extractBtn.setEnabled(false);
        extractBtn.setAlpha(0.5f);
        progress.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        releasePlayer();
        destroyWebView();
        found = false;
        m3u8Url = null;

        setStatus("Loading player page...");

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36");
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                setStatus("Page loaded, waiting for stream...");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!found && url.contains(".m3u8")) {
                    found = true;
                    m3u8Url = url;
                    handler.post(() -> onM3U8Found(url));
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.loadUrl("https://gemma416okl.com/play/" + imdbId);

        handler.postDelayed(() -> {
            if (!found) {
                setStatus("Timeout - stream not found");
                finishExtraction();
            }
        }, 60000);
    }

    private void onM3U8Found(String url) {
        progress.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        resultBox.setVisibility(View.VISIBLE);
        m3u8Link.setText(url);
        finishExtraction();
        destroyWebView();
        startPlayer(url);
    }

    private void startPlayer(String url) {
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
                .createMediaSource(MediaItem.fromUri(url));

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
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
    }

    private void finishExtraction() {
        handler.post(() -> {
            extractBtn.setEnabled(true);
            extractBtn.setAlpha(1f);
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
