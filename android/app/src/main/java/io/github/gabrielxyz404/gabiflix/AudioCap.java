package io.github.gabrielxyz404.gabiflix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captura de áudio para a sincronização pelo áudio (PCM mono 16 kHz de uma janela do MESMO endereço do vídeo). O trabalho em si (segunda
 * instância do libVLC, em {@link AudioCapEngine}) roda em OUTRO PROCESSO ({@link AudioCapService}, ":audiocap"): uma queda nativa ali
 * (memória, decodificador) nunca fecha o player. Aqui só se fala com o serviço. Uma captura por vez. Sempre chamada fora da thread principal.
 */
final class AudioCap {
    static final class Out {
        boolean ok;
        String end = "?";
        double wallS, pcmS;
        /** bytes lidos da rede pelo libVLC nesta janela (-1 = o libVLC não disse) */
        long bytes = -1;
        /** qual faixa de áudio a segunda instância usou (para o registro) */
        String track = "?";
        /** o processo de captura caiu (ou foi morto) enquanto esta operação corria */
        boolean crashed;
    }

    /** só nos testes (extra gfAudioCrash): "vad" (cai no modo normal do Silero), "vad-all" (cai em todos), "capture" (cai com a configuração 0) ou "capture-all" */
    static volatile String testHook;

    private static final String FB_PREFS = "audiocap-fallback";
    /**
     * qual motor de VAD gravou os marcadores silero_broken/silero_bad. Até a 1.0.N o Silero rodava no ONNX Runtime (nativo) e, no Fire TV Stick 4K Max
     * (ARM de 32 bits), a criação da sessão derrubava o processo nos dois modos: o aparelho ficou marcado como "silero_broken" e passou a usar o DSP, que recusa
     * a maioria das legendas. Agora o Silero é Java puro: esses marcadores velhos não valem mais e são apagados uma vez.
     */
    private static final String VAD_ENGINE = "java1";
    /** quantos modos do Silero existem: 0 = normal (Java puro, até 2 fios); 1 = seguro (Java puro, 1 fio só) */
    static final int NVAD = 2;

