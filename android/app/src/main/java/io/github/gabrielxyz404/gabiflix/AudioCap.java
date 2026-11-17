package io.github.gabrielxyz404.gabiflix;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Captura de áudio para a sincronização pelo áudio: PCM mono 16 kHz de uma janela [início, início+duração) do MESMO endereço do vídeo,
 * numa SEGUNDA instância do libVLC (o player de reprodução não é tocado). A mídia vai para uma cadeia de saída "transcode -> WAV em arquivo":
 * sem saída de áudio o VLC não segue o relógio e lê/decodifica o mais rápido que a rede e a CPU deixam, com o corte exato (:start-time / :stop-time).
 * Uma captura por vez. Sempre chamada fora da thread principal.
 */
final class AudioCap {
    static final class Out {
        boolean ok;
        String end = "?";
        double wallS, pcmS;
    }

    private final Context ctx;
    private LibVLC vlc;
    private volatile CountDownLatch curLatch;
    private volatile boolean cancelled;

    AudioCap(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** interrompe a captura em andamento (e as seguintes até {@link #reset()}) */
    void cancel() {
        cancelled = true;
        CountDownLatch l = curLatch;
        if (l != null) l.countDown();
    }

    void reset() { cancelled = false; }

    boolean isCancelled() { return cancelled; }

    private synchronized LibVLC instance() {
        if (vlc == null) {
            ArrayList<String> o = new ArrayList<>();
            o.add("--no-lua");
            o.add("--no-osd");
            o.add("--no-spu");
            o.add("--no-sub-autodetect-file");
            vlc = new LibVLC(ctx, o);
        }
        return vlc;
    }

    synchronized void close() {
        if (vlc != null) { try { vlc.release(); } catch (Throwable ignored) {} vlc = null; }
    }

    /** Bloqueia até terminar (ou cancelar/estourar o tempo). */
    Out capture(String url, long startMs, long durMs, File out, long timeoutMs) {
        Out r = new Out();
        if (out.exists()) out.delete();
        if (cancelled) { r.end = "cancelado"; return r; }
        LibVLC v = instance();
        Media media = new Media(v, Uri.parse(url));
        media.addOption(":no-video");
        media.addOption(":network-caching=600");
        media.addOption(":http-reconnect");
        media.addOption(":start-time=" + (startMs / 1000.0));
        media.addOption(":stop-time=" + ((startMs + durMs) / 1000.0));
        media.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=" + out.getAbsolutePath() + "}");
        MediaPlayer mp = new MediaPlayer(v);
        final CountDownLatch done = new CountDownLatch(1);
        curLatch = done;
        final String[] why = {"tempo esgotado"};
        mp.setEventListener(ev -> {
            switch (ev.type) {
                case MediaPlayer.Event.EndReached: why[0] = "fim"; done.countDown(); break;
                case MediaPlayer.Event.EncounteredError: why[0] = "erro"; done.countDown(); break;
                case MediaPlayer.Event.Stopped: if (!"fim".equals(why[0])) why[0] = "parou"; done.countDown(); break;
                default: break;
            }
        });
        mp.setMedia(media);
        long t0 = System.nanoTime();
        try {
            mp.play();
            if (!cancelled) done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            r.wallS = (System.nanoTime() - t0) / 1e9;
            if (cancelled) why[0] = "cancelado";
            r.end = why[0];
            try { mp.stop(); } catch (Throwable ignored) {}
            mp.setEventListener(null);
            try { mp.release(); } catch (Throwable ignored) {}
            try { media.release(); } catch (Throwable ignored) {}
            curLatch = null;
        }
        // o arquivo só fecha direito depois do stop
        try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (out.exists()) {
            r.pcmS = Math.max(0, out.length() - 44) / 32000.0;
            r.ok = r.pcmS > 0 && !cancelled;
        }
        return r;
    }

    /** lê o WAV (mono s16 16 kHz) para short[]; pula o cabeçalho procurando o bloco "data" */
    static short[] readWav(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int o = 0, n;
            while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
        }
        int p = 12;
        while (p + 8 <= b.length) {
            boolean data = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            long sz = (b[p + 4] & 0xffL) | (b[p + 5] & 0xffL) << 8 | (b[p + 6] & 0xffL) << 16 | (b[p + 7] & 0xffL) << 24;
            p += 8;
            if (data) {
                int n = (int) Math.min((b.length - p) / 2, sz == 0 || sz > b.length ? Long.MAX_VALUE : sz / 2);
                short[] s = new short[n];
                for (int i = 0; i < n; i++) s[i] = (short) ((b[p + 2 * i] & 0xff) | (b[p + 2 * i + 1] << 8));
                return s;
            }
            p += (int) sz;
        }
        return new short[0];
    }
}
