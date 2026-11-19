package io.github.gabrielxyz404.gabiflix;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telemetria anônima de memória, SÓ NA TV: a cada 15 min de reprodução manda um relatório "MEMORIA" pelo mesmo canal do relatório de erros
 * ({@link CrashReporter}), no máximo 8 por sessão (processo). Não leva dado pessoal nem o título: só números de memória do app e do aparelho, o
 * tempo de reprodução e o tipo de decodificador. Serve para ver, no Fire TV de verdade, se a memória nativa do player cresce ao longo de um filme.
 */
final class MemTelemetry {
    /** de quanto em quanto tempo de REPRODUÇÃO (não conta pausa) sai um relatório */
    static final long INTERVAL_MS = 15 * 60_000L;
    /** no máximo isto por sessão (a vida do processo) */
    static final int MAX_PER_SESSION = 8;
    private static final long TICK_MS = 20_000L;

    /** quanto da tela do player a telemetria precisa saber */
    interface Host {
        boolean playing();
        /** "hardware", "software" ou "?" */
        String decoder();
        /** TextureView ou SurfaceView, para o registro */
        String surface();
    }

    private static final AtomicInteger sent = new AtomicInteger();
    /** a primeira leitura da sessão (para dizer quanto cresceu desde então) */
    private static volatile long firstPssKb = -1, firstNativeKb = -1, firstAtPlayMs = -1;

    private final Context app;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long playMs, lastTick;
    private long nextAtMs = INTERVAL_MS;
    private boolean stopped;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            long now = SystemClock.elapsedRealtime();
            try { if (lastTick > 0 && host.playing()) playMs += Math.min(now - lastTick, 2 * TICK_MS); } catch (Throwable ignored) {}
            lastTick = now;
            if (playMs >= nextAtMs) {
                nextAtMs += INTERVAL_MS;
                report();
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private MemTelemetry(Context c, Host h) { app = c.getApplicationContext(); host = h; }

    /** liga a telemetria (só na TV; fora dela devolve null). Chamar quando o filme já está tocando. */
    static MemTelemetry startIfTv(Context c, boolean tv, Host h) {
        if (!tv) return null;
        MemTelemetry t = new MemTelemetry(c, h);
        t.lastTick = SystemClock.elapsedRealtime();
        t.ui.postDelayed(t.tick, TICK_MS);
        return t;
    }

    void stop() { stopped = true; ui.removeCallbacksAndMessages(null); }

    private void report() {
        if (sent.get() >= MAX_PER_SESSION) return;
        final int n = sent.incrementAndGet();
        final long minutes = playMs / 60_000L;
        final String dec = safe(host::decoder), surf = safe(host::surface);
        Thread th = new Thread(() -> {
            try { CrashReporter.memoria(body(n, minutes, dec, surf)); } catch (Throwable ignored) {}
        }, "gf-mem-telemetry");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    private static String safe(java.util.concurrent.Callable<String> c) { try { return c.call(); } catch (Throwable t) { return "?"; } }

    private String body(int n, long playMinutes, String decoder, String surface) {
        StringBuilder b = new StringBuilder();
        b.append("Relatório ").append(n).append('/').append(MAX_PER_SESSION).append(" da sessão, a cada ").append(INTERVAL_MS / 60_000L).append(" min de reprodução\n");
        b.append("reprodução: ").append(playMinutes).append(" min | processo ligado há ").append(uptimeMin()).append(" min | decodificador: ").append(decoder).append(" | vídeo: ").append(surface).append('\n');
        long pssKb = 0, nativeKb = 0;
        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            Map<String, String> st = mi.getMemoryStats();
            pssKb = mi.getTotalPss();
            nativeKb = kb(st, "summary.native-heap");
            b.append(String.format(Locale.ROOT, "PSS total %d MB | nativeHeap %d MB | Graphics %d MB | Code %d MB | Java %d MB | Stack %d MB | Outros %d MB | Sistema %d MB\n",
                    pssKb >> 10, nativeKb >> 10, kb(st, "summary.graphics") >> 10, kb(st, "summary.code") >> 10, kb(st, "summary.java-heap") >> 10,
                    kb(st, "summary.stack") >> 10, kb(st, "summary.private-other") >> 10, kb(st, "summary.system") >> 10));
        } catch (Throwable t) { b.append("(Debug.MemoryInfo: ").append(t).append(")\n"); }
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append(String.format(Locale.ROOT, "availMem %d MB | lowMemory=%s | limiar %d MB | RAM total %d MB\n", mi.availMem >> 20, mi.lowMemory, mi.threshold >> 20, mi.totalMem >> 20));
        } catch (Throwable t) { b.append("(ActivityManager: ").append(t).append(")\n"); }
        try {
            Runtime r = Runtime.getRuntime();
            b.append(String.format(Locale.ROOT, "heap Java usado %d MB de %d MB | nativo alocado (malloc) %d MB de %d MB\n", (r.totalMemory() - r.freeMemory()) >> 20, r.maxMemory() >> 20,
                    Debug.getNativeHeapAllocatedSize() >> 20, Debug.getNativeHeapSize() >> 20));
        } catch (Throwable ignored) {}
        if (firstPssKb < 0) { firstPssKb = pssKb; firstNativeKb = nativeKb; firstAtPlayMs = playMinutes; }
        else if (playMinutes > firstAtPlayMs) {
            double per = (double) (playMinutes - firstAtPlayMs);
            b.append(String.format(Locale.ROOT, "desde o 1º relatório (%d min de reprodução): PSS %+d MB (%+.1f MB/min), nativeHeap %+d MB (%+.1f MB/min)\n", firstAtPlayMs,
                    (pssKb - firstPssKb) >> 10, (pssKb - firstPssKb) / 1024.0 / per, (nativeKb - firstNativeKb) >> 10, (nativeKb - firstNativeKb) / 1024.0 / per));
        }
        b.append("onTrimMemory recebidos nesta sessão: ").append(App.trimSummary()).append('\n');
        return b.toString();
    }

    private static long kb(Map<String, String> st, String key) {
        try { String v = st.get(key); return v == null ? 0 : Long.parseLong(v.trim()); } catch (Throwable t) { return 0; }
    }

    private static long uptimeMin() {
        try { return (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 60_000L; } catch (Throwable t) { return -1; }
    }
}
