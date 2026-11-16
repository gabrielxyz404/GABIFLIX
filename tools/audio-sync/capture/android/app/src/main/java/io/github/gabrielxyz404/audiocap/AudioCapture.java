package io.github.gabrielxyz404.audiocap;

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
 * Captura de áudio do FramePlay para o alinhador por áudio: PCM mono 16 kHz s16 de uma janela [startMs, startMs+durMs) de um vídeo em HTTP,
 * usando uma SEGUNDA instância do libVLC (a do player não é tocada), sem tocar som e sem acompanhar o relógio de reprodução.
 *
 * Método principal (SOUT): a mídia vai para uma cadeia de saída "transcode -> WAV em arquivo". Como não há saída de áudio, o VLC não espera o relógio:
 * lê e decodifica o mais rápido que a rede e a CPU deixam (dezenas de vezes o tempo real) e o corte é exato (:start-time / :stop-time).
 * Método alternativo (AFILE): aout "afile" (grava o WAV, mas segue o relógio: tempo real).
 */
public final class AudioCapture {
    private AudioCapture() {}

    public static final class Result {
        public boolean ok;
        public String end = "?";
        public double wallS, cpuS, pcmS;
        public File file;
    }

    /** instância do libVLC só para captura (rodar APENAS uma captura por vez em cada instância) */
    public static LibVLC newInstance(Context ctx, String aoutFileOrNull) {
        ArrayList<String> o = new ArrayList<>();
        o.add("--no-lua");
        o.add("--no-osd");
        o.add("--no-spu");
        o.add("--no-sub-autodetect-file");
        if (aoutFileOrNull != null) {
            o.add("--aout=afile");
            o.add("--audiofile-file=" + aoutFileOrNull);
            o.add("--audiofile-wav");
            o.add("--audiofile-channels=1");
        }
        return new LibVLC(ctx.getApplicationContext(), o);
    }

    /** Bloqueia até terminar (chame fora da thread principal). */
    public static Result capture(LibVLC vlc, String url, long startMs, long durMs, File out, boolean sout, boolean noVideo, long timeoutMs) {
        Result r = new Result();
        r.file = out;
        if (out.exists()) out.delete();
        Media media = new Media(vlc, Uri.parse(url));
        if (noVideo) media.addOption(":no-video");
        media.addOption(":network-caching=600");
        media.addOption(":start-time=" + (startMs / 1000.0));
        media.addOption(":stop-time=" + ((startMs + durMs) / 1000.0));
        if (sout) {
            media.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=" + out.getAbsolutePath() + "}");
        }
        MediaPlayer mp = new MediaPlayer(vlc);
        final CountDownLatch done = new CountDownLatch(1);
        final String[] why = {"timeout"};
        mp.setEventListener(ev -> {
            switch (ev.type) {
                case MediaPlayer.Event.EndReached: why[0] = "ended"; done.countDown(); break;
                case MediaPlayer.Event.EncounteredError: why[0] = "error"; done.countDown(); break;
                case MediaPlayer.Event.Stopped: if (!"ended".equals(why[0])) why[0] = "stopped"; done.countDown(); break;
                default: break;
            }
        });
        mp.setMedia(media);
        long t0 = System.nanoTime();
        long cpu0 = android.os.Process.getElapsedCpuTime();
        mp.play();
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        r.wallS = (System.nanoTime() - t0) / 1e9;
        r.cpuS = (android.os.Process.getElapsedCpuTime() - cpu0) / 1000.0;
        r.end = why[0];
        mp.stop();
        mp.setEventListener(null);
        mp.release();
        media.release();
        // o arquivo só fecha direito depois do stop
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        if (out.exists()) {
            r.pcmS = Math.max(0, out.length() - 44) / 32000.0;
            r.ok = r.pcmS > 0;
        }
        return r;
    }

    /** lê o WAV (mono s16 16 kHz) para short[]; pula o cabeçalho procurando o bloco "data" */
    public static short[] readWav(File f) throws IOException {
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
