package io.github.gabrielxyz404.gabiflix.tv;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rede simples: pedidos em segundo plano e resposta na tela principal. */
final class Net {
    private Net() {}

    static final ExecutorService IO = Executors.newFixedThreadPool(6);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, String> CACHE = new LruCache<>(80);

    interface Cb<T> { void done(T value, Exception error); }

    static <T> void async(Callable<T> work, Cb<T> cb) {
        IO.execute(() -> {
            try {
                T v = work.call();
                MAIN.post(() -> cb.done(v, null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(null, e));
            }
        });
    }

    static void clearCache() { CACHE.evictAll(); }

    /** GET com memória: o mesmo endereço não é baixado duas vezes enquanto o app está aberto */
    static String getCached(String url) throws IOException {
        String c = CACHE.get(url);
        if (c != null) return c;
        String s = get(url);
        CACHE.put(url, s);
        return s;
    }

    static String get(String url) throws IOException {
        return request(url, null, 15000);
    }

    static String post(String url, String json) throws IOException {
        return request(url, json, 20000);
    }

    private static String request(String url, String body, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GabiFlixTV");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) buf.write(b, 0, r);
            in.close();
            String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + s.substring(0, Math.min(200, s.length())));
            return s;
        } finally {
            c.disconnect();
        }
    }
}
