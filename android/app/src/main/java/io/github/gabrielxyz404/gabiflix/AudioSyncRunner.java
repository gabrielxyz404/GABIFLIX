package io.github.gabrielxyz404.gabiflix;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestra o acerto da legenda pelo áudio: escolhe as janelas, captura uma de cada vez em segundo plano
 * (só depois da reprodução estar estável e sem competir com o buffer do player principal), faz o VAD e alinha.
 * Nunca toca na interface: fala com a tela só pelo Host.
 */
final class AudioSyncRunner {
    interface Host {
        /** o player principal está carregando/pausado/ocupado: espera */
        boolean mainBusy();
        /** a reprodução principal já tocou o bastante (>= 10 s) */
        boolean playbackStable();
        void progress(String s);
        void done(Outcome o);
    }

    static final class Outcome {
        AudioAlign.Result result;     // null se nem chegou a alinhar
        String note = "";             // texto curto para o diagnóstico
        boolean skipped;              // não rodou (acima do limite de bytes no modo automático)
        long estBytes;
        int windows;
        long tookMs;
        String mode = "";
        long readBytes;
    }

    static final long WINDOW_MS = 15000;
    static final int BASE_WINDOWS = 6, MAX_WINDOWS = 9;
    /** limite do modo automático (estimativa de bytes lidos da rede) */
    static final long AUTO_LIMIT_BYTES = 400L * 1024 * 1024;
    /** sobra por janela: cabeçalho do contêiner/índice + leitura adiantada do cache */
    static final long OVERHEAD_PER_WINDOW = 3L * 1024 * 1024;

    static long estimateBytes(long fileBytes, long durationMs, int windows, long windowMs) {
        if (fileBytes <= 0 || durationMs <= 0) return -1;
        double bps = fileBytes / (durationMs / 1000.0);
        return (long) (bps * windows * windowMs / 1000.0) + windows * OVERHEAD_PER_WINDOW;
    }

    private final Context ctx;
    private final String url;
    private final long durationMs;
    private long fileBytes;
    private final int audioTrackId;
    private final String subText;
    private final Host host;
    private final boolean verbose;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final File dir;
    private volatile Thread thread;
    /** só nos testes: ensaio com o início das janelas sem a correção de partida */
    String debugTag = "";

    AudioSyncRunner(Context ctx, String url, long durationMs, long fileBytes, int audioTrackId, String subText, Host host, boolean verbose) {
        this.ctx = ctx.getApplicationContext();
        this.url = url;
        this.durationMs = durationMs;
        this.fileBytes = fileBytes;
        this.audioTrackId = audioTrackId;
        this.subText = subText;
        this.host = host;
        this.verbose = verbose;
        this.dir = new File(ctx.getCacheDir(), "audiosync");
    }

    void cancel() {
        cancel.set(true);
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    boolean running() { Thread t = thread; return t != null && t.isAlive(); }

    void start(final boolean manual) {
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
            Outcome o = new Outcome();
            long t0 = System.currentTimeMillis();
            try {
                run(manual, o);
            } catch (InterruptedException ie) {
                o.note = "cancelado";
            } catch (Throwable e) {
                o.note = "erro " + e.getClass().getSimpleName();
                SubtitleSync.log("[áudio] erro inesperado: " + e);
            } finally {
                o.tookMs = System.currentTimeMillis() - t0;
                cleanup();
            }
            if (!cancel.get()) host.done(o);
        }, "audio-sync");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void cleanup() {
        try {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Throwable ignored) {}
    }

    private static void log(String s) { SubtitleSync.log("[áudio] " + s); }

