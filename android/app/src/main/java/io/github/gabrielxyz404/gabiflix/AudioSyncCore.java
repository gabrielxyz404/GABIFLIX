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
 * Motor de sincronização pelo áudio (a parte pesada, sem interface): captura 3 janelas de 90 s ESPALHADAS pelo filme (10%, 50%, 90%; até 5 se há suspeita de
 * deriva de escala, ver AudioSyncSession.analyze) numa segunda instância do libVLC,
 * passa o VAD (Silero; DSP de reserva se o ONNX Runtime não carregar) e resolve com o alinhador validado (z >= 9, porta de concordância de 150 ms
 * entre as janelas, banda morta de 1100 ms, só escalas de fps conhecidas, cortes só como aviso). Corre numa thread de prioridade baixa,
 * uma execução por vez, com memória limitada (16 kHz mono, uma janela de PCM por vez) e cancelável.
 */
final class AudioSyncCore {
    /** versão do motor + política: muda o que está guardado (cache) sobre cada legenda */
    static final String VERSION = "a3";
    static final long WIN_MS = 90_000;
    static final int N_WIN = 3;
    /** janelas espalhadas pelo filme na análise (sem limite de dados: a deriva só aparece com janelas em todo o filme); até maxDriftWindows com as extras */
    static final int DRIFT_WIN = 6;
    /** a leitura estimada das 3 janelas: o automático só roda se couber no orçamento da rede (ver AudioSyncAuto); o botão nunca pula */
    static final long NO_LIMIT = Long.MAX_VALUE;
    private static final long MIN_DURATION_MS = 8 * 60_000L;
    private static final int MIN_SPEECH_CUES = 40;

    static final class Stats {
        String vad = "?";
        int windows;
        long captureWallMs, vadMs, vadCpuMs, solveMs, solveCpuMs, totalCpuMs, totalWallMs;
        long estBytes, realBytes;
        long pssStartKb, pssEndKb, javaUsedStartKb, javaUsedEndKb;
        String summary() {
            return String.format(Locale.ROOT, "vad=%s janelas=%d captura=%d ms vad=%d ms (cpu %d) solve=%d ms (cpu %d) cpu_thread=%d ms total=%d ms leitura_estimada=%d MB leitura_real=%s pss=%d->%d MB java=%d->%d MB",
                    vad, windows, captureWallMs, vadMs, vadCpuMs, solveMs, solveCpuMs, totalCpuMs, totalWallMs, estBytes >> 20, realBytes > 0 ? (realBytes >> 20) + " MB" : "?", pssStartKb >> 10, pssEndKb >> 10, javaUsedStartKb >> 10, javaUsedEndKb >> 10);
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
        /** a escala (1,001 ou outra razão de fps) foi aceita pela prova de deriva das janelas */
        boolean drift;
        /** frase pronta, em português simples, para mostrar ao usuário quando nada foi mudado ("" = não há) */
        String msg = "";
        Result result;
        Window[] windows;
        final Stats st = new Stats();
    }

    private final Context ctx;
    private final File dir;
    private volatile AudioCap cap;
    /** capturas em paralelo (instâncias 1 e 2; a 0 é a `cap`) */
    private final AudioCap[] extraCaps = new AudioCap[2];
    private final Object vadLock = new Object();
    private volatile boolean peerCancel;
    private Vad vad;
    private String vadName = "?";
    private volatile boolean busy;
    /** só nos testes: força o VAD por DSP (reserva) em vez do Silero */
    volatile boolean forceDsp;
    /** faixa de áudio que está tocando no player (a segunda instância ouve a mesma); -1 = a padrão do arquivo */
    volatile int audioTrackId = -1;

    interface Progress { void on(String s); }
    /** chamado da thread de trabalho: diz em que ponto a análise está (o painel mostra) */
    volatile Progress progress;
    private void say(String s) { Progress p = progress; if (p != null) { try { p.on(s); } catch (Throwable ignored) {} } }
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
        for (AudioCap e : extraCaps) if (e != null) e.cancel();
    }

    private void resetCaps() {
        if (cap != null) cap.reset();
        for (AudioCap e : extraCaps) if (e != null) e.reset();
    }

    private AudioCap capAt(int i) {
        synchronized (this) {
            if (cap == null) cap = new AudioCap(ctx);
            if (i == 0) return cap;
            if (extraCaps[i - 1] == null) extraCaps[i - 1] = new AudioCap(ctx);
            return extraCaps[i - 1];
        }
    }