    private static final Object L = new Object();
    private static Messenger svc;
    private static boolean binding;
    private static ServiceConnection conn;
    private static volatile Context appCtx;
    /** configurações de captura em que o processo já caiu (bit i = configuração i), por aparelho+versão */
    private static volatile int capBad;
    /** todas as configurações caíram: a captura fica desligada até o app reabrir */
    private static volatile boolean capDisabled;
    /** modos do Silero em que o processo já caiu (bit i = modo i; 0 = normal, 1 = seguro: 1 fio só), por aparelho+versão */
    private static volatile int sileroBad;
    /** o Silero não carregou por erro Java (pesos ausentes, falta de memória etc.): não adianta repetir até o app reabrir */
    private static volatile boolean sileroUnavail;
    /** todos os modos do Silero derrubaram o processo neste aparelho+versão: nunca mais é usado (gravado) */
    private static volatile boolean sileroBroken;
    private static volatile boolean prefsLoaded;
    private static final AtomicInteger ids = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private static final Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            if (m.what != AudioCapService.MSG_REPLY) return;
            Bundle b = m.getData();
            Pending p = pending.remove(b.getInt("id"));
            if (p == null) return;
            p.out.ok = b.getBoolean("ok");
            p.out.end = b.getString("end", "?");
            p.out.wallS = b.getDouble("wallS");
            p.out.pcmS = b.getDouble("pcmS");
            p.out.bytes = b.getLong("bytes", -1);
            p.out.track = b.getString("track", "?");
            p.done.countDown();
        }
    });

    private static final class Pending {
        final Out out = new Out();
        final CountDownLatch done = new CountDownLatch(1);
        /** o que o processo de captura fazia para este pedido: "captura" ou "vad" */
        final String kind;
        final int cfg;
        Pending(String kind, int cfg) { this.kind = kind; this.cfg = cfg; }
    }

    private final Context ctx;
    private volatile boolean cancelled;

    AudioCap(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private static void log(String m) { SubtitleSync.log("AUDIOSYNC " + m); }

    // ------------------------------------------------------------ o que vale neste aparelho (guardado por aparelho + versão do app)

    /** muda com o aparelho e com a versão: uma versão nova volta a tentar o que a anterior desligou */
    private static String devKey(Context c) {
        return BuildConfigHelper.versionName(c) + "|" + android.os.Build.FINGERPRINT + "|" + (android.os.Process.is64Bit() ? "64" : "32");
    }

    private static synchronized void loadPrefs(Context c) {
        if (prefsLoaded) return;
        prefsLoaded = true;
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE);
            if (!VAD_ENGINE.equals(sp.getString("vad_engine", ""))) {
                boolean had = sp.contains("silero_broken") || sp.contains("silero_bad");
                sp.edit().remove("silero_broken").remove("silero_bad").putString("vad_engine", VAD_ENGINE).apply();
                if (had) log("Silero: marcadores de queda do ONNX Runtime apagados (agora é Java puro, sem código nativo)");
            }
            String k = devKey(c);
            sileroBroken = k.equals(sp.getString("silero_broken", ""));
            String sb = sp.getString("silero_bad", "");
            int j = sb.lastIndexOf('#');
            if (j > 0 && sb.substring(0, j).equals(k)) sileroBad = Integer.parseInt(sb.substring(j + 1));
            String cb = sp.getString("cap_bad", "");
            int i = cb.lastIndexOf('#');
            if (i > 0 && cb.substring(0, i).equals(k)) {
                capBad = Integer.parseInt(cb.substring(i + 1));
                if (capBad >= (1 << AudioCapEngine.NCFG) - 1) capBad = 0;   // todas desligadas numa vez anterior: tenta de novo (pode ter sido falta de memória)
            }
            if (sileroBroken) log("Silero desligado neste aparelho (derrubou o processo antes em todos os modos): VAD por DSP");
            else if (sileroBad != 0) log("modos do Silero que já derrubaram o processo neste aparelho: máscara " + sileroBad);
            if (capBad != 0) log("configurações de captura que já derrubaram o processo neste aparelho: máscara " + capBad);
        } catch (Throwable ignored) {}
    }

    private static void saveSileroBroken(Context c) {
        try { c.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE).edit().putString("silero_broken", devKey(c)).apply(); } catch (Throwable ignored) {}
    }

    private static void saveSileroBad(Context c) {
        try { c.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE).edit().putString("silero_bad", devKey(c) + "#" + sileroBad).apply(); } catch (Throwable ignored) {}
    }

    /** o primeiro modo do Silero que ainda não derrubou o processo (0 = normal, 1 = seguro), ou -1 se nenhum */
    private static int nextVadMode() {
        if (sileroBroken || sileroUnavail) return -1;
        for (int i = 0; i < NVAD; i++) if ((sileroBad & (1 << i)) == 0) return i;
        return -1;
    }

    private static void saveCapBad(Context c) {
        try { c.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE).edit().putString("cap_bad", devKey(c) + "#" + capBad).apply(); } catch (Throwable ignored) {}
    }

    /**
     * false = não usar o Silero neste aparelho: já derrubou o processo antes (nesta versão) ou não carregou. O Silero é Java puro (sem código nativo), então vale em
     * qualquer ABI, inclusive o ARM de 32 bits; o VAD por DSP é bem menos preciso (recusa legendas que o Silero acerta) e só entra como último recurso.
     */
    static boolean sileroAllowed(Context c) {
        loadPrefs(c.getApplicationContext());
        return nextVadMode() >= 0;
    }

    /** por que o Silero não será usado (para o registo) */
    static String sileroWhyNot(Context c) {
        loadPrefs(c.getApplicationContext());
        return sileroUnavail ? "o Silero em Java não carregou" : sileroBroken || sileroBad != 0 ? "ele derrubou o processo de captura neste aparelho" : "";
    }

    private static int nextCfg() {
        for (int i = 0; i < AudioCapEngine.NCFG; i++) if ((capBad & (1 << i)) == 0) return i;
        return -1;
    }

    /**
     * o processo de captura caiu (ou foi morto): quem esperava recebe o motivo e a etapa em que estava; o app segue e o relatório sai já
     * (CAPTURA_CAIU, com o motivo da morte, as migalhas, o ABI e o registo de queda do sistema)
     */
    private static void died(String why) {
        ServiceConnection old;
        synchronized (L) { old = conn; conn = null; svc = null; binding = false; }
        Context a = appCtx;
        if (old != null && a != null) { try { a.unbindService(old); } catch (Throwable ignored) {} }   // senão o sistema recria o serviço sozinho
        StringBuilder kinds = new StringBuilder();
        for (Integer id : pending.keySet()) {
            Pending p = pending.remove(id);
            if (p != null) {
                p.out.ok = false;
                p.out.crashed = true;
                p.out.end = "o processo de captura caiu";
                if (kinds.length() > 0) kinds.append('+');
                kinds.append(p.kind).append(" cfg=").append(p.cfg);
                p.done.countDown();
            }
        }
        if (kinds.length() == 0) { log("o processo de captura terminou sem nenhuma operação em curso (" + why + ")"); return; }
        String stage = CrashReporter.readStage("audiocap"), crumbs = CrashReporter.readCrumbsOf("audiocap");
        log("o processo de captura caiu (" + why + ") durante: " + kinds + "; o filme continua. Última etapa: " + lastLine(stage));
        CrashReporter.audiocapDied(kinds.toString().split(" ")[0], kinds + " (" + why + ")", stage, crumbs, System.currentTimeMillis());
    }

    private static String lastLine(String s) {
        String[] ls = s.split("\n");
        for (int i = ls.length - 1; i >= 0; i--) if (!ls[i].trim().isEmpty()) return ls[i].trim();
        return "(sem etapa marcada)";
    }

    private Messenger connect(long waitMs) {
        synchronized (L) {
            if (svc != null) return svc;
            if (!binding) {
                binding = true;
                conn = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName n, IBinder b) { synchronized (L) { svc = new Messenger(b); binding = false; L.notifyAll(); } }
                    @Override public void onServiceDisconnected(ComponentName n) { died("desconectou"); }
                    @Override public void onBindingDied(ComponentName n) { died("ligação morreu"); }
                    @Override public void onNullBinding(ComponentName n) { synchronized (L) { binding = false; L.notifyAll(); } }
                };
                try {
                    if (!ctx.bindService(new Intent(ctx, AudioCapService.class), conn, Context.BIND_AUTO_CREATE)) { binding = false; return null; }
                } catch (Throwable t) { binding = false; return null; }
            }
            long end = System.nanoTime() + waitMs * 1_000_000L;
            while (svc == null && binding) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                try { L.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return svc;
        }
    }

    private static boolean send(int what, Bundle data, Messenger replyTo) {
        Messenger s;
        synchronized (L) { s = svc; }
        if (s == null) return false;
        try {
            Message m = Message.obtain(null, what);
            if (data != null) m.setData(data);
            m.replyTo = replyTo;
            s.send(m);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** interrompe a captura em andamento (e as seguintes até {@link #reset()}) */
    void cancel() {
        cancelled = true;
        send(AudioCapService.MSG_CANCEL, null, null);
    }

    void reset() {
        cancelled = false;
        send(AudioCapService.MSG_RESET, null, null);
    }

    boolean isCancelled() { return cancelled; }

    /** solta a ligação com o serviço (o processo de captura termina e devolve a memória) */
    static void close() {
        ServiceConnection c;
        synchronized (L) { c = conn; conn = null; svc = null; binding = false; }
        for (Pending p : pending.values()) { p.out.end = "cancelado"; p.done.countDown(); }
        pending.clear();
        Context a = appCtx;
        if (c != null && a != null) { try { a.unbindService(c); } catch (Throwable ignored) {} }
    }

    /**
     * Bloqueia até terminar (ou cancelar). Qualquer falha (processo caiu, serviço indisponível) volta como captura que não deu certo, nunca
     * como exceção. Se o processo de captura CAIR (queda nativa), tenta de novo na hora com a próxima configuração de captura (ver
     * {@link AudioCapEngine#NCFG}); só devolve "captura desligada" depois de todas terem caído.
     * @param audioTrackId faixa de áudio a ouvir (a que está tocando no player); -1 = a que o arquivo marca como padrão
     */
    Out capture(String url, long startMs, long durMs, File out, long timeoutMs, int audioTrackId) {
        appCtx = ctx;
        loadPrefs(ctx);
        Out r = new Out();
        if (cancelled) { r.end = "cancelado"; return r; }
        long t0 = System.nanoTime();
        while (true) {
            if (capDisabled) { r.end = "captura desligada: o processo de captura caiu com todas as " + AudioCapEngine.NCFG + " configurações"; r.wallS = (System.nanoTime() - t0) / 1e9; return r; }
            int cfg = nextCfg();
            if (cfg < 0) { capDisabled = true; continue; }
            r = captureOnce(cfg, url, startMs, durMs, out, timeoutMs, audioTrackId);
            if (!r.crashed) {
                if (r.ok && capBad != 0) log("captura recuperada com a configuração " + cfg + " depois de queda(s) do processo");
                break;
            }
            capBad |= 1 << cfg;
            saveCapBad(ctx);
            log("a captura derrubou o processo com a configuração " + cfg + " (" + (cfg == 0 ? "padrão" : "alternativa") + "); "
                    + (nextCfg() >= 0 ? "tento a configuração " + nextCfg() + " na hora" : "não há mais configurações"));
            if (nextCfg() < 0) { capDisabled = true; r.end = "captura desligada: o processo de captura caiu com todas as " + AudioCapEngine.NCFG + " configurações"; break; }
            if (cancelled) { r.end = "cancelado"; break; }
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); r.end = "interrompido"; break; }
        }
        r.wallS = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    private Out captureOnce(int cfg, String url, long startMs, long durMs, File out, long timeoutMs, int audioTrackId) {
        Out r = new Out();
        long t0 = System.nanoTime();
        try {
            if (out.exists()) out.delete();
            Messenger s = connect(20_000);
            if (s == null) { r.end = "o serviço de captura não abriu"; return r; }
            int id = ids.incrementAndGet();
            Pending p = new Pending("captura", cfg);
            pending.put(id, p);
            Bundle b = new Bundle();
            b.putInt("id", id);
            b.putString("url", url);
            b.putLong("start", startMs);
            b.putLong("dur", durMs);
            b.putString("out", out.getAbsolutePath());
            b.putLong("timeout", timeoutMs);
            b.putInt("track", audioTrackId);
            b.putInt("cfg", cfg);
            if (testHook != null) b.putString("hook", testHook);
            if (!send(AudioCapService.MSG_CAPTURE, b, reply)) { pending.remove(id); r.end = "o serviço de captura não respondeu (sem ligação)"; return r; }
            long limit = System.nanoTime() + (timeoutMs + 90_000L) * 1_000_000L;
            long cancelAt = 0;
            while (true) {
                if (p.done.await(500, TimeUnit.MILLISECONDS)) break;
                long now = System.nanoTime();
                if (cancelled && cancelAt == 0) cancelAt = now;
                if (cancelAt != 0 && now - cancelAt > 8_000_000_000L) { pending.remove(id); p.out.end = "cancelado"; break; }   // o serviço não respondeu ao cancelamento
                if (now > limit) { pending.remove(id); p.out.end = "o serviço de captura não respondeu"; break; }
            }
            r = p.out;
            if (cancelled) { r.ok = false; r.end = "cancelado"; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.end = "interrompido";
        } catch (Throwable t) {
            r.end = "erro: " + t;
        }
        if (r.wallS <= 0) r.wallS = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    /**
     * Roda o Silero (Java puro) no processo de captura: lê o PCM (s16le) de `in` e grava as probabilidades (floats little-endian, 100 por segundo) em `out`.
     * false = não deu (serviço caiu, indisponível, cancelado, sem os pesos): quem chama usa o VAD por DSP (último recurso).
     * Se o processo cair durante o Silero, tenta na hora o modo seguro (1 fio só); se esse também cair, fica gravado (por aparelho + versão) que o Silero
     * não é mais usado e o VAD por DSP assume.
     */
    boolean vad(File in, File out) {
        appCtx = ctx;
        loadPrefs(ctx);
        while (true) {
            if (cancelled) return false;
            int mode = nextVadMode();
            if (mode < 0) return false;
            Out r = vadOnce(mode, in, out);
            if (r == null) return false;
            if (!r.crashed) {
                if (!r.ok) {
                    if (r.end.startsWith("silero indisponível")) sileroUnavail = true;   // erro Java (biblioteca não carregou): não adianta repetir nesta execução
                    log("VAD no processo de captura não deu: " + r.end);
                } else if (sileroBad != 0) log("Silero recuperado no modo " + mode + " (" + (mode == 0 ? "normal" : "seguro") + ") depois de queda(s) do processo");
                return r.ok && out.exists();
            }
            sileroBad |= 1 << mode;
            saveSileroBad(ctx);
            if (nextVadMode() < 0) {
                sileroBroken = true;
                saveSileroBroken(ctx);
                log("o Silero derrubou o processo no modo " + (mode == 0 ? "normal" : "seguro") + " e não há mais modos: desligado neste aparelho (versão atual); o VAD por DSP segue a análise");
                return false;
            }
            log("o Silero derrubou o processo no modo " + (mode == 0 ? "normal" : "seguro") + "; tento o modo " + nextVadMode() + " (seguro) na hora");
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    /** uma tentativa do Silero no modo `mode`; null = não foi possível pedir (serviço fora, cancelado, tempo) */
    private Out vadOnce(int mode, File in, File out) {
        try {
            if (out.exists()) out.delete();
            Messenger s = connect(20_000);
            if (s == null) return null;
            int id = ids.incrementAndGet();
            Pending p = new Pending("vad", mode);
            pending.put(id, p);
            Bundle b = new Bundle();
            b.putInt("id", id);
            b.putString("in", in.getAbsolutePath());
            b.putString("out", out.getAbsolutePath());
            b.putInt("vmode", mode);
            if (testHook != null) b.putString("hook", testHook);
            if (!send(AudioCapService.MSG_VAD, b, reply)) { pending.remove(id); return null; }
            long limit = System.nanoTime() + 240_000_000_000L;
            while (!p.done.await(500, TimeUnit.MILLISECONDS)) {
                if (cancelled || System.nanoTime() > limit) { pending.remove(id); return null; }
            }
            return p.out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** lê o WAV (mono s16 16 kHz) para short[]; pula o cabeçalho procurando o bloco "data" */
    static short[] readWav(File f) throws IOException { return AudioCapEngine.readWav(f); }
}
