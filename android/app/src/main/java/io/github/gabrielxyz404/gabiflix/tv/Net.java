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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Rede simples: pedidos em segundo plano e resposta na tela principal. */
final class Net {
    private Net() {}

    /**
     * Threads em prioridade de segundo plano (como o antigo AsyncTask): no Fire TV Stick (4 núcleos fracos) o JSON de 200 KB do catálogo e o
     * resto do trabalho de rede não podem disputar o processador com a thread da tela na mesma prioridade (ela desenha o foco do controle).
     */
    private static ThreadFactory background(final String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return r -> new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
            r.run();
        }, prefix + n.incrementAndGet());
    }

    /** catálogos, títulos, biblioteca e conta */
    static final ExecutorService IO = Executors.newFixedThreadPool(6, background("gf-io-"));
    /**
     * pedidos aos addons de fontes: ficam à parte porque um addon lento ou parado segura a thread até 15 s (o limite de leitura) e, na mesma
     * fila dos catálogos, 6 addons parados deixavam a tela do título e a fila de progresso sem nenhuma thread livre
     */
    static final ExecutorService ADDONS = Executors.newFixedThreadPool(8, background("gf-addon-"));
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, String> CACHE = new LruCache<>(80);

    interface Cb<T> { void done(T value, Exception error); }

    static <T> void async(Callable<T> work, Cb<T> cb) { run(IO, work, cb); }

    /** como {@link #async}, mas na fila dos addons de fontes (que podem demorar) */
    static <T> void asyncAddon(Callable<T> work, Cb<T> cb) { run(ADDONS, work, cb); }

    private static <T> void run(ExecutorService pool, Callable<T> work, Cb<T> cb) {
        pool.execute(() -> {
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
