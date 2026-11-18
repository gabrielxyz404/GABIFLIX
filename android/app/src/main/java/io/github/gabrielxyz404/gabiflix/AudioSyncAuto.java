package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import io.github.gabrielxyz404.gabiflix.audiosync.Recheck;
import io.github.gabrielxyz404.gabiflix.audiosync.Window;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sincronização da legenda pelo áudio, totalmente automática: quando o vídeo está tocando e há uma legenda baixada ativa, ouve o filme
 * (3 janelas de 90 s numa segunda instância do libVLC, thread de prioridade baixa) e, se o alinhador tem certeza, acerta o atraso da legenda;
 * a cada ~10 min confere de novo (monitor). O mesmo motor atende o botão "Sincronizar automaticamente" do painel de legendas ({@link #chain}).
 *
 * Regras: o atraso manual do usuário sempre vence (atraso guardado que não foi posto por aqui, ou mexido depois, nunca é alterado); na dúvida não
 * mexe; nada disto toca o player (só setSpuDelay pelo mesmo caminho do ajuste manual).
 */
final class AudioSyncAuto {
    /** o que a tela do player empresta a este controlador (tudo chamado na thread da interface, salvo quando dito) */
    interface Host {
        /** tocando agora: não pausado e sem "carregando" */
        boolean playing();
        boolean buffering();
        long timeMs();
        long durationMs();
        String url();
        /** a legenda baixada ativa (null se for embutida, desativada ou ainda desconhecida) */
        SubtitleSync.Cand activeCand();
        /** chave do atraso guardado dessa legenda (a mesma do ajuste manual) */
        String delayKey();
        int delayMs();
        int savedDelay(String key);
        /** muda o atraso (guardando por legenda se persist) pelo mesmo caminho do ajuste manual */
        void applyDelay(int ms, boolean persist);
        void notice(String s);
        /** quadros de vídeo perdidos até agora (diagnóstico; -1 se não sabe) */
        long lostFrames();
        /** texto de diagnóstico (faixa ativa, faixas, legendas ligadas) */
        String debug();
        /** faixa de áudio que está tocando (-1 = não sabe): a segunda instância ouve a mesma */
        int audioTrackId();
        /** o estado da análise, em português simples, para a linha de estado do painel de legendas (null = nada a dizer); tom: 0 neutro, 1 bom, 2 aviso */
        void status(String text, int tone);
    }

    static final int TONE_NEUTRAL = 0, TONE_GOOD = 1, TONE_WARN = 2;

    private static final String PREFS = "audio-sync-1";
    private static final int NEW = 0, DONE = 2, OFF = 3;
    private static final long TICK_MS = 1000;

    private final Activity act;
    private final Host h;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioSyncCore core;
    private final ExecutorService ex;
    private final AtomicInteger token = new AtomicInteger();
    private boolean started, stopped;

    // configuração (os extras só existem nos testes)
    private final boolean autoOn;
    private long startAfterMs = 25_000, recheckMs = 600_000;
    /** o filme tem de estar tocando sem parar por este tempo antes de o automático abrir a captura (só nos testes, com gfAudioStartSec, vale 0) */
    private long needPlayMs = 30_000;
    private long playingSince;

    // estado da legenda ativa
    private String key;
    private long keySince;
    private int st = NEW;
    private boolean running, retryPending;
    private int retries;
    private long retryAt;
    private double mScale = 1.0, mOffset;
    private int lastWritten;
    private long nextRecheckAt, nextFollowAt;
    private Recheck.Out prevOut;
    private final ArrayList<Window> failing = new ArrayList<>();
    private int stallAborts;
    private long runStartedAt;
    private volatile long rescanAt;
    private volatile String keyNow;

    // saúde da reprodução (para medir que a análise não trava o filme)
    private long lastWall, lastT, hN, hStalls, hBuf, lostAtStart, lastStallAt;
    private double hMinRatio = 9;
    private boolean lastPl, lastBuf;

    AudioSyncAuto(Activity act, Host h) {
        this.act = act;
        this.h = h;
        this.core = new AudioSyncCore(act);
        this.ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "gf-audiosync");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // só nos testes (am start --es gfAudio...): automático desligado, tempos mais curtos, VAD por DSP
        android.content.Intent in = act.getIntent();
        autoOn = !"false".equals(extra(in, "gfAudioAuto"));
        String v = extra(in, "gfAudioStartSec");
        if (v != null) try { startAfterMs = Integer.parseInt(v) * 1000L; needPlayMs = 0; } catch (NumberFormatException ignored) {}
        v = extra(in, "gfAudioRecheckSec");
        if (v != null) try { recheckMs = Integer.parseInt(v) * 1000L; } catch (NumberFormatException ignored) {}
        if ("true".equals(extra(in, "gfAudioDsp"))) core.forceDsp = true;
        core.progress = s2 -> ui.post(() -> { if (!stopped) h.status(s2, TONE_NEUTRAL); });
    }

    private static String extra(android.content.Intent in, String k) {
        try {
            Object o = in == null || in.getExtras() == null ? null : in.getExtras().get(k);
            return o == null ? null : String.valueOf(o);
        } catch (Throwable t) { return null; }
    }

    private static void log(String s) { SubtitleSync.log("AUDIOSYNC " + s); }


    /** sem limite de dados: a sincronização automática roda sempre, qualquer que seja o tamanho do arquivo e o tipo de rede (decisão do usuário) */
    private long autoBudget() { return AudioSyncCore.NO_LIMIT; }

    private long recheckBudget() { return AudioSyncCore.NO_LIMIT; }

    private String noticedKey = "";

    /** um aviso discreto por situação e por legenda (não repete a cada tentativa) */
    private void noticeOnce(String k, String what, String text) {
        String id = k + "#" + what;
        if (id.equals(noticedKey)) return;
        noticedKey = id;
        h.notice(text);
    }

    private void status(String s, int tone) { h.status(s, tone); }

    void start() {
        if (started || stopped) return;
        started = true;
        log("pronto (sem limite de dados; automático=" + autoOn + ", começa " + startAfterMs / 1000 + " s depois de a legenda ficar ativa, reverifica a cada " + recheckMs / 1000 + " s)");
        ui.postDelayed(tick, TICK_MS);
    }

    /** a tela vai fechar: cancela tudo e solta o libVLC de captura e o ONNX Runtime */
    void stop() {
        stopped = true;
        token.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        core.close();
        ex.shutdown();
    }

    /** o app foi para segundo plano: não deixa captura nem análise rodando */
    void onPause() {
        if (running) log("pausa da tela: análise em andamento cancelada");
        token.incrementAndGet();
        core.cancel();
        running = false;
    }

    // ------------------------------------------------------------ botão (o painel de legendas)

    /**
     * Motor do botão: a referência/consenso de sempre primeiro; depois o áudio confere e, se tem certeza, corrige (ele manda no atraso).
     * Se o áudio recusa, vale o resultado de antes. Corre na thread própria do painel (fora da interface).
     */
    AutoSync.Engine chain(final AutoSync.Engine ref) {
        return (active, videoTimeMs, currentDelayMs) -> {
            AutoSync.Result r1;
            try { r1 = ref.sync(active, videoTimeMs, currentDelayMs); }
            catch (Throwable t) { r1 = AutoSync.Result.failed("erro: " + t); }
            if (stopped) return r1;
            final int d1 = r1.status == AutoSync.FIXED ? r1.delayMs : currentDelayMs;
            token.incrementAndGet();   // a execução automática em andamento (se houver) é descartada
            core.cancel();
            core.audioTrackId = h.audioTrackId();
            AudioSyncCore.Outcome o = core.run(h.url(), active.text, h.durationMs(), videoTimeMs, d1, AudioSyncCore.NO_LIMIT);
            log("botão: " + o.note + " | " + o.st.summary());
            String k = keyNow;
            switch (o.kind) {
                case AudioSyncCore.Outcome.FIX:
                    if (k != null) mark(k, "fix", o.scale, o.offsetMs, o.targetDelay); // o painel guarda este atraso como qualquer outro
                    rescanAt = SystemClock.uptimeMillis() + 6000;
                    return AutoSync.Result.fixed(o.targetDelay, "áudio " + o.note);
                case AudioSyncCore.Outcome.OK:
                    if (k != null) mark(k, "ok", 1.0, o.offsetMs, d1);
                    rescanAt = SystemClock.uptimeMillis() + 6000;
                    return r1.status == AutoSync.FIXED ? r1 : AutoSync.Result.ok("já estava certa (o áudio confirma)");
                case AudioSyncCore.Outcome.REFUSED:
                    if (k != null) mark(k, "no", 1.0, 0, 0);
                    return r1.status == AutoSync.FAILED ? AutoSync.Result.failedMsg(r1.note, o.msg) : r1;
                case AudioSyncCore.Outcome.CANCELLED:
                    return r1;
                default: // erro de captura, pulado: diz o motivo em vez de um "não consegui" genérico (se a referência já acertou, vale a referência)
                    return r1.status == AutoSync.FAILED ? AutoSync.Result.failedMsg(r1.note, o.msg.isEmpty() ? "Não consegui ouvir o áudio do filme." : o.msg) : r1;
            }
        };
    }

    // ------------------------------------------------------------ marcas guardadas (cache por legenda)

    private void mark(String k, String kind, double scale, double off, int delay) {
        try {
            SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            p.edit().putString(k, AudioSyncCore.VERSION + "|" + kind + "|" + scale + "|" + off + "|" + delay + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    private String[] readMark(String k) {
        try {
            String v = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(k, null);
            if (v == null) return null;
            String[] a = v.split("\\|");
            if (a.length != 6 || !a[0].equals(AudioSyncCore.VERSION)) return null;
            // "sem confiança" vale por 3 dias: depois tenta de novo (a causa pode ter sido passageira: rede, faixa errada, versão do app)
            if (a[1].equals("no") && System.currentTimeMillis() - Long.parseLong(a[5]) > 3L * 86_400_000L) return null;
            return a;
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ o ciclo (1 vez por segundo, na thread da interface)

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            try { onTick(); } catch (Throwable t) { log("erro no ciclo: " + t); }
            if (!stopped) ui.postDelayed(this, TICK_MS);
        }
    };

    private void resetHealth() {
        hN = hStalls = hBuf = 0;
        hMinRatio = 9;
        lostAtStart = h.lostFrames();
    }

    private String healthLine() {
        long lost = h.lostFrames();
        return String.format(Locale.ROOT, "saúde da reprodução: amostras=%d paradas=%d razão_mínima=%.2f carregando=%d quadros_perdidos=%s",
                hN, hStalls, hN == 0 ? 0.0 : hMinRatio, hBuf, lost < 0 || lostAtStart < 0 ? "?" : String.valueOf(lost - lostAtStart));
    }

    private void sampleHealth(long now, boolean pl) {
        long t = h.timeMs();
        boolean buf = h.buffering();
        if (buf && !lastBuf) hBuf++;
        if (lastWall > 0 && pl && lastPl && now - lastWall >= 700) {
            double ratio = (t - lastT) / (double) (now - lastWall);
            if (ratio >= 0 && ratio < 3) {
                hN++;
                if (ratio < hMinRatio) hMinRatio = ratio;
                if (ratio < 0.5) { hStalls++; lastStallAt = now; }
            }
        }
        lastWall = now; lastT = t; lastPl = pl; lastBuf = buf;
    }

    /** a reprodução engasgou duas vezes durante a análise: a rede não aguenta a captura junto com o filme; cancela e tenta uma vez mais tarde */
    private void abortForStall(long now) {
        log("a reprodução engasgou " + hBuf + " vezes durante a análise: cancelo para não atrapalhar o filme");
        token.incrementAndGet();
        core.cancel();
        running = false;
        stallAborts++;
        if (st == NEW) {
            if (stallAborts >= 2) {
                st = OFF;
                status("A rede não aguenta ouvir o filme sem engasgar a reprodução. Para acertar a legenda, pausa e toca em Sincronizar automaticamente.", TONE_WARN);
                noticeOnce(key == null ? "" : key, "fim", "Legenda: a rede está curta para analisar — usa Sincronizar no painel");
            } else {
                retryPending = true;
                retryAt = now + 180_000;
                status("A reprodução engasgou durante a análise: tento de novo daqui a 3 min.", TONE_WARN);
            }
        } else {
            nextRecheckAt = now + 1_800_000L;
        }
    }

    private void newSub(String k) {
        token.incrementAndGet();
        if (running) { core.cancel(); log("legenda trocada: análise em andamento cancelada"); }
        running = false;
        key = k;
        keyNow = k;
        keySince = SystemClock.uptimeMillis();
        st = NEW;
        retries = 0;
        retryPending = false;
        prevOut = null;
        failing.clear();
        mScale = 1.0;
        mOffset = 0;
        stallAborts = 0;
        status(null, TONE_NEUTRAL);
        if (k != null) log("legenda ativa: " + k.substring(Math.max(0, k.length() - 60)));
    }

    private long lastDiag;

    private void onTick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastDiag >= 20_000) { lastDiag = now; log("estado: st=" + st + " executando=" + running + " " + h.debug()); }
        boolean pl = h.playing();
        if (!pl) playingSince = 0; else if (playingSince == 0) playingSince = now;
        sampleHealth(now, pl);
        SubtitleSync.Cand cand = h.activeCand();
        String k = cand == null || cand.text == null ? null : h.delayKey();
        if ((k == null ? key != null : !k.equals(key))) newSub(k);
        if (k == null || !autoOn) return;
        if (running && hBuf >= 2 && now - runStartedAt > 5000) abortForStall(now);
        if (rescanAt > 0 && now >= rescanAt && !running) { rescanAt = 0; st = NEW; keySince = 0; retries = 0; }
        switch (st) {
            case NEW:
                if (running || !pl || now - keySince < startAfterMs || now - playingSince < needPlayMs || (retryPending && now < retryAt)) return;
                if (now - lastStallAt < 20_000 && lastStallAt > 0) return;
                decideStart(cand, k);
                break;
            case DONE:
                if (h.delayMs() != lastWritten) { st = OFF; log("atraso mexido à mão: não mexo mais nesta legenda"); return; }
                if (running || core.busy()) return;
                if (Math.abs(mScale - 1.0) > 1e-9 && now >= nextFollowAt && pl) follow(now);
                if (pl && now >= nextRecheckAt && now - lastStallAt > 20_000) startRecheck(cand, k);
                break;
            default:
                break;
        }
    }

    /** a legenda tem de ser uma que o motor ainda não viu; atraso manual guardado vence */
    private void decideStart(SubtitleSync.Cand cand, String k) {
        int saved = h.savedDelay(k);
        String[] m = readMark(k);
        if (m != null) {
            if (m[1].equals("no")) {
                st = OFF;
                log("esta legenda já foi vista pelo áudio e recusada: não tento de novo (por 3 dias)");
                status("Já conferida pelo áudio: sem confiança nesta legenda (pode ser de outra versão do filme). Se estiver fora, usa os botões.", TONE_WARN);
                return;
            }
            int markDelay = Integer.parseInt(m[4]);
            if (saved != markDelay) { st = OFF; log("atraso guardado (" + saved + " ms) não é o do áudio (" + markDelay + " ms): é manual, não mexo"); status("Há um ajuste manual guardado para esta legenda: não mexi.", TONE_NEUTRAL); return; }
            mScale = Double.parseDouble(m[2]);
            mOffset = Double.parseDouble(m[3]);
            if (m[1].equals("ok")) mOffset = h.delayMs();
            lastWritten = h.delayMs();
            st = DONE;
            nextRecheckAt = SystemClock.uptimeMillis() + recheckMs;
            nextFollowAt = 0;
            log("legenda já sincronizada pelo áudio antes (" + m[1] + "): sem nova captura, reverifica em " + recheckMs / 1000 + " s");
            status(m[1].equals("ok") ? "Legenda conferida pelo áudio: já estava certa." : "Legenda acertada pelo áudio antes (" + secs(markDelay) + ").", TONE_GOOD);
            return;
        }
        if (saved != 0 || h.delayMs() != 0) { st = OFF; log("atraso manual guardado (" + saved + " ms): não mexo"); status("Há um ajuste manual guardado para esta legenda (" + secs(saved != 0 ? saved : h.delayMs()) + "): não mexi.", TONE_NEUTRAL); return; }
        startRun(cand, k);
    }

    private void startRun(SubtitleSync.Cand cand, String k) {
        final int tok = token.incrementAndGet();
        running = true;
        final int d0 = h.delayMs();
        final long pos = h.timeMs(), dur = h.durationMs();
        final String url = h.url(), text = cand.text;
        log("antes da análise: " + healthLine());
        resetHealth();
        runStartedAt = SystemClock.uptimeMillis();
        final long budget = autoBudget();
        final int trackId = h.audioTrackId();
        log("começando: posição " + pos / 1000 + " s, duração " + dur / 1000 + " s, atraso atual " + d0 + " ms, faixa de áudio " + trackId + "");
        status("A verificar a legenda pelo áudio…", TONE_NEUTRAL);
        noticeOnce(k, "verificando", "Legenda: a verificar pelo áudio…");
        exec(() -> {
            core.audioTrackId = trackId;
            AudioSyncCore.Outcome o = core.run(url, text, dur, pos, d0, budget);
            ui.post(() -> finishRun(tok, k, d0, o));
        });
    }

    /** nada do que roda aqui pode derrubar o app: uma exceção vira registro + relatório, e a análise desta legenda para */
    private void fail(String what, Throwable t) {
        log("ERRO em " + what + ": " + t);
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            CrashReporter.event("ERRO_ANALISE", what + ": " + (trace.length() > 3000 ? trace.substring(0, 3000) : trace));
        } catch (Throwable ignored) {}
        running = false;
        st = OFF;
    }

    private void exec(Runnable r) {
        ex.execute(() -> { try { r.run(); } catch (Throwable t) { final Throwable e = t; ui.post(() -> fail("thread de análise", e)); } });
    }

    private void finishRun(int tok, String k, int d0, AudioSyncCore.Outcome o) {
        try { finishRun0(tok, k, d0, o); } catch (Throwable t) { fail("resultado", t); }
    }

    private void finishRun0(int tok, String k, int d0, AudioSyncCore.Outcome o) {
        if (stopped || tok != token.get()) { log("resultado descartado (outra execução/legenda/tela): " + o.note); return; }
        running = false;
        log("resultado: " + o.note + (o.cuts ? " [aviso: a legenda parece ter cortes]" : "") + " | " + o.st.summary());
        log(healthLine());
        if (!k.equals(key)) return;
        if (h.delayMs() != d0) { st = OFF; log("o usuário mexeu no atraso durante a análise: descartado"); return; }
        switch (o.kind) {
            case AudioSyncCore.Outcome.FIX: {
                boolean persist = o.scale == 1.0;
                h.applyDelay(o.targetDelay, persist);
                mScale = o.scale;
                mOffset = o.offsetMs;
                lastWritten = o.targetDelay;
                mark(k, "fix", o.scale, o.offsetMs, persist ? o.targetDelay : 0);
                st = DONE;
                nextRecheckAt = SystemClock.uptimeMillis() + recheckMs;
                nextFollowAt = SystemClock.uptimeMillis() + 15_000;
                String fixMsg = persist ? "Legenda acertada pelo áudio (" + secs(o.targetDelay) + ")" : "Legenda acertada pelo áudio (velocidade e atraso)";
                h.notice(fixMsg);
                status(fixMsg + ".", TONE_GOOD);
                noticedKey = k + "#fim";
                log("aplicado: atraso " + o.targetDelay + " ms, escala " + o.scale);
                break;
            }
            case AudioSyncCore.Outcome.OK:
                mScale = 1.0;
                mOffset = h.delayMs();
                lastWritten = h.delayMs();
                mark(k, "ok", 1.0, o.offsetMs, h.delayMs());
                st = DONE;
                nextRecheckAt = SystemClock.uptimeMillis() + recheckMs;
                log("a legenda já estava certa (deslocamento medido " + Math.round(o.offsetMs) + " ms): não mexi");
                status("Legenda conferida pelo áudio: já estava certa.", TONE_GOOD);
                noticeOnce(k, "fim", "Legenda confirmada pelo áudio");
                break;
            case AudioSyncCore.Outcome.REFUSED:
                mark(k, "no", 1.0, 0, 0);
                st = OFF;
                log("o áudio não tem certeza: legenda deixada como estava");
                status(o.msg.isEmpty() ? "Sem confiança: o áudio não confirma esta legenda." : o.msg, TONE_WARN);
                noticeOnce(k, "fim", "Legenda: não consegui confirmar pelo áudio — usa Sincronizar no painel");
                break;
            default: // erro, cancelado, pulado: tenta de novo mais tarde (no máximo 2 vezes)
                if (o.kind == AudioSyncCore.Outcome.CANCELLED) { st = NEW; retryPending = true; retryAt = SystemClock.uptimeMillis() + 30_000; log("análise cancelada (" + o.note + "): tento de novo em 30 s"); }
                else if (o.kind == AudioSyncCore.Outcome.SKIPPED || retries >= 2) {
                    st = OFF;
                    log("desisti (" + o.note + ")");
                    status(o.msg.isEmpty() ? "Não consegui conferir pelo áudio (" + o.note + ")." : o.msg, TONE_WARN);
                    noticeOnce(k, "fim", o.kind == AudioSyncCore.Outcome.SKIPPED ? "Legenda: ficheiro pesado para analisar sozinho — usa Sincronizar no painel" : "Legenda: não consegui ouvir o áudio — usa Sincronizar no painel");
                }
                else {
                    retries++; retryPending = true; retryAt = SystemClock.uptimeMillis() + 120_000 * retries;
                    log("tento de novo em " + 120 * retries + " s (" + o.note + ")");
                    status((o.msg.isEmpty() ? "Não consegui conferir pelo áudio." : o.msg) + " Tento de novo em " + 2 * retries + " min.", TONE_WARN);
                }
                break;
        }
    }

    // ------------------------------------------------------------ escala diferente de 1: o atraso acompanha o modelo

    private static int delayAt(double a, double off, long t) {
        double cue = (t - off) / a;
        return (int) Math.max(-600_000, Math.min(600_000, Math.round(((a - 1.0) * cue + off) / 10.0) * 10));
    }

    private void follow(long now) {
        nextFollowAt = now + 15_000;
        int d = delayAt(mScale, mOffset, h.timeMs());
        if (Math.abs(d - h.delayMs()) < 250) return;
        h.applyDelay(d, false);
        lastWritten = d;
    }

    // ------------------------------------------------------------ reverificação a cada ~10 min

    private void startRecheck(SubtitleSync.Cand cand, String k) {
        final int tok = token.incrementAndGet();
        running = true;
        resetHealth();
        final long pos = h.timeMs(), dur = h.durationMs();
        final String url = h.url(), text = cand.text;
        final double a = mScale, b = mOffset;
        final Recheck.Out prev = prevOut;
        final ArrayList<Window> fl = new ArrayList<>(failing);
        log("reverificando na posição " + pos / 1000 + " s");
        runStartedAt = SystemClock.uptimeMillis();
        final long budget = recheckBudget();
        final int trackId = h.audioTrackId();
        exec(() -> {
            core.audioTrackId = trackId;
            AudioSyncCore.Check ck = core.recheck(url, text, dur, pos, a, b, prev, fl, budget);
            ui.post(() -> finishRecheck(tok, k, ck, pos, a, b));
        });
    }

    private void finishRecheck(int tok, String k, AudioSyncCore.Check ck, long pos, double a, double b) {
        try { finishRecheck0(tok, k, ck, pos, a, b); } catch (Throwable t) { fail("reverificação", t); }
    }

    private void finishRecheck0(int tok, String k, AudioSyncCore.Check ck, long pos, double a, double b) {
        if (stopped || tok != token.get()) return;
        running = false;
        nextRecheckAt = SystemClock.uptimeMillis() + recheckMs;
        log("reverificação: " + ck.note + " | " + healthLine());
        if (ck.kind == AudioSyncCore.Outcome.SKIPPED && ck.note.contains("não reverifico")) nextRecheckAt = SystemClock.uptimeMillis() + 3_600_000L;
        if (!k.equals(key) || ck.out == null) return;
        if (h.delayMs() != lastWritten) { st = OFF; return; }
        switch (ck.out.verdict) {
            case KEEP:
                if (ck.explained) { failing.clear(); prevOut = null; }
                else if (ck.window != null) addFailing(ck.window);
                break;
            case PROPOSED:
                prevOut = ck.out;
                if (ck.window != null) addFailing(ck.window);
                log("reverificação: proposta de novo deslocamento " + Math.round(ck.out.newOffsetMs) + " ms, aguardando confirmação (nada mudou)");
                break;
            case CHANGE:
                confirmChange(k, ck, pos, a, b);
                break;
            default:
                break;
        }
    }

    private void addFailing(Window w) {
        failing.add(w);
        while (failing.size() > 2) failing.remove(0);
    }

    /** o Recheck viu um salto: só vale se uma sessão completa em volta da posição concordar (mesmas portas do alinhamento inicial) */
    private void confirmChange(String k, AudioSyncCore.Check ck, long pos, double a, double b) {
        final int tok = token.incrementAndGet();
        running = true;
        final SubtitleSync.Cand cand = h.activeCand();
        if (cand == null || cand.text == null) { running = false; return; }
        final long dur = h.durationMs();
        final String url = h.url(), text = cand.text;
        final long applyFromSub = ck.out.applyFromSubMs;
        log("reverificação: salto proposto, confirmando com 3 janelas em volta de " + pos / 1000 + " s");
        exec(() -> {
            AudioSyncCore.Outcome o = core.confirmChange(url, text, dur, pos, a, b, ck.window);
            ui.post(() -> {
                if (stopped || tok != token.get()) return;
                running = false;
                log("confirmação: " + o.note);
                if (o.kind != AudioSyncCore.Outcome.FIX || Math.abs(o.scale - a) > 1e-6) {
                    log("o reajuste não foi confirmado: legenda mantida");
                    prevOut = null;
                    return;
                }
                if (applyFromSub == Long.MAX_VALUE) { log("sem pausa de falas à frente para aplicar o reajuste: legenda mantida"); return; }
                scheduleApply(k, tok, o, (long) (a * applyFromSub + b), SystemClock.uptimeMillis() + 180_000);
            });
        });
    }

    /** o reajuste entra no intervalo entre falas: um pouco antes da fala que abre depois da pausa (nunca no meio de uma fala) */
    private void scheduleApply(String k, int tok, AudioSyncCore.Outcome o, long mediaStartMs, long deadline) {
        ui.postDelayed(() -> {
            if (stopped || tok != token.get() || !k.equals(key) || h.delayMs() != lastWritten) return;
            long t = h.timeMs();
            if (SystemClock.uptimeMillis() > deadline || t > mediaStartMs + 5000) { log("reajuste: a pausa passou sem aplicar; legenda mantida"); return; }
            if (t < mediaStartMs - 300) { scheduleApply(k, tok, o, mediaStartMs, deadline); return; }
            boolean persist = o.scale == 1.0;
            int d = delayAt(o.scale, o.offsetMs, t);
            h.applyDelay(d, persist);
            lastWritten = d;
            mScale = o.scale;
            mOffset = o.offsetMs;
            prevOut = null;
            failing.clear();
            mark(k, "fix", o.scale, o.offsetMs, persist ? d : 0);
            h.notice("Legenda reajustada pelo áudio (" + secs(d) + " a partir daqui)");
            log("reajuste aplicado: atraso " + d + " ms");
        }, 400);
    }

    private static String secs(long ms) {
        String v = String.format(Locale.ROOT, "%.1f", Math.abs(ms) / 1000.0).replace('.', ',');
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s";
    }
}
