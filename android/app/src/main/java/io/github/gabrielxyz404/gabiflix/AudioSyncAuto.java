package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sincronização da legenda pelo áudio, totalmente automática: quando o vídeo está tocando e há uma legenda baixada ativa, ouve o filme
 * (3 janelas de 90 s numa segunda instância do libVLC, thread de prioridade baixa) e, se o alinhador tem certeza, acerta o atraso da legenda.
 * O mesmo motor atende o botão "Sincronizar automaticamente" do painel de legendas ({@link #chain}).
 *
 * Quantas vezes se analisa (pedido do usuário: o motor verificava a legenda vezes demais):
 *  - depois de um resultado CONFIANTE (acertou ou "já estava certa") a legenda NÃO é analisada de novo nesta sessão, e o resultado fica guardado
 *    por filme+legenda durante 30 dias (reabrir o mesmo filme não analisa de novo). Só volta a analisar quando o usuário troca de faixa de legenda
 *    (outra legenda), quando há um salto de mais de 10 min na linha do tempo JUNTO com um ajuste manual do atraso, ou quando ele toca em
 *    "Sincronizar automaticamente";
 *  - depois de um resultado SEM CONFIANÇA (recusada, erro de captura, reprodução engasgando) tenta mais UMA vez, 20 min depois, e para;
 *  - as quedas do processo de captura (que a captura já recupera na hora, com outra configuração) contam como UMA tentativa só;
 *  - só aparece aviso na tela quando o atraso muda de verdade; confirmação silenciosa só na primeira conferência de um filme.
 * Toda decisão de iniciar (ou não) uma análise sai no registro, com o motivo: "AUDIOSYNC GATILHO motivo=… decisão=…".
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
    /** NEW = ainda não analisada; DONE = resultado confiante (não se analisa de novo); HOLD = DONE mas o usuário mexeu no atraso à mão; OFF = parou */
    private static final int NEW = 0, DONE = 2, OFF = 3, HOLD = 4;
    private static final long TICK_MS = 1000;
    /** quanto vale um resultado confiante guardado (fix/ok) */
    private static final long CONFIDENT_TTL_MS = 30L * 86_400_000L;
    /** "sem confiança" guardado vale por 3 dias */
    private static final long NO_TTL_MS = 3L * 86_400_000L;

    private final Activity act;
    private final Host h;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioSyncCore core;
    private final ExecutorService ex;
    private final AtomicInteger token = new AtomicInteger();
    private boolean started, stopped;

    // configuração (os extras só existem nos testes)
    private final boolean autoOn;
    private long startAfterMs = 25_000;
    /** depois de um resultado sem confiança: uma segunda e última tentativa daqui a tanto (teste: gfAudioRetrySec encurta) */
    private long retryMs = 20 * 60_000L;
    /** salto na linha do tempo que conta como "salto grande" (teste: gfAudioFarSeekSec) */
    private long farSeekMs = 600_000L;
    /** o filme tem de estar tocando sem parar por este tempo antes de o automático abrir a captura (só nos testes, com gfAudioStartSec, vale 0) */
    private long needPlayMs = 30_000;
    private long playingSince;

    /** estado de UMA legenda (por filme+fonte+legenda) nesta sessão do player; fica guardado se a faixa for religada ou trocada e voltar */
    private static final class Sub {
        int st = NEW;
        /** análises começadas para esta legenda nesta sessão (para o registro/provas) */
        int analyses;
        /** análises que terminaram sem confiança (recusada, erro, engasgo): no máximo 2 */
        int attempts;
        boolean retryPending, retryFromRefusal, interrupted;
        long retryAt, keySince;
        double mScale = 1.0, mOffset;
        int lastWritten;
        long nextFollowAt;
        /** gatilho "ajuste manual + salto grande": os dois têm de acontecer depois do resultado confiante */
        boolean manualEdited, farSeek;
        int stallAborts;
    }

    private final Map<String, Sub> subs = new HashMap<>();
    private Sub cur;
    private String key;
    private boolean running;
    private long runStartedAt;
    private volatile long rescanAt;
    private volatile String keyNow;
    private long lastPosMs = -1, lastPosWall;

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
        v = extra(in, "gfAudioRetrySec");
        if (v != null) try { retryMs = Integer.parseInt(v) * 1000L; } catch (NumberFormatException ignored) {}
        v = extra(in, "gfAudioFarSeekSec");
        if (v != null) try { farSeekMs = Integer.parseInt(v) * 1000L; } catch (NumberFormatException ignored) {}
        if ("true".equals(extra(in, "gfAudioDsp"))) core.forceDsp = true;
        AudioCap.testHook = extra(in, "gfAudioCrash");   // só nos testes: vad | capture | capture-all (derruba o processo de captura de propósito)
        core.progress = s2 -> ui.post(() -> { if (!stopped) h.status(s2, TONE_NEUTRAL); });
    }

    private static String extra(android.content.Intent in, String k) {
        try {
            Object o = in == null || in.getExtras() == null ? null : in.getExtras().get(k);
            return o == null ? null : String.valueOf(o);
        } catch (Throwable t) { return null; }
    }

    private static void log(String s) { SubtitleSync.log("AUDIOSYNC " + s); }

    private static String shortKey(String k) { return k == null ? "-" : k.substring(Math.max(0, k.length() - 48)); }

    /** o registro de cada gatilho (iniciar ou não uma análise) e a razão: o log do CI conta as linhas "decisão=análise" */
    private void trigger(String why, String k, String decision) {
        Sub s = k == null ? null : subs.get(k);
        log("GATILHO motivo=" + why + " legenda=" + shortKey(k) + " decisão=" + decision + (s == null ? "" : " [análises=" + s.analyses + " sem_confiança=" + s.attempts + " estado=" + stName(s.st) + "]"));
    }

    private static String stName(int st) { return st == NEW ? "nova" : st == DONE ? "confiante" : st == HOLD ? "manual" : "parada"; }

    /** sem limite de dados: a sincronização automática roda sempre, qualquer que seja o tamanho do arquivo e o tipo de rede (decisão do usuário) */
    private long autoBudget() { return AudioSyncCore.NO_LIMIT; }

    private void status(String s, int tone) { h.status(s, tone); }

    void start() {
        if (started || stopped) return;
        started = true;
        purgeOldMarks();
        log("pronto (sem limite de dados; automático=" + autoOn + ", começa " + startAfterMs / 1000 + " s depois de a legenda ficar ativa; resultado confiante vale " + CONFIDENT_TTL_MS / 86_400_000L
                + " dias e não se reverifica; sem confiança: 1 nova tentativa em " + retryMs / 1000 + " s; salto grande = " + farSeekMs / 1000 + " s)");
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
        if (running) {
            log("pausa da tela: análise em andamento cancelada (não conta como tentativa; recomeça uma vez ao voltar)");
            if (cur != null) cur.interrupted = true;
        }
        token.incrementAndGet();
        core.cancel();
        running = false;
    }

    /** voltou ao app (onStart/onResume): só registra; nenhuma análise nasce daqui */
    void onReturn(String what) {
        Sub s = cur;
        trigger("voltou-ao-app(" + what + ")", key, s == null ? "ignorado: sem legenda ativa" : s.st == DONE || s.st == HOLD ? "ignorado: resultado confiante já guardado" : "ignorado: o ciclo decide sozinho (sem gatilho próprio)");
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
            String k = keyNow;
            ui.post(() -> { Sub s = k == null ? null : subs.get(k); if (s != null) s.analyses++; trigger("botão-sincronizar", k, "análise"); });
            core.audioTrackId = h.audioTrackId();
            AudioSyncCore.Outcome o = core.run(h.url(), active.text, h.durationMs(), videoTimeMs, d1, AudioSyncCore.NO_LIMIT);
            log("botão: " + o.note + " | " + o.st.summary());
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
                    rescanAt = SystemClock.uptimeMillis() + 6000;
                    return r1.status == AutoSync.FAILED ? AutoSync.Result.failedMsg(r1.note, o.msg) : r1;
                case AudioSyncCore.Outcome.CANCELLED:
                    return r1;
                default: // erro de captura, pulado: diz o motivo em vez de um "não consegui" genérico (se a referência já acertou, vale a referência)
                    return r1.status == AutoSync.FAILED ? AutoSync.Result.failedMsg(r1.note, o.msg.isEmpty() ? "Não consegui ouvir o áudio do filme." : o.msg) : r1;
            }
        };
    }

    // ------------------------------------------------------------ marcas guardadas (cache por filme+legenda)

    private void mark(String k, String kind, double scale, double off, int delay) {
        try {
            SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            p.edit().putString(k, AudioSyncCore.VERSION + "|" + kind + "|" + scale + "|" + off + "|" + delay + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    private static boolean expired(String[] a) {
        long age = System.currentTimeMillis() - Long.parseLong(a[5]);
        // "sem confiança" vale por 3 dias (a causa pode ter sido passageira: rede, faixa errada, versão do app); o confiante por 30 dias
        return age > (a[1].equals("no") ? NO_TTL_MS : CONFIDENT_TTL_MS);
    }

    private String[] readMark(String k) {
        try {
            String v = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(k, null);
            if (v == null) return null;
            String[] a = v.split("\\|");
            if (a.length != 6 || !a[0].equals(AudioSyncCore.VERSION)) return null;
            if (expired(a)) return null;
            return a;
        } catch (Throwable t) { return null; }
    }

    /** apaga as marcas vencidas (30 dias; "sem confiança" 3 dias) e as de versões antigas do motor */
    private void purgeOldMarks() {
        try {
            SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit();
            int n = 0;
            for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
                if (!(en.getValue() instanceof String)) continue;
                String[] a = ((String) en.getValue()).split("\\|");
                boolean drop;
                try { drop = a.length != 6 || !a[0].equals(AudioSyncCore.VERSION) || expired(a); } catch (Throwable t) { drop = true; }
                if (drop) { e.remove(en.getKey()); n++; }
            }
            if (n > 0) { e.apply(); log("marcas vencidas apagadas: " + n); }
        } catch (Throwable ignored) {}
    }

    /** a primeira conferência deste filme (nenhuma outra marca dele guardada antes): só nela a confirmação silenciosa mostra um aviso */
    private boolean firstCheckOfFilm(String k) {
        try {
            int cut = k.indexOf('|');
            String film = "filme:" + (cut > 0 ? k.substring(0, cut) : k);
            SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            String v = p.getString(film, null);
            if (v != null && System.currentTimeMillis() - Long.parseLong(v) < CONFIDENT_TTL_MS) return false;
            p.edit().putString(film, String.valueOf(System.currentTimeMillis())).apply();
            return true;
        } catch (Throwable t) { return false; }
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

    /** salto na linha do tempo (para frente ou para trás) maior que o limite: guarda para o gatilho "ajuste manual + salto grande" */
    private void watchSeek(long now, boolean pl) {
        long t = h.timeMs();
        if (lastPosMs >= 0 && t > 0) {
            long expected = lastPosMs + (pl ? now - lastPosWall : 0);
            long jump = Math.abs(t - expected);
            if (jump > farSeekMs && cur != null) {
                log("salto na linha do tempo de " + jump / 1000 + " s (de " + lastPosMs / 1000 + " s para " + t / 1000 + " s): só conta se o usuário também mexer no atraso à mão; sozinho não dispara análise");
                cur.farSeek = true;
            }
        }
        if (t > 0) { lastPosMs = t; lastPosWall = now; }
    }

    /** a legenda ativa mudou: o estado de cada legenda fica guardado (religar a faixa ou voltar a ela não recomeça do zero) */
    private void switchSub(String k, long now) {
        if (running && k != null && key != null && !k.equals(key)) {
            token.incrementAndGet();
            core.cancel();
            running = false;
            log("legenda trocada: análise em andamento cancelada");
        }
        key = k;
        keyNow = k;
        if (k == null) { cur = null; return; }
        Sub s = subs.get(k);
        boolean fresh = s == null;
        if (fresh) {
            s = new Sub();
            s.keySince = now;
            subs.put(k, s);
        }
        cur = s;
        status(null, TONE_NEUTRAL);
        log("legenda ativa: " + shortKey(k) + (fresh ? " (nova nesta sessão)" : " (já vista nesta sessão: " + stName(s.st) + ", análises=" + s.analyses + ")"));
        if (fresh) trigger("troca-de-legenda", k, "avaliar (espera " + startAfterMs / 1000 + " s tocando; se há resultado guardado, não analisa)");
    }

    private long lastDiag;

    private void onTick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastDiag >= 20_000) { lastDiag = now; log("estado: st=" + (cur == null ? "-" : stName(cur.st)) + " executando=" + running + " " + h.debug() + " | " + CrashReporter.memLine()); }
        boolean pl = h.playing();
        if (!pl) playingSince = 0; else if (playingSince == 0) playingSince = now;
        sampleHealth(now, pl);
        watchSeek(now, pl);
        SubtitleSync.Cand cand = h.activeCand();
        String k = cand == null || cand.text == null ? null : h.delayKey();
        if ((k == null ? key != null : !k.equals(key))) switchSub(k, now);
        if (k == null || !autoOn || cur == null) return;
        Sub s = cur;
        if (running && hBuf >= 2 && now - runStartedAt > 5000) abortForStall(s, k, now);
        if (rescanAt > 0 && now >= rescanAt && !running) {
            // o botão acabou de terminar: adota o resultado que ele guardou (sem nova captura)
            rescanAt = 0; s.st = NEW; s.keySince = 0; s.attempts = 0; s.retryPending = false; s.retryFromRefusal = false;
            trigger("botão-adotar-resultado", k, "sem análise (só lê a marca que o botão guardou)");
        }
        switch (s.st) {
            case NEW:
                if (running || !pl || now - s.keySince < startAfterMs || now - playingSince < needPlayMs || (s.retryPending && now < s.retryAt)) return;
                if (now - lastStallAt < 20_000 && lastStallAt > 0) return;
                decideStart(s, cand, k, now);
                break;
            case DONE:
                if (h.delayMs() != s.lastWritten) {
                    s.st = HOLD;
                    s.manualEdited = true;
                    log("atraso mexido à mão: não mexo no atraso; só uma nova análise se também houver um salto de mais de " + farSeekMs / 1000 + " s na linha do tempo");
                    return;
                }
                if (running || core.busy()) return;
                if (Math.abs(s.mScale - 1.0) > 1e-9 && now >= s.nextFollowAt && pl) follow(s, now);
                break;
            case HOLD:
                if (running || core.busy() || !pl) return;
                if (s.manualEdited && s.farSeek) {
                    s.manualEdited = false;
                    s.farSeek = false;
                    startRun(s, cand, k, "ajuste-manual+salto-grande", now);
                }
                break;
            default:
                break;
        }
    }

    /** a legenda tem de ser uma que o motor ainda não viu; atraso manual guardado vence */
    private void decideStart(Sub s, SubtitleSync.Cand cand, String k, long now) {
        int saved = h.savedDelay(k);
        String[] m = readMark(k);
        boolean second = s.retryFromRefusal;
        if (m != null && !second) {
            if (m[1].equals("no")) {
                s.st = OFF;
                trigger("início", k, "ignorado: já vista pelo áudio e recusada (vale 3 dias)");
                log("esta legenda já foi vista pelo áudio e recusada: não tento de novo (por 3 dias)");
                status("Já conferida pelo áudio: sem confiança nesta legenda (pode ser de outra versão do filme). Se estiver fora, usa os botões.", TONE_WARN);
                return;
            }
            int markDelay = Integer.parseInt(m[4]);
            if (saved != markDelay) {
                s.st = OFF;
                trigger("início", k, "ignorado: atraso guardado (" + saved + " ms) não é o do áudio (" + markDelay + " ms): é manual");
                status("Há um ajuste manual guardado para esta legenda: não mexi.", TONE_NEUTRAL);
                return;
            }
            s.mScale = Double.parseDouble(m[2]);
            s.mOffset = Double.parseDouble(m[3]);
            if (m[1].equals("ok")) s.mOffset = h.delayMs();
            s.lastWritten = h.delayMs();
            s.st = DONE;
            s.nextFollowAt = 0;
            trigger("início", k, "ignorado: resultado confiante guardado (" + m[1] + ", há " + (System.currentTimeMillis() - Long.parseLong(m[5])) / 86_400_000L + " dias): sem captura e sem reverificação");
            status(m[1].equals("ok") ? "Legenda conferida pelo áudio: já estava certa." : "Legenda acertada pelo áudio antes (" + secs(markDelay) + ").", TONE_GOOD);
            return;
        }
        if (saved != 0 || h.delayMs() != 0) {
            s.st = OFF;
            trigger(second ? "segunda-tentativa" : "início", k, "ignorado: atraso manual guardado (" + (saved != 0 ? saved : h.delayMs()) + " ms)");
            status("Há um ajuste manual guardado para esta legenda (" + secs(saved != 0 ? saved : h.delayMs()) + "): não mexi.", TONE_NEUTRAL);
            return;
        }
        startRun(s, cand, k, second ? "segunda-tentativa-após-" + (s.attempts > 0 ? "sem-confiança" : "erro") : s.interrupted ? "retomada-após-sair-do-app" : "início", now);
    }

    private void startRun(Sub s, SubtitleSync.Cand cand, String k, String why, long now) {
        if (running || core.busy()) { trigger(why, k, "ignorado: já há uma análise em andamento (nunca duas ao mesmo tempo)"); return; }
        final int tok = token.incrementAndGet();
        running = true;
        s.analyses++;
        s.retryPending = false;
        s.retryFromRefusal = false;
        s.interrupted = false;
        trigger(why, k, "análise");
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
        if (cur != null) cur.st = OFF;
    }

    private void exec(Runnable r) {
        ex.execute(() -> { try { r.run(); } catch (Throwable t) { final Throwable e = t; ui.post(() -> fail("thread de análise", e)); } });
    }

    /** a reprodução engasgou duas vezes durante a análise: a rede não aguenta a captura junto com o filme; cancela e conta como resultado sem confiança */
    private void abortForStall(Sub s, String k, long now) {
        log("a reprodução engasgou " + hBuf + " vezes durante a análise: cancelo para não atrapalhar o filme");
        token.incrementAndGet();
        core.cancel();
        running = false;
        s.stallAborts++;
        inconclusive(s, k, "reprodução engasgou", "A reprodução engasgou durante a análise.");
    }

    /** resultado sem confiança (recusada, erro de captura, engasgo): uma segunda tentativa, 20 min depois, e para */
    private void inconclusive(Sub s, String k, String why, String userMsg) {
        s.attempts++;
        if (s.attempts >= 2) {
            s.st = OFF;
            s.retryPending = false;
            trigger("sem-confiança", k, "parar: " + why + " (2 tentativas feitas)");
            status(userMsg + " Parei de tentar sozinho: usa Sincronizar automaticamente no painel.", TONE_WARN);
        } else {
            s.st = NEW;
            s.retryPending = true;
            s.retryFromRefusal = true;
            s.retryAt = SystemClock.uptimeMillis() + retryMs;
            trigger("sem-confiança", k, "agendar UMA segunda tentativa em " + retryMs / 1000 + " s: " + why);
            status(userMsg + " Tento mais uma vez daqui a " + Math.max(1, retryMs / 60_000L) + " min.", TONE_WARN);
        }
    }

    private void finishRun(int tok, String k, int d0, AudioSyncCore.Outcome o) {
        try { finishRun0(tok, k, d0, o); } catch (Throwable t) { fail("resultado", t); }
    }

    private void finishRun0(int tok, String k, int d0, AudioSyncCore.Outcome o) {
        if (stopped || tok != token.get()) { log("resultado descartado (outra execução/legenda/tela): " + o.note); return; }
        running = false;
        log("resultado: " + o.note + (o.cuts ? " [aviso: a legenda parece ter cortes]" : "") + " | " + o.st.summary());
        log(healthLine());
        Sub s = subs.get(k);
        if (s == null || (key != null && !k.equals(key))) return;
        if (h.delayMs() != d0) { s.st = OFF; log("o usuário mexeu no atraso durante a análise: descartado"); return; }
        // as quedas do processo de captura que a captura recuperou sozinha (outra configuração, na hora) fazem parte desta mesma tentativa
        if (o.note != null && (o.note.contains("recuper") || o.note.contains("caiu"))) log("tentativa " + (s.attempts + 1) + ": houve queda(s) do processo de captura, tratadas dentro desta mesma tentativa (a captura troca de configuração na hora); contam como UMA tentativa só");
        switch (o.kind) {
            case AudioSyncCore.Outcome.FIX: {
                boolean persist = o.scale == 1.0;
                boolean changed = o.targetDelay != d0 || !persist;
                if (changed) h.applyDelay(o.targetDelay, persist);
                s.mScale = o.scale;
                s.mOffset = o.offsetMs;
                s.lastWritten = changed ? o.targetDelay : d0;
                mark(k, "fix", o.scale, o.offsetMs, persist ? o.targetDelay : 0);
                s.st = DONE;
                s.attempts = 0;
                s.manualEdited = false;
                s.farSeek = false;
                s.nextFollowAt = SystemClock.uptimeMillis() + 15_000;
                String fixMsg = persist ? "Legenda acertada pelo áudio (" + secs(o.targetDelay) + ")" : "Legenda acertada pelo áudio (velocidade e atraso)";
                if (changed) h.notice(fixMsg);   // o atraso mudou de verdade: único caso em que há aviso (além da primeira confirmação do filme)
                status(fixMsg + ".", TONE_GOOD);
                log("aplicado: atraso " + o.targetDelay + " ms, escala " + o.scale + " (resultado confiante: não será reverificado)");
                break;
            }
            case AudioSyncCore.Outcome.OK:
                s.mScale = 1.0;
                s.mOffset = h.delayMs();
                s.lastWritten = h.delayMs();
                mark(k, "ok", 1.0, o.offsetMs, h.delayMs());
                s.st = DONE;
                s.attempts = 0;
                s.manualEdited = false;
                s.farSeek = false;
                log("a legenda já estava certa (deslocamento medido " + Math.round(o.offsetMs) + " ms): não mexi (resultado confiante: não será reverificado)");
                status("Legenda conferida pelo áudio: já estava certa.", TONE_GOOD);
                if (firstCheckOfFilm(k)) h.notice("Legenda confirmada pelo áudio");   // confirmação silenciosa: só na primeira conferência do filme
                break;
            case AudioSyncCore.Outcome.REFUSED:
                mark(k, "no", 1.0, 0, 0);
                log("o áudio não tem certeza: legenda deixada como estava");
                inconclusive(s, k, "o áudio não tem certeza", o.msg.isEmpty() ? "Sem confiança: o áudio não confirma esta legenda." : o.msg);
                break;
            default: // erro, cancelado, pulado
                if (o.kind == AudioSyncCore.Outcome.CANCELLED) {
                    s.st = NEW; s.retryPending = true; s.retryAt = SystemClock.uptimeMillis() + 30_000; s.interrupted = true;
                    log("análise cancelada (" + o.note + "): recomeça em 30 s (não conta como tentativa)");
                } else if (o.kind == AudioSyncCore.Outcome.SKIPPED) {
                    s.st = OFF;
                    trigger("pulado", k, "parar: " + o.note);
                    status(o.msg.isEmpty() ? "Não consegui conferir pelo áudio (" + o.note + ")." : o.msg, TONE_WARN);
                } else inconclusive(s, k, "erro: " + o.note, o.msg.isEmpty() ? "Não consegui conferir pelo áudio (" + o.note + ")." : o.msg);
                break;
        }
    }

    // ------------------------------------------------------------ escala diferente de 1: o atraso acompanha o modelo (sem nova análise)

    private static int delayAt(double a, double off, long t) {
        double cue = (t - off) / a;
        return (int) Math.max(-600_000, Math.min(600_000, Math.round(((a - 1.0) * cue + off) / 10.0) * 10));
    }

    private void follow(Sub s, long now) {
        s.nextFollowAt = now + 15_000;
        int d = delayAt(s.mScale, s.mOffset, h.timeMs());
        if (Math.abs(d - h.delayMs()) < 250) return;
        h.applyDelay(d, false);
        s.lastWritten = d;
    }

    private static String secs(long ms) {
        String v = String.format(Locale.ROOT, "%.1f", Math.abs(ms) / 1000.0).replace('.', ',');
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s";
    }
}