    private void run(boolean manual, Outcome o) throws InterruptedException {
        if (fileBytes <= 0) {
            try { fileBytes = SubtitleSync.computeHashTimeboxed(url, 12000).size; } catch (Throwable ignored) {}
        }
        if (durationMs < 5 * 60000L) { o.note = "vídeo curto demais (" + durationMs / 1000 + " s)"; return; }
        AudioAlign.Spans sp = AudioAlign.spans(subText);
        if (sp.n() < 8) { o.note = "legenda com poucas falas"; return; }
        long est = estimateBytes(fileBytes, durationMs, BASE_WINDOWS, WINDOW_MS);
        o.estBytes = est;
        log("estimativa de leitura: " + (est < 0 ? "desconhecida" : (est / 1048576) + " MB") + " para " + BASE_WINDOWS + " janelas de " + WINDOW_MS / 1000 + " s"
                + (fileBytes > 0 ? "; arquivo " + (fileBytes / 1048576) + " MB, " + String.format(Locale.ROOT, "%.1f", fileBytes * 8.0 / (durationMs / 1000.0) / 1e6) + " Mbps" : ""));
        if (!manual && (est < 0 || est > AUTO_LIMIT_BYTES)) {
            o.skipped = true;
            o.note = est < 0 ? "tamanho do vídeo desconhecido: só pelo botão" : "vídeo pesado (~" + (est / 1048576) + " MB): só pelo botão";
            return;
        }
        // espera a reprodução principal ficar estável
        if (!manual) {
            long until = System.currentTimeMillis() + 180000;
            while (!host.playbackStable()) {
                if (cancel.get() || System.currentTimeMillis() > until) { o.note = "reprodução não estabilizou"; return; }
                Thread.sleep(1000);
            }
        }
        long[] starts = AudioAlign.planWindows(sp, durationMs, WINDOW_MS, BASE_WINDOWS);
        ArrayList<AudioAlign.Win> wins = new ArrayList<>(), winsRaw = new ArrayList<>();
        String mode = null;
        int done = 0;
        StringBuilder alog = new StringBuilder();
        AudioAlign.Result res = null;
        ArrayList<Long> taken = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                // sem pico claro: mais janelas (até MAX_WINDOWS) nos pontos intermediários, se a estimativa permitir
                int extra = MAX_WINDOWS - BASE_WINDOWS;
                long est2 = estimateBytes(fileBytes, durationMs, MAX_WINDOWS, WINDOW_MS);
                if (!manual && (est2 < 0 || est2 > AUTO_LIMIT_BYTES * 3 / 2)) break;
                long[] ex = AudioAlign.planWindows(sp, durationMs, WINDOW_MS, java.util.Arrays.copyOf(AudioAlign.EXTRA_FRACTIONS, extra), toArray(taken));
                starts = ex;
            }
            for (long st : starts) {
                taken.add(st);
                int total = round == 0 ? BASE_WINDOWS : MAX_WINDOWS;
                host.progress("A ouvir o áudio… " + (done + 1) + "/" + total);
                long waitUntil = System.currentTimeMillis() + 60000;
                while (host.mainBusy() && System.currentTimeMillis() < waitUntil) { if (cancel.get()) throw new InterruptedException(); Thread.sleep(500); }
                AudioCapture.Out out = null;
                String[] order = mode != null ? new String[]{mode} : AudioCapture.MODES;
                StringBuilder why = new StringBuilder();
                for (String md : order) {
                    if (cancel.get()) throw new InterruptedException();
                    out = AudioCapture.capture(ctx, url, st, WINDOW_MS, dir, "w" + done, md, audioTrackId, cancel, 90000, verbose);
                    if (out.ok()) { mode = md; break; }
                    why.append(md).append(": ").append(out.err).append("; ");
                    out = null;
                }
                done++;
                if (out == null) {
                    if (mode == null) { o.note = "captura de áudio indisponível (" + why + ")"; return; }
                    continue; // janela perdida; segue com as outras
                }
                o.mode = mode;
                if (out.readBytes > 0) o.readBytes += out.readBytes;
                double durS = WINDOW_MS / 1000.0;
                long adj = out.pcmSec > durS + 0.35 ? -(long) ((out.pcmSec - durS) * 1000) : 0;
                byte[] v = AudioAlign.vad(out.pcm.x, out.pcm.rate);
                float[] soft = AudioAlign.lastSoft;
                AudioAlign.Win w = new AudioAlign.Win(st + adj, v, soft);
                wins.add(w);
                winsRaw.add(new AudioAlign.Win(st, v, soft));
                log(String.format(Locale.ROOT, "janela %d: início %.1f s, áudio %.2f s (ajuste de partida %d ms), fala %.0f%%", done, st / 1000.0, out.pcmSec, adj, w.frac * 100));
            }
            o.windows = wins.size();
            alog.setLength(0);
            res = AudioAlign.align(wins, sp, alog);
            log(alog.toString().trim());
            if (!debugTag.isEmpty()) {
                StringBuilder l2 = new StringBuilder();
                AudioAlign.Result r2 = AudioAlign.align(winsRaw, sp, l2);
                log("SEM ajuste de partida: " + r2.summary() + (r2.accepted ? " offset=" + r2.offsetMs : ""));
            }
            if (res.accepted) break;
        }
        o.result = res;
        o.note = res == null ? "sem resultado" : res.summary();
    }

    private static long[] toArray(ArrayList<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