    /** cancela e libera o libVLC de captura e o ONNX Runtime sem prender quem chama (a thread da interface): a liberação espera a execução acabar */
    void close() {
        cancel();
        Thread t = new Thread(() -> {
            synchronized (lock) {
                AudioCap c = cap;
                cap = null;
                for (int i = 0; i < extraCaps.length; i++) extraCaps[i] = null;
                if (c != null) AudioCap.close();   // libera o libVLC compartilhado por todas as capturas
                vad = null;
            }
        }, "gf-audiosync-close");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void log(String s) { SubtitleSync.log("AUDIOSYNC " + s); }

    /**
     * O Silero (ONNX Runtime) roda no processo de captura (:audiocap), nunca neste: uma queda nativa ou falta de memória dele não fecha o player.
     * Se o serviço não responder, o VAD por DSP (Java puro, sem biblioteca nativa) faz o trabalho aqui.
     */
    private final class RemoteVad implements Vad {
        private Vad dsp;
        private volatile String last = "silero";
        @Override public String name() { return last; }
        @Override public float[] speechProb(short[] pcm, int n, int rate) {
            if (!forceDsp && AudioCap.sileroAllowed(ctx)) {
                File in = new File(dir, "vad-in.raw"), out = new File(dir, "vad-out.raw");
                try {
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    bb.asShortBuffer().put(pcm, 0, n);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(in)) { fo.write(bb.array()); }
                    if (capAt(0).vad(in, out)) {
                        byte[] raw = new byte[(int) out.length()];
                        try (java.io.FileInputStream fi = new java.io.FileInputStream(out)) { int o = 0, k; while (o < raw.length && (k = fi.read(raw, o, raw.length - o)) > 0) o += k; }
                        float[] p = new float[raw.length / 4];
                        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(p);
                        last = "silero";
                        return p;
                    }
                } catch (Throwable t) {
                    log("VAD remoto falhou (" + t + ")");
                } finally {
                    in.delete();
                    out.delete();
                }
                if (cap != null && cap.isCancelled()) return new float[n / 160];
            }
            if (dsp == null) dsp = DspVad.forName("dsp");
            last = dsp.name();
            log("Silero indisponível" + (forceDsp ? " (teste: DSP forçado)" : AudioCap.sileroAllowed(ctx) ? "" : " (" + AudioCap.sileroWhyNot(ctx) + ")") + ": usando o VAD por DSP");
            return dsp.speechProb(pcm, n, rate);
        }
    }

    private Vad vad() {
        if (vad == null) vad = new RemoteVad();
        return vad;
    }

    /**
     * O tamanho do arquivo (bytes) pelo servidor, ou -1. Muitos servidores de links diretos recusam o HEAD (403/405): então pede só o
     * primeiro byte (Range: bytes=0-0) e lê o total em Content-Range. Segue redirecionamentos (o endereço de download costuma redirecionar).
     */
    static long contentLength(String url) {
        if (!url.startsWith("http")) return -1;
        long n = probe(url, true);
        if (n <= 0) n = probe(url, false);
        return n;
    }

