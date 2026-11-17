package io.github.gabrielxyz404.gabiflix;

import android.content.Context;
import android.os.Debug;
import android.os.Process;

import io.github.gabrielxyz404.gabiflix.audiosync.AudioAligner;
import io.github.gabrielxyz404.gabiflix.audiosync.AudioSyncSession;
import io.github.gabrielxyz404.gabiflix.audiosync.Cue;
import io.github.gabrielxyz404.gabiflix.audiosync.DspVad;
import io.github.gabrielxyz404.gabiflix.audiosync.Recheck;
import io.github.gabrielxyz404.gabiflix.audiosync.Result;
import io.github.gabrielxyz404.gabiflix.audiosync.SileroVad;
import io.github.gabrielxyz404.gabiflix.audiosync.Vad;
import io.github.gabrielxyz404.gabiflix.audiosync.Window;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Motor de sincronização pelo áudio (a parte pesada, sem interface): captura 3 janelas de 90 s do filme numa segunda instância do libVLC,
 * passa o VAD (Silero; DSP de reserva se o ONNX Runtime não carregar) e resolve com o alinhador validado (z >= 9, porta de concordância de 150 ms
 * entre as janelas, banda morta de 1100 ms, só escalas de fps conhecidas, cortes só como aviso). Corre numa thread de prioridade baixa,
 * uma execução por vez, com memória limitada (16 kHz mono, uma janela de PCM por vez) e cancelável.
 */
final class AudioSyncCore {
    /** versão do motor + política: muda o que está guardado (cache) sobre cada legenda */
    static final String VERSION = "a1";
    static final long WIN_MS = 90_000;
    static final int N_WIN = 3;
    /** a execução automática pula filmes cuja leitura estimada das 3 janelas passa disto (o botão não pula) */
    static final long AUTO_MAX_BYTES = 600L * 1024 * 1024;
    private static final long MIN_DURATION_MS = 8 * 60_000L;
    private static final int MIN_SPEECH_CUES = 40;

    static final class Stats {
        String vad = "?";
        int windows;
        long captureWallMs, vadMs, vadCpuMs, solveMs, solveCpuMs, totalCpuMs, totalWallMs;
        long estBytes;
        long pssStartKb, pssEndKb, javaUsedStartKb, javaUsedEndKb;
        String summary() {
            return String.format(Locale.ROOT, "vad=%s janelas=%d captura=%d ms vad=%d ms (cpu %d) solve=%d ms (cpu %d) cpu_thread=%d ms total=%d ms leitura_estimada=%d MB pss=%d->%d MB java=%d->%d MB",
                    vad, windows, captureWallMs, vadMs, vadCpuMs, solveMs, solveCpuMs, totalCpuMs, totalWallMs, estBytes >> 20, pssStartKb >> 10, pssEndKb >> 10, javaUsedStartKb >> 10, javaUsedEndKb >> 10);
        }
    }

    static final class Outcome {
        static final int FIX = 0, OK = 1, REFUSED = 2, ERROR = 3, CANCELLED = 4, SKIPPED = 5;
        int kind = ERROR;
        String note = "";
        /** modelo achado: tempo corrigido = scale * tempoDaLegenda + offsetMs (offset sem a banda morta) */
        double scale = 1.0, offsetMs;
        /** atraso (ms) que a legenda deve ter AGORA para ficar certa (igual a offsetMs com escala 1) */
        int targetDelay;
        boolean cuts;
        Result result;
        Window[] windows;
        final Stats st = new Stats();
    }

    private final Context ctx;
    private final File dir;
    private volatile AudioCap cap;
    private Vad vad;
    private String vadName = "?";
    private volatile boolean busy;
    /** só nos testes: força o VAD por DSP (reserva) em vez do Silero */
    volatile boolean forceDsp;
    private final Object lock = new Object();

