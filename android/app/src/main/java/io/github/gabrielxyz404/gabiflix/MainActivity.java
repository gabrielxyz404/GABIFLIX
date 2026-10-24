package io.github.gabrielxyz404.gabiflix;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * Tela principal: abre o GabiFlix (site) dentro do app.
 * Quando o GabiFlix pede para abrir um vídeo no VLC, o vídeo abre no player embutido (PlayerActivity).
 */
public class MainActivity extends Activity {

    static final String APP_URL = "https://gabrielxyz404.github.io/GABIFLIX/";
    static final String APP_HOST = "gabrielxyz404.github.io";

    private WebView web;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // o site reconhece que está dentro do app e liga o VLC como player
        s.setUserAgentString(s.getUserAgentString() + " GabiFlixApp/" + BuildConfigHelper.versionName(this));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
        });

        // o site chama window.GabiFlixNative.play(link) para tocar no player embutido (VLC)
        web.addJavascriptInterface(new NativeBridge(), "GabiFlixNative");

        web.setWebChromeClient(new WebChromeClient() {
            // vídeo em tela cheia no player do próprio site
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                hideSystemBars(true);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                root.removeView(fullscreenView);
                fullscreenView = null;
                web.setVisibility(View.VISIBLE);
                hideSystemBars(false);
                if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
                fullscreenCallback = null;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
    }

    /** Decide o que fazer com cada link aberto pelo site. true = o app cuidou do link. */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        // 1) vídeo para o VLC -> player embutido
        String media = VlcLinks.mediaUrl(url);
        if (media != null) {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URL, media);
            startActivity(i);
            return true;
        }

        // 2) páginas do próprio GabiFlix continuam dentro do app
        if ((scheme.equals("http") || scheme.equals("https")) && APP_HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }

        // 3) qualquer outro link (YouTube, sites, outros apps) abre fora
        try {
            Intent i;
            if (scheme.equals("intent")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) {
            // sem app para abrir: ignora
        }
        return true;
    }

    private void hideSystemBars(boolean hide) {
        View d = getWindow().getDecorView();
        if (hide) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
            return;
        }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    /** Ponte chamada pelo site: abre o vídeo no player embutido. */
    private final class NativeBridge {
        @JavascriptInterface
        public void play(String link) {
            if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) return;
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, link);
                startActivity(i);
            });
        }

        /** toca com legendas: subsJson = ["https://...srt", ...] (já só em português) */
        @JavascriptInterface
        public void playWithSubs(String link, String subsJson) {
            if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) return;
            ArrayList<String> list = new ArrayList<>();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(subsJson == null ? "[]" : subsJson);
                for (int k = 0; k < arr.length() && k < 8; k++) {
                    String u = arr.optString(k, "");
                    if (u.startsWith("http://") || u.startsWith("https://")) list.add(u);
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, link);
                i.putExtra(PlayerActivity.EXTRA_SUBS, list.toArray(new String[0]));
                startActivity(i);
            });
        }

        /** toca com legendas; type/id deixam o player buscar legendas sozinho se a lista vier vazia */
        @JavascriptInterface
        public void playFull(String link, String subsJson, String type, String id) {
            if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) return;
            ArrayList<String> list = new ArrayList<>();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(subsJson == null ? "[]" : subsJson);
                for (int k = 0; k < arr.length() && k < 8; k++) {
                    String u = arr.optString(k, "");
                    if (u.startsWith("http://") || u.startsWith("https://")) list.add(u);
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, link);
                i.putExtra(PlayerActivity.EXTRA_SUBS, list.toArray(new String[0]));
                i.putExtra(PlayerActivity.EXTRA_TYPE, type);
                i.putExtra(PlayerActivity.EXTRA_ID, id);
                startActivity(i);
            });
        }

        @JavascriptInterface
        public int version() { return 3; }
    }
}