    private static long probe(String url, boolean head) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(head ? "HEAD" : "GET");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FramePlay");
            if (!head) c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code >= 400) return -1;
            String cr = c.getHeaderField("Content-Range");   // "bytes 0-0/123456"
            if (cr != null && cr.contains("/")) {
                try { return Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (NumberFormatException ignored) {}
            }
            return code == 200 ? c.getContentLengthLong() : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** leitura estimada (bytes) para `seconds` s de filme: o tamanho do arquivo repartido pela duração; -1 se não deu para saber */
    static long estimateBytes(String url, long durMs, long seconds) {
        long len = contentLength(url);
        return len > 0 && durMs > 0 ? (long) (len / (double) durMs * 1000.0 * seconds) : -1;
    }

    private static int countSpeech(Cue[] cues) {
        int n = 0;
        for (Cue c : cues) if (c.speech) n++;
        return n;
    }

    /** por que a última captura falhou (frase curta, para o registro e para o painel) */
    private volatile String lastCapFail = "";

    private Window captureWindow(String url, long startMs, long lenMs, Outcome o) throws Exception {
        return captureWindow(url, startMs, lenMs, o, capAt(0));
    }

    private Window captureWindow(String url, long startMs, long lenMs, Outcome o, AudioCap c) throws Exception {
        File wav = new File(dir, "w" + startMs + ".wav");
        AudioCap.Out co = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long t0 = System.nanoTime();
            co = c.capture(url, startMs, lenMs, wav, 420_000, audioTrackId);
            synchronized (o.st) {
                o.st.captureWallMs += (System.nanoTime() - t0) / 1_000_000;
                if (co.bytes > 0) o.st.realBytes += co.bytes;
            }
            if (co.ok || c.isCancelled()) break;
            if (co.end.startsWith("captura desligada")) { log("captura falhou em " + startMs / 1000 + " s: " + co.end + " (todas as configurações já foram tentadas)"); break; }
            log("captura falhou em " + startMs / 1000 + " s (tentativa " + attempt + "): " + co.end + " em " + String.format(Locale.ROOT, "%.1f", co.wallS) + " s; " + co.track);
            if (attempt == 1) Thread.sleep(2000);
        }
        try {
            if (!co.ok) { if (!peerCancel) lastCapFail = co.end; return null; }
            log(String.format(Locale.ROOT, "captura %d s +%d s: %.2f s de PCM em %.2f s (%s) leitura=%s | %s", startMs / 1000, lenMs / 1000, co.pcmS, co.wallS, co.end,
                    co.bytes > 0 ? (co.bytes >> 20) + " MB" : "?", co.track));
            short[] pcm = AudioCap.readWav(wav);
            if (pcm.length < 16000L * (lenMs / 1000) / 3) { lastCapFail = "o áudio veio curto (" + pcm.length / 16000 + " s de " + lenMs / 1000 + " s)"; log("captura curta: " + lastCapFail); return null; }
            long c0 = Debug.threadCpuTimeNanos(), w0 = System.nanoTime();
            float[] p;
            synchronized (vadLock) { p = vad().speechProb(pcm, pcm.length, 16000); }   // o VAD guarda estado: uma janela por vez
            synchronized (o.st) {
                o.st.vadCpuMs += (Debug.threadCpuTimeNanos() - c0) / 1_000_000;
                o.st.vadMs += (System.nanoTime() - w0) / 1_000_000;
                o.st.vad = vad.name();
            }
            return new Window(startMs, p);
        } finally {
            wav.delete();
        }
    }

    private Outcome captureFailed(Outcome o, int idx) {
        o.kind = cap != null && cap.isCancelled() ? Outcome.CANCELLED : Outcome.ERROR;
        o.note = "captura da janela " + idx + " falhou: " + lastCapFail;
        o.msg = "Não consegui ouvir o áudio do filme (" + lastCapFail + ").";
        return o;
    }

    private Outcome cancelledOut(Outcome o) { o.kind = Outcome.CANCELLED; o.note = "cancelado"; return o; }

    static String mb(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0).replace('.', ',') : (bytes >> 20) + " MB";
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
     * @param maxBytes     pula (SKIPPED) se a leitura estimada das 3 janelas passar disto; NO_LIMIT = botão do usuário (nunca pula)
     */
    Outcome run(String url, String subText, long durMs, long nowMs, int currentDelay, long maxBytes) {
        synchronized (lock) {
            busy = true;
            Outcome o = new Outcome();
            long cpu0 = Debug.threadCpuTimeNanos(), wall0 = System.nanoTime();
            try {
                resetCaps();
                begin(o);
                Cue[] cues = subText == null ? new Cue[0] : Cue.parseSrt(subText);
                if (countSpeech(cues) < MIN_SPEECH_CUES) { o.kind = Outcome.REFUSED; o.note = "poucas falas na legenda"; o.msg = "A legenda tem poucas falas para conferir pelo áudio."; return o; }
                if (durMs < MIN_DURATION_MS) { o.kind = Outcome.SKIPPED; o.note = "vídeo curto ou duração desconhecida"; o.msg = "O vídeo é curto (ou a duração ainda é desconhecida) para conferir pelo áudio."; return o; }
                final boolean unlimited = maxBytes == NO_LIMIT;
                Thread estThread = null;
                if (unlimited) {
                    // o botão não espera a sonda de tamanho: a primeira captura começa já (a estimativa só alimenta o registro e a mensagem)
                    estThread = new Thread(() -> o.st.estBytes = estimateBytes(url, durMs, WIN_MS * N_WIN / 1000), "gf-audiosync-est");
                    estThread.start();
                } else {
                    o.st.estBytes = estimateBytes(url, durMs, WIN_MS * N_WIN / 1000);
                    if (o.st.estBytes < 0) log("tamanho do arquivo desconhecido (o servidor não diz): sem estimativa de leitura");
                    if (o.st.estBytes > maxBytes) {
                        o.kind = Outcome.SKIPPED;
                        o.note = "leitura estimada de " + (o.st.estBytes >> 20) + " MB passa do limite de " + (maxBytes >> 20) + " MB: só pelo botão";
                        o.msg = "Ficheiro muito pesado para analisar sozinho (≈ " + mb(o.st.estBytes) + " a ler). Toca em Sincronizar automaticamente para analisar.";
                        return o;
                    }
                }
                say(o.st.estBytes > 0 ? "A ouvir o filme… (≈ " + mb(o.st.estBytes) + " a ler)" : "A ouvir o filme…");
                AudioAligner.Params P = new AudioAligner.Params();
                // 3 janelas espalhadas pelo filme (o que mostra a deriva da escala) e, se as janelas confiáveis sugerem deriva sem prova ainda, até 2 extras.
                // Capturas em fila, uma janela por vez: capturar ao mesmo tempo (testado no emulador) fez o corte do libVLC perder a exatidão
                // (janelas de 92-98 s em vez de 90 s) e a análise recusou uma legenda que a captura em fila aceita; por isso não se faz em paralelo
                final int[] failedAt = {0};
                final boolean[] first = {true};
                long cw0 = System.nanoTime();
                Window[][] used = new Window[1][];
                Result r = AudioSyncSession.analyze(cues, durMs, DRIFT_WIN, WIN_MS, (st, ln, i, total) -> {
                    if (cap != null && cap.isCancelled()) throw new InterruptedException();
                    if (!first[0]) Thread.sleep(300);
                    first[0] = false;
                    say(total > DRIFT_WIN && i >= DRIFT_WIN ? "A ouvir mais um trecho do filme (a legenda parece desviar)… " + (i + 1) + " de " + total : "A ouvir o filme… trecho " + (i + 1) + " de " + total);
                    Window w = captureWindow(url, st, ln, o);
                    if (w == null) { failedAt[0] = i + 1; return null; }
                    o.st.windows++;
                    return w;
                }, P, true, N_WIN, used);
                if (r == null) { return captureFailed(o, failedAt[0]); }
                o.st.captureWallMs = (System.nanoTime() - cw0) / 1_000_000;
                if (estThread != null) { estThread.join(1500); }
                o.windows = used[0];
                o.st.solveCpuMs = 0;
                o.st.solveMs = 0;
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
                AudioCap.close();   // solta o processo de captura logo depois da análise: não fica ocupando memória
            }
        }
    }

    /** transforma o resultado do alinhador em decisão: mexer só se aceito e se o atraso certo difere do atual por mais que a banda morta */
    static void fill(Outcome o, Result r, long nowMs, int currentDelay, AudioAligner.Params P) {
        o.note = String.format(Locale.ROOT, "%s z=%.1f janelas=%d/%d%s", r.accepted ? "aceita" : "recusada", r.z, r.winsAgree, r.winsValid, r.accepted ? "" : " (" + r.reason.trim() + ")");
        if (!r.accepted) { o.kind = Outcome.REFUSED; o.msg = "Sem confiança: o áudio não confirma esta legenda (pode ser de outra versão do filme)."; return; }
        if (r.segments != null && r.segments.length > 1) { o.kind = Outcome.REFUSED; o.note += " por partes: não aplico"; o.msg = "A legenda parece ter cortes em relação ao filme: não mexi. Usa o ajuste manual."; return; }
        double a = r.scale, off = r.rawOffsetMs;
        o.scale = a;
        o.offsetMs = off;
        o.drift = r.driftScale;
        double cue = (nowMs - off) / a;                    // fala da legenda que aparece agora
        double target = (a - 1.0) * cue + off;             // atraso que a deixa no tempo certo agora
        target = Math.max(-600_000, Math.min(600_000, target));
        o.targetDelay = (int) (Math.round(target / 10.0) * 10);
        boolean scaled = Math.abs(a - 1.0) > 1e-9;
        o.kind = scaled || Math.abs(o.targetDelay - currentDelay) >= P.deadbandMs ? Outcome.FIX : Outcome.OK;
        o.note += String.format(Locale.ROOT, " escala=%.5f deslocamento=%.0f ms", a, off) + (r.driftScale ? " (deriva provada pelas janelas)" : "");
    }

    /**
     * Corpo do relatório anônimo ANALISE (vai para o ntfy como o CrashReporter): só números do motor. Sem título, sem endereço, sem nome de arquivo.
     * @param durMs duração do conteúdo; o resto (decisão final, atrasos manuais) vem de quem decide, em extra
     */
    static String reportBody(Outcome o, long durMs, String extra) {
        StringBuilder b = new StringBuilder();
        Result r = o.result;
        b.append("duracao_s=").append(durMs / 1000).append(" janelas=").append(o.windows == null ? 0 : o.windows.length).append(" rodadas=").append(r == null ? 0 : r.rounds)
                .append(" vad=").append(o.st.vad).append(" motor=").append(VERSION).append('\n');
        if (r != null && r.wins != null) {
            for (int i = 0; i < r.wins.length; i++) {
                Result.Win w = r.wins[i];
                b.append(String.format(Locale.ROOT, "j%d inicio_s=%d centro_s=%d atraso_ms=%.0f ncc=%.2f segundo=%.2f z=%.1f confiavel=%d%n", i + 1, w.startMs / 1000, Math.round(w.centerMs / 1000), w.delayMs, w.ncc, w.second, w.z, w.confident ? 1 : 0));
            }
            b.append(String.format(Locale.ROOT, "ajuste escala=%.6f deslocamento_ms=%.0f inclinacao=%.6f residuo_ms=%.0f residuo_sem_escala_ms=%.0f deriva_janelas_ms=%.0f deriva_filme_ms=%.0f confiaveis=%d deriva_provada=%d%n",
                    r.scale, r.rawOffsetMs, r.driftSlope, r.driftResMs, r.unitResMs, r.driftMeasMs, r.driftContentMs, r.driftConfident, r.driftScale ? 1 : 0));
            b.append(String.format(Locale.ROOT, "motor aceita=%d inSync=%d z=%.1f margem=%.2f ncc=%.2f janelas_concordam=%d/%d desvio_max_ms=%.0f escala_conhecida=%d suspeita_deriva=%d cortes=%d%n",
                    r.accepted ? 1 : 0, r.inSync ? 1 : 0, r.z, r.margin, r.nccMean, r.winsAgree, r.winsValid, r.winMaxDev, r.scaleKnown ? 1 : 0, r.driftSuspect ? 1 : 0, r.cutsWarning ? 1 : 0));
            b.append("motivo=").append(r.reason == null ? "" : r.reason.trim()).append(" | ").append(r.driftNote).append('\n');
        }
        b.append("resultado=").append(o.kind == Outcome.FIX ? "corrigir" : o.kind == Outcome.OK ? "ja_certa" : o.kind == Outcome.REFUSED ? "sem_confianca" : o.kind == Outcome.ERROR ? "erro" : o.kind == Outcome.CANCELLED ? "cancelado" : "pulado")
                .append(" nota=").append(o.note).append('\n');
        b.append(String.format(Locale.ROOT, "tempos captura_ms=%d vad_ms=%d total_ms=%d leitura_mb=%d%n", o.st.captureWallMs, o.st.vadMs, o.st.totalWallMs, o.st.realBytes >> 20));
        if (extra != null && !extra.isEmpty()) b.append(extra);
        return b.toString();
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
    Check recheck(String url, String subText, long durMs, long posMs, double scale, double offsetMs, Recheck.Out prev, List<Window> failing, long maxBytes) {
        synchronized (lock) {
            busy = true;
            Check ck = new Check();
            Outcome tmp = new Outcome();
            long cpu0 = Debug.threadCpuTimeNanos(), wall0 = System.nanoTime();
            try {
                resetCaps();
                begin(tmp);
                Cue[] cues = Cue.parseSrt(subText);
                if (countSpeech(cues) < MIN_SPEECH_CUES || durMs < MIN_DURATION_MS) { ck.kind = Outcome.SKIPPED; ck.note = "sem condições"; return ck; }
                tmp.st.estBytes = estimateBytes(url, durMs, WIN_MS / 1000);
                if (maxBytes != NO_LIMIT && tmp.st.estBytes > maxBytes) {
                    ck.kind = Outcome.SKIPPED;
                    ck.note = "cada janela leria ≈ " + mb(tmp.st.estBytes) + ", acima do limite de " + mb(maxBytes) + ": não reverifico";
                    return ck;
                }
                long st = Math.max(0, Math.min(posMs, durMs - WIN_MS)) / 10 * 10;
                Window w = captureWindow(url, st, WIN_MS, tmp);
                if (w == null) { ck.kind = Outcome.ERROR; ck.note = "captura falhou: " + lastCapFail; return ck; }
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
                AudioCap.close();
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
                resetCaps();
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
                AudioCap.close();
            }
        }
    }
}