    AudioSyncCore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(this.ctx.getCacheDir(), "audiosync");
    }

    boolean busy() { return busy; }

    /** cancela o que estiver rodando (pode ser chamado de qualquer thread) */
    void cancel() {
        AudioCap c = cap;
        if (c != null) c.cancel();
    }

    /** cancela e libera o libVLC de captura e o ONNX Runtime sem prender quem chama (a thread da interface): a liberação espera a execução acabar */
    void close() {
        cancel();
        Thread t = new Thread(() -> {
            synchronized (lock) {
                AudioCap c = cap;
                cap = null;
                if (c != null) c.close();
                if (vad instanceof SileroVad) { try { ((SileroVad) vad).close(); } catch (Throwable ignored) {} }
                vad = null;
            }
        }, "gf-audiosync-close");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void log(String s) { SubtitleSync.log("AUDIOSYNC " + s); }

    private Vad vad() {
        if (vad != null) return vad;
        try {
            if (forceDsp) throw new IllegalStateException("forçado nos testes");
            byte[] model;
            try (InputStream in = ctx.getAssets().open("silero_vad.onnx")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(2_400_000);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                model = bo.toByteArray();
            }
            vad = new SileroVad(model);
            vadName = "silero";
        } catch (Throwable t) {
            // ONNX Runtime não carregou neste aparelho (biblioteca nativa/ABI/memória): usa o VAD por DSP (sem biblioteca nativa)
            log("Silero indisponível (" + t + "): usando o VAD por DSP");
            vad = DspVad.forName("dsp");
            vadName = vad.name();
        }
        return vad;
    }

    /** o tamanho do arquivo (bytes) pelo servidor, ou -1 */
    static long contentLength(String url) {
        try {
            if (!url.startsWith("http")) return -1;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "GabiFlix");
            try { return c.getResponseCode() < 400 ? c.getContentLengthLong() : -1; } finally { c.disconnect(); }
        } catch (Throwable t) { return -1; }
    }

    private static int countSpeech(Cue[] cues) {
        int n = 0;
        for (Cue c : cues) if (c.speech) n++;
        return n;
    }

    private Window captureWindow(String url, long startMs, long lenMs, Outcome o) throws Exception {
        AudioCap c;
        synchronized (this) { if (cap == null) cap = new AudioCap(ctx); c = cap; }
        File wav = new File(dir, "w" + startMs + ".wav");
        long t0 = System.nanoTime();
        AudioCap.Out co = c.capture(url, startMs, lenMs, wav, Math.min(240_000, lenMs * 3));
        o.st.captureWallMs += (System.nanoTime() - t0) / 1_000_000;
        try {
            if (!co.ok) { log("captura falhou em " + startMs / 1000 + " s: " + co.end); return null; }
            log(String.format(Locale.ROOT, "captura %d s +%d s: %.2f s de PCM em %.2f s (%s)", startMs / 1000, lenMs / 1000, co.pcmS, co.wallS, co.end));
            short[] pcm = AudioCap.readWav(wav);
            if (pcm.length < 16000L * (lenMs / 1000) / 3) return null;
            long c0 = Debug.threadCpuTimeNanos(), w0 = System.nanoTime();
            float[] p = vad().speechProb(pcm, pcm.length, 16000);
            o.st.vadCpuMs += (Debug.threadCpuTimeNanos() - c0) / 1_000_000;
            o.st.vadMs += (System.nanoTime() - w0) / 1_000_000;
            o.st.vad = vadName;
            return new Window(startMs, p);
        } finally {
            wav.delete();
        }
    }

    private static long usedJavaKb() { Runtime r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) >> 10; }

    private static long pssKb() { try { return Debug.getPss(); } catch (Throwable t) { return 0; } }

    private void begin(Outcome o) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        o.st.pssStartKb = pssKb();
        o.st.javaUsedStartKb = usedJavaKb();
        dir.mkdirs();
    }

    private void end(Outcome o, long cpu0, long wall0) {
        o.st.totalCpuMs = (Debug.threadCpuTimeNanos() - cpu0) / 1_000_000;
        o.st.totalWallMs = (System.nanoTime() - wall0) / 1_000_000;
        o.st.pssEndKb = pssKb();
        o.st.javaUsedEndKb = usedJavaKb();
    }

    /**
     * Sincronização completa (3 janelas de 90 s no plano por densidade de falas).
     * @param nowMs        posição do vídeo agora (só para o atraso de escalas diferentes de 1)
     * @param currentDelay atraso aplicado agora (a decisão de mexer compara com ele)
     * @param force        true = botão do usuário (não pula por tamanho de leitura)
     */
    Outcome run(String url, String subText, long durMs, long nowMs, int currentDelay, boolean force) {
        synchronized (lock) {
            busy = true;
            Outcome o = new Outcome();
            long cpu0 = Debug.threadCpuTimeNanos(), wall0 = System.nanoTime();
            try {
                if (cap != null) cap.reset();
                begin(o);
                Cue[] cues = subText == null ? new Cue[0] : Cue.parseSrt(subText);
                if (countSpeech(cues) < MIN_SPEECH_CUES) { o.kind = Outcome.REFUSED; o.note = "poucas falas na legenda"; return o; }
                if (durMs < MIN_DURATION_MS) { o.kind = Outcome.SKIPPED; o.note = "vídeo curto ou duração desconhecida"; return o; }
                long len = contentLength(url);
                if (len > 0) {
                    o.st.estBytes = (long) (len / (double) durMs * (WIN_MS * N_WIN));
                    if (!force && o.st.estBytes > AUTO_MAX_BYTES) { o.kind = Outcome.SKIPPED; o.note = "leitura estimada de " + (o.st.estBytes >> 20) + " MB: só pelo botão"; return o; }
                }
                AudioAligner.Params P = new AudioAligner.Params();
                long[][] plan = AudioSyncSession.planDense(cues, durMs, N_WIN, WIN_MS);
                AudioSyncSession ses = new AudioSyncSession(cues, durMs, P);
                Window[] ws = new Window[plan.length];
                for (int i = 0; i < plan.length; i++) {
                    if (cap != null && cap.isCancelled()) { o.kind = Outcome.CANCELLED; o.note = "cancelado"; return o; }
                    Window w = captureWindow(url, plan[i][0], plan[i][1], o);
                    if (w == null) {
                        o.kind = cap != null && cap.isCancelled() ? Outcome.CANCELLED : Outcome.ERROR;
                        o.note = "captura da janela " + (i + 1) + " falhou";
                        return o;
                    }
                    ws[i] = w;
                    ses.addWindow(w);
                    o.st.windows++;
                    if (i + 1 < plan.length) Thread.sleep(1000); // respiro entre as capturas: o player ao lado não disputa o tempo todo
                }
                o.windows = ws;
                long c0 = Debug.threadCpuTimeNanos(), w0 = System.nanoTime();
                Result r = ses.solve();
                o.st.solveCpuMs = (Debug.threadCpuTimeNanos() - c0) / 1_000_000;
                o.st.solveMs = (System.nanoTime() - w0) / 1_000_000;
                o.result = r;
                o.cuts = r.cutsWarning;
                fill(o, r, nowMs, currentDelay, P);
                return o;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                o.kind = Outcome.CANCELLED; o.note = "interrompido";
                return o;
            } catch (Throwable t) {
                o.kind = Outcome.ERROR; o.note = "erro: " + t;
                return o;
            } finally {
                end(o, cpu0, wall0);
                busy = false;
            }
        }
    }

    /** transforma o resultado do alinhador em decisão: mexer só se aceito e se o atraso certo difere do atual por mais que a banda morta */
    static void fill(Outcome o, Result r, long nowMs, int currentDelay, AudioAligner.Params P) {
        o.note = String.format(Locale.ROOT, "%s z=%.1f janelas=%d/%d%s", r.accepted ? "aceita" : "recusada", r.z, r.winsAgree, r.winsValid, r.accepted ? "" : " (" + r.reason.trim() + ")");
        if (!r.accepted) { o.kind = Outcome.REFUSED; return; }
        if (r.segments != null && r.segments.length > 1) { o.kind = Outcome.REFUSED; o.note += " por partes: não aplico"; return; }
        double a = r.scale, off = r.rawOffsetMs;
        o.scale = a;
        o.offsetMs = off;
        double cue = (nowMs - off) / a;                    // fala da legenda que aparece agora
        double target = (a - 1.0) * cue + off;             // atraso que a deixa no tempo certo agora
        target = Math.max(-600_000, Math.min(600_000, target));
        o.targetDelay = (int) (Math.round(target / 10.0) * 10);
        boolean scaled = Math.abs(a - 1.0) > 1e-9;
        o.kind = scaled || Math.abs(o.targetDelay - currentDelay) >= P.deadbandMs ? Outcome.FIX : Outcome.OK;
        o.note += String.format(Locale.ROOT, " escala=%.5f deslocamento=%.0f ms", a, off);
    }

    // ------------------------------------------------------------ reverificação

    static final class Check {
        Recheck.Out out;
        Window window;
        /** o modelo atual explica o áudio desta janela */
        boolean explained;
        int kind = Outcome.ERROR;
        String note = "";
        long tookMs, cpuMs;
        /** CHANGE confirmado por uma sessão completa (3 janelas em volta da posição) com as mesmas portas */
        Outcome confirm;
    }

    /**
     * Reverificação (monitor): captura 90 s a partir da posição atual e chama Recheck. Nunca muda nada sozinha; devolve o veredito.
     * @param model (scale, offsetMs) em uso;  failing = janelas recentes que o modelo não explicava (até 2)
     */
    Check recheck(String url, String subText, long durMs, long posMs, double scale, double offsetMs, Recheck.Out prev, List<Window> failing) {
        synchronized (lock) {
            busy = true;
            Check ck = new Check();
            Outcome tmp = new Outcome();
            long cpu0 = Debug.threadCpuTimeNanos(), wall0 = System.nanoTime();
            try {
                if (cap != null) cap.reset();
                begin(tmp);
                Cue[] cues = Cue.parseSrt(subText);
                if (countSpeech(cues) < MIN_SPEECH_CUES || durMs < MIN_DURATION_MS) { ck.kind = Outcome.SKIPPED; ck.note = "sem condições"; return ck; }
                long st = Math.max(0, Math.min(posMs, durMs - WIN_MS)) / 10 * 10;
                Window w = captureWindow(url, st, WIN_MS, tmp);
                if (w == null) { ck.kind = Outcome.ERROR; ck.note = "captura falhou"; return ck; }
                ck.window = w;
                Result cur = new Result();
                cur.accepted = true;
                cur.scale = scale;
                cur.offsetMs = offsetMs;
                Window[] wins = new Window[1 + failing.size()];
                wins[0] = w;
                for (int i = 0; i < failing.size(); i++) wins[1 + i] = failing.get(i);
                Recheck.Out ro = Recheck.check(cues, wins, cur, posMs, durMs, false, prev, new AudioAligner.Params(), new Recheck.Params());
                ck.out = ro;
                ck.explained = ro.verdict == Recheck.Verdict.KEEP && "ok".equals(ro.reason);
                ck.kind = Outcome.OK;
                ck.note = ro.verdict + " (" + ro.reason + ") ncc=" + String.format(Locale.ROOT, "%.2f", ro.nccAtCurrent) + " z=" + String.format(Locale.ROOT, "%.1f", ro.z) + " análise=" + ro.tookMs + " ms";
                return ck;
            } catch (Throwable t) {
                ck.kind = Outcome.ERROR; ck.note = "erro: " + t;
                return ck;
            } finally {
                end(tmp, cpu0, wall0);
                ck.tookMs = tmp.st.totalWallMs;
                ck.cpuMs = tmp.st.totalCpuMs;
                tmp.st.windows = ck.window == null ? 0 : 1;
                log("reverificação: " + tmp.st.summary());
                busy = false;
            }
        }
    }

    /**
     * Um CHANGE da reverificação só vale depois de uma sessão completa em volta da posição com as mesmas portas do alinhamento inicial:
     * a janela já capturada + 2 à frente. Devolve o resultado (kind FIX/OK/REFUSED...).
     */
    Outcome confirmChange(String url, String subText, long durMs, long posMs, double scale, double offsetMs, Window have) {
        synchronized (lock) {
            busy = true;
            Outcome o = new Outcome();
            long cpu0 = Debug.threadCpuTimeNanos(), wall0 = System.nanoTime();
            try {
                if (cap != null) cap.reset();
                begin(o);
                Cue[] cues = Cue.parseSrt(subText);
                AudioAligner.Params P = new AudioAligner.Params();
                AudioSyncSession ses = new AudioSyncSession(cues, durMs, P);
                ses.addWindow(have);
                o.st.windows = 1;
                long[] starts = {have.startMs + 150_000, have.startMs + 300_000};
                for (long s0 : starts) {
                    long s = Math.min(s0, durMs - WIN_MS) / 10 * 10;
                    if (s < have.startMs + 100_000) s = Math.max(0, have.startMs - (s0 - have.startMs)) / 10 * 10;
                    if (cap != null && cap.isCancelled()) { o.kind = Outcome.CANCELLED; return o; }
                    Window w = captureWindow(url, s, WIN_MS, o);
                    if (w == null) { o.kind = Outcome.ERROR; o.note = "captura falhou"; return o; }
                    ses.addWindow(w);
                    o.st.windows++;
                    Thread.sleep(1000);
                }
                Result r = ses.solve();
                o.result = r;
                // o modelo atual, em tempo de legenda, na posição: o atraso certo agora vem do novo deslocamento
                fill(o, r, posMs, (int) Math.round((scale - 1.0) * ((posMs - offsetMs) / scale) + offsetMs), P);
                return o;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                o.kind = Outcome.CANCELLED;
                return o;
            } catch (Throwable t) {
                o.kind = Outcome.ERROR; o.note = "erro: " + t;
                return o;
            } finally {
                end(o, cpu0, wall0);
                log("confirmação do reajuste: " + o.st.summary());
                busy = false;
            }
        }
    }
}
