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
    static final int MSG_CAPTURE = 1, MSG_CANCEL = 2, MSG_RESET = 3, MSG_CLOSE = 4, MSG_REPLY = 10;

    private HandlerThread ctl;
    private Messenger messenger;
    private AudioCapEngine engine;
    private ExecutorService worker;

    @Override public void onCreate() {
        super.onCreate();
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
                AudioCapEngine.Out o = engine.capture(b.getString("url"), b.getLong("start"), b.getLong("dur"), new File(b.getString("out")), b.getLong("timeout"), b.getInt("track", -1));
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

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        try { engine.cancel(); } catch (Throwable ignored) {}
        try { worker.shutdownNow(); } catch (Throwable ignored) {}
        try { AudioCapEngine.close(); } catch (Throwable ignored) {}
        try { ctl.quit(); } catch (Throwable ignored) {}
        super.onDestroy();
        Process.killProcess(Process.myPid());   // este processo só existe para a captura: não deixa o libVLC ocupando memória
    }
}
