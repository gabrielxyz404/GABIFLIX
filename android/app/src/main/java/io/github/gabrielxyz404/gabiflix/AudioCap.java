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
    }

    /** depois de tantas quedas do processo de captura a análise pelo áudio é desligada até o app reabrir */
    private static final int MAX_CRASHES = 2;

    private static final Object L = new Object();
    private static Messenger svc;
    private static boolean binding;
    private static ServiceConnection conn;
    private static volatile Context appCtx;
    private static volatile int crashes;
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
    }

    private final Context ctx;
    private volatile boolean cancelled;

    AudioCap(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private static void log(String m) { SubtitleSync.log("AUDIOSYNC " + m); }

    /** o processo de captura caiu (ou foi morto): quem esperava recebe o motivo; o app segue */
    private static void died(String why) {
        synchronized (L) { svc = null; binding = false; }
        crashes++;
        log("o processo de captura caiu (" + why + "), queda " + crashes + " de " + MAX_CRASHES + ": o filme continua");
        for (Integer id : pending.keySet()) {
            Pending p = pending.remove(id);
            if (p != null) { p.out.ok = false; p.out.end = "o processo de captura caiu"; p.done.countDown(); }
        }
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

    private static void send(int what, Bundle data, Messenger replyTo) {
        Messenger s;
        synchronized (L) { s = svc; }
        if (s == null) return;
        try {
            Message m = Message.obtain(null, what);
            if (data != null) m.setData(data);
            m.replyTo = replyTo;
            s.send(m);
        } catch (Throwable ignored) {}
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
     * como exceção.
     * @param audioTrackId faixa de áudio a ouvir (a que está tocando no player); -1 = a que o arquivo marca como padrão
     */
    Out capture(String url, long startMs, long durMs, File out, long timeoutMs, int audioTrackId) {
        appCtx = ctx;
        Out r = new Out();
        if (cancelled) { r.end = "cancelado"; return r; }
        if (crashes >= MAX_CRASHES) { r.end = "captura desligada: o processo de captura caiu " + crashes + " vezes"; return r; }
        long t0 = System.nanoTime();
        try {
            if (out.exists()) out.delete();
            Messenger s = connect(20_000);
            if (s == null) { r.end = "o serviço de captura não abriu"; return r; }
            int id = ids.incrementAndGet();
            Pending p = new Pending();
            pending.put(id, p);
            Bundle b = new Bundle();
            b.putInt("id", id);
            b.putString("url", url);
            b.putLong("start", startMs);
            b.putLong("dur", durMs);
            b.putString("out", out.getAbsolutePath());
            b.putLong("timeout", timeoutMs);
            b.putInt("track", audioTrackId);
            send(AudioCapService.MSG_CAPTURE, b, reply);
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

    /** lê o WAV (mono s16 16 kHz) para short[]; pula o cabeçalho procurando o bloco "data" */
    static short[] readWav(File f) throws IOException { return AudioCapEngine.readWav(f); }
}
