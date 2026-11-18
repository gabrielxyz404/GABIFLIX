package io.github.gabrielxyz404.gabiflix;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Captura de áudio num PROCESSO SEPARADO (android:process=":audiocap"). O segundo libVLC é código nativo: se ele cair (falta de memória
 * na TV, erro do decodificador), cai só este processo; o player do filme (outro processo) continua tocando. O cliente é {@link AudioCap}.
 * Protocolo (Messenger): CAPTURE (com replyTo) -> REPLY; CANCEL, RESET e CLOSE não esperam resposta.
 */
public final class AudioCapService extends Service {
    static final int MSG_CAPTURE = 1, MSG_CANCEL = 2, MSG_RESET = 3, MSG_CLOSE = 4, MSG_VAD = 5, MSG_REPLY = 10;

    private static volatile boolean alive;
    private HandlerThread ctl;
    private Messenger messenger;
    private AudioCapEngine engine;
    private ExecutorService worker;
    /** o Silero (Java puro, sem código nativo) também vive AQUI: uma falta de memória dele nunca fecha o player */
    private io.github.gabrielxyz404.gabiflix.audiosync.JavaSileroVad vad;
    /** modo com que {@link #vad} foi criado (0 = normal: até 2 fios; 1 = seguro: 1 fio só) */
    private int vadMode;

    @Override public void onCreate() {
        super.onCreate();
        alive = true;
        engine = new AudioCapEngine(this);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "gf-audiocap");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ctl = new HandlerThread("gf-audiocap-ctl");
        ctl.start();
        messenger = new Messenger(new Handler(ctl.getLooper()) {
            @Override public void handleMessage(Message m) {
                try {
                    switch (m.what) {
                        case MSG_CAPTURE: capture(m); break;
                        case MSG_VAD: vad(m); break;
                        case MSG_CANCEL: engine.cancel(); break;
                        case MSG_RESET: engine.reset(); break;
                        case MSG_CLOSE: engine.cancel(); break;
                        default: break;
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void capture(Message m) {
        final Messenger to = m.replyTo;
        final Bundle b = m.getData();
        worker.execute(() -> {
            Bundle r = new Bundle();
            r.putInt("id", b.getInt("id"));
            try {
                int cfg = b.getInt("cfg", 0);
                String hook = b.getString("hook");   // só nos testes: "capture" (cai com a configuração 0) ou "capture-all" (cai com todas)
                boolean crashTest = "capture-all".equals(hook) || ("capture".equals(hook) && cfg == 0);
                AudioCapEngine.Out o = engine.capture(b.getString("url"), b.getLong("start"), b.getLong("dur"), new File(b.getString("out")), b.getLong("timeout"), b.getInt("track", -1), cfg, crashTest);
                r.putBoolean("ok", o.ok);
                r.putString("end", o.end);
                r.putDouble("wallS", o.wallS);
                r.putDouble("pcmS", o.pcmS);
                r.putLong("bytes", o.bytes);
                r.putString("track", o.track);
            } catch (Throwable t) {
                r.putBoolean("ok", false);
                r.putString("end", "erro na captura: " + t);
            }
            try {
                Message rm = Message.obtain(null, MSG_REPLY);
                rm.setData(r);
                if (to != null) to.send(rm);
            } catch (Throwable ignored) {}
        });
    }

    /** PCM s16le (ficheiro) -> probabilidade de fala a cada 10 ms (ficheiro de floats little-endian) */
    private void vad(Message m) {
        final Messenger to = m.replyTo;
        final Bundle b = m.getData();
        worker.execute(() -> {
            Bundle r = new Bundle();
            r.putInt("id", b.getInt("id"));
            try {
                CrashReporter.stage("vad começando: lendo o PCM");
                java.io.File in = new java.io.File(b.getString("in")), out = new java.io.File(b.getString("out"));
                byte[] raw = new byte[(int) in.length()];
                try (java.io.FileInputStream fi = new java.io.FileInputStream(in)) { int o = 0, n; while (o < raw.length && (n = fi.read(raw, o, raw.length - o)) > 0) o += n; }
                short[] pcm = new short[raw.length / 2];
                java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                final int vmode = b.getInt("vmode", 0);
                if (vad != null && vadMode != vmode) { try { vad.close(); } catch (Throwable ignored) {} vad = null; }
                if (vad == null) {
                    CrashReporter.stage("vad modo " + vmode + " lendo os pesos silero_v5_16k.bin");
                    // modo normal: até 2 fios (em 4 núcleos o player segue folgado); modo seguro: 1 fio. Fios de prioridade baixa: a análise nunca pesa no player
                    int cores = Runtime.getRuntime().availableProcessors();
                    int threads = vmode == 1 ? 1 : (cores >= 4 ? 2 : 1);
                    java.util.concurrent.ThreadFactory tf = task -> {
                        Thread th = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run(); }, "gf-silero");
                        th.setDaemon(true);
                        th.setPriority(Thread.MIN_PRIORITY);
                        return th;
                    };
                    try (java.io.InputStream is = getAssets().open("silero_v5_16k.bin")) {
                        vad = io.github.gabrielxyz404.gabiflix.audiosync.JavaSileroVad.load(is, threads, tf);
                    }
                    vadMode = vmode;
                }
                CrashReporter.stage("vad modo " + vmode + " Silero em Java, " + pcm.length / 16000 + " s de áudio");
                String vhook = b.getString("hook");
                if ("vad-all".equals(vhook) || ("vad".equals(vhook) && vmode == 0)) {   // só nos testes
                    CrashReporter.stage("TESTE: queda nativa forçada no VAD (modo " + vmode + ")");
                    android.os.Process.sendSignal(android.os.Process.myPid(), 11);
                    Thread.sleep(10_000);
                }
                float[] p = vad.speechProb(pcm, pcm.length, 16000);
                CrashReporter.stage("vad terminou");
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(p.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                bb.asFloatBuffer().put(p);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(bb.array()); }
                r.putBoolean("ok", true);
                r.putString("end", "silero");
            } catch (Throwable t) {
                r.putBoolean("ok", false);
                r.putString("end", "silero indisponível: " + t);
            }
            try {
                Message rm = Message.obtain(null, MSG_REPLY);
                rm.setData(r);
                if (to != null) to.send(rm);
            } catch (Throwable ignored) {}
        });
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        try { engine.cancel(); } catch (Throwable ignored) {}
        try { worker.shutdownNow(); } catch (Throwable ignored) {}
        try { AudioCapEngine.close(); } catch (Throwable ignored) {}
        try { if (vad != null) vad.close(); } catch (Throwable ignored) {}
        vad = null;
        try { ctl.quit(); } catch (Throwable ignored) {}
        super.onDestroy();
        alive = false;
        // este processo só existe para a captura: devolve a memória. Espera um pouco e só mata se a próxima análise não o reabriu entretanto
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (!alive) System.exit(0); }, 2000);   // saída normal (o sistema registra EXIT_SELF, não uma morte por sinal)
    }
}
