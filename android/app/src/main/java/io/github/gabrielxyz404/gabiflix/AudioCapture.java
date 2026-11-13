package io.github.gabrielxyz404.gabiflix;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captura um trecho do áudio do vídeo com um SEGUNDO player do libVLC (sem vídeo, sem som no alto-falante):
 * decodifica qualquer codec (AAC, AC3/EAC3, DTS, TrueHD...) por software e escreve PCM 16 bits mono em arquivo.
 * Modos (o primeiro que funcionar fica):
 *  wav   = sout transcode (s16l mono 8 kHz) + mux wav
 *  raw   = sout transcode + mux dummy (PCM cru)
 *  afile = saída de áudio "audiofile" do VLC (taxa e canais do original, mono)
 * Tudo protegido: nenhum erro/travamento aqui derruba o player; falhou = devolve o motivo.
 */
final class AudioCapture {
    private AudioCapture() {}

    /** só "afile": é o único modo com tempo exato (a saída de áudio passa pelo player normal, que respeita start-time/stop-time com precisão
     * de ~20 ms; o sout/transcode ignora a busca e começa em outro ponto, medido com o vídeo de varredura) */
    static final String[] MODES = {"afile"};
    static final int RATE = 8000;

    static final class Out {
        String mode = "";
        AudioAlign.Pcm pcm;
        long tookMs;
        long fileBytes;
        double pcmSec;
        long readBytes = -1;
        String err = "";
        boolean ended;
        /** amostras {ms desde o play, bytes do arquivo, tempo do player} a cada ~25 ms (diagnóstico de tempo) */
        ArrayList<long[]> samples = new ArrayList<>();
        boolean ok() { return pcm != null && pcm.x != null && pcm.x.length > 0 && err.isEmpty(); }
    }

    private static void log(String s) { SubtitleSync.log("[áudio] " + s); }

    /** captura [startMs, startMs+durMs) do vídeo; bloqueia até acabar, estourar o tempo ou cancelar (nunca lança) */
    static Out capture(Context ctx, String url, long startMs, long durMs, File dir, String name, String mode, int audioTrackId,
                       AtomicBoolean cancel, long timeoutMs, boolean verbose) {
        return capture(ctx, url, startMs, durMs, dir, name, mode, audioTrackId, cancel, timeoutMs, verbose, "std");
    }

    /** variant: std (start-time/stop-time), fast (+input-fast-seek), seek (play e depois setTime), paused (começa pausado, setTime, play), runtime (start-time + run-time) */
    static Out capture(Context ctx, String url, long startMs, long durMs, File dir, String name, String mode, int audioTrackId,
                       AtomicBoolean cancel, long timeoutMs, boolean verbose, String variant) {
        final Out out = new Out();
        out.mode = mode;
        long t0 = System.currentTimeMillis();
        LibVLC lv = null;
        MediaPlayer mp = null;
        File f = new File(dir, name + (mode.equals("raw") ? ".pcm" : ".wav"));
        try {
            dir.mkdirs();
            f.delete();
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--no-video");
            opts.add("--no-spu");
            opts.add("--http-reconnect");
            if (verbose) opts.add("-vv");
            if (mode.equals("afile")) {
                opts.add("--aout=afile");
                opts.add("--audiofile-file=" + f.getAbsolutePath());
                opts.add("--audiofile-wav");
                opts.add("--audiofile-channels=1");
            }
            lv = new LibVLC(ctx, opts);
            mp = new MediaPlayer(lv);
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean err = new AtomicBoolean(false), ended = new AtomicBoolean(false);
            mp.setEventListener(ev -> {
                switch (ev.type) {
                    case MediaPlayer.Event.EndReached: ended.set(true); done.countDown(); break;
                    case MediaPlayer.Event.EncounteredError: err.set(true); done.countDown(); break;
                    default: break;
                }
            });
            Media m = new Media(lv, Uri.parse(url));
            m.setHWDecoderEnabled(false, false);
            m.addOption(":no-video");
            m.addOption(":no-spu");
            m.addOption(":no-sub-autodetect-file");
            m.addOption(":network-caching=600");
            boolean viaSetTime = variant.equals("seek") || variant.equals("paused");
            if (!viaSetTime) m.addOption(":start-time=" + String.format(Locale.ROOT, "%.3f", startMs / 1000.0));
            if (variant.equals("runtime")) m.addOption(":run-time=" + String.format(Locale.ROOT, "%.3f", durMs / 1000.0));
            else if (!viaSetTime) m.addOption(":stop-time=" + String.format(Locale.ROOT, "%.3f", (startMs + durMs) / 1000.0));
            if (variant.equals("fast")) m.addOption(":input-fast-seek");
            if (variant.equals("paused")) m.addOption(":start-paused");
            if (audioTrackId >= 0) m.addOption(":audio-track-id=" + audioTrackId);
            if (!mode.equals("afile")) {
                String mux = mode.equals("wav") ? "wav" : "dummy";
                m.addOption(":no-sout-video");
                m.addOption(":no-sout-spu");
                m.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=" + RATE + "}:std{access=file,mux=" + mux + ",dst=" + f.getAbsolutePath() + "}");
                m.addOption(":sout-keep");
            }
            mp.setMedia(m);
            m.release();
            mp.play();
            if (viaSetTime) {
                Thread.sleep(variant.equals("paused") ? 1500 : 300);
                mp.setTime(startMs);
                if (variant.equals("paused")) { Thread.sleep(500); mp.play(); }
            }
            // espera: terminou (evento), erro, tamanho esperado alcançado, cancelado ou tempo esgotado
            long expect = (long) (durMs / 1000.0 * (mode.equals("afile") ? 0 : RATE * 2)) * (viaSetTime ? 4 : 1);
            long deadline = System.currentTimeMillis() + timeoutMs;
            long lastSize = -1, lastGrow = System.currentTimeMillis();
            boolean finished = false;
            while (System.currentTimeMillis() < deadline) {
                if (cancel != null && cancel.get()) { out.err = "cancelado"; break; }
                if (done.await(25, TimeUnit.MILLISECONDS)) { finished = true; break; }
                long sz = f.length();
                try { if (out.samples.size() < 4000) out.samples.add(new long[]{System.currentTimeMillis() - t0, sz, mp.getTime()}); } catch (Throwable ignored) {}
                if (sz != lastSize) { lastSize = sz; lastGrow = System.currentTimeMillis(); }
                if (expect > 0 && sz >= expect + 4096) { finished = true; break; }
                // começou a escrever e parou de crescer há 6 s: acabou (stop-time sem evento)
                if (sz > 100000 && System.currentTimeMillis() - lastGrow > 6000) { finished = true; break; }
            }
            if (!finished && out.err.isEmpty()) out.err = "tempo esgotado (" + (timeoutMs / 1000) + " s)";
            out.ended = ended.get();
            if (err.get() && out.err.isEmpty()) out.err = "erro do VLC ao decodificar";
            out.readBytes = readBytes(mp);
            // para e solta: o arquivo só é finalizado (cabeçalho WAV) depois do stop
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.setEventListener((MediaPlayer.EventListener) null); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
            try { lv.release(); } catch (Throwable ignored) {}
            lv = null;
            Thread.sleep(150);
            out.fileBytes = f.length();
            if (out.err.isEmpty() || out.fileBytes > 20000) {
                byte[] data = readAll(f);
                if (data != null && data.length > 100) {
                    AudioAlign.Pcm p = AudioAlign.parseWav(data, mode.equals("afile") ? 44100 : RATE);
                    if (p.x != null && p.x.length > 0) {
                        out.pcm = p;
                        out.pcmSec = p.x.length / (double) p.rate;
                        if (!out.err.isEmpty()) out.err = ""; // tinha dados bons: o aviso de tempo não vale
                    } else if (out.err.isEmpty()) out.err = "arquivo sem áudio (" + p.note + ")";
                } else if (out.err.isEmpty()) out.err = "arquivo vazio (" + out.fileBytes + " bytes)";
            }
        } catch (Throwable t) {
            out.err = "falha " + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        } finally {
            final MediaPlayer fmp = mp;
            final LibVLC flv = lv;
            if (fmp != null || flv != null) {
                // algo ficou preso: solta em outra thread para não travar quem chamou
                Thread th = new Thread(() -> {
                    try { if (fmp != null) { fmp.stop(); fmp.release(); } } catch (Throwable ignored) {}
                    try { if (flv != null) flv.release(); } catch (Throwable ignored) {}
                });
                th.setDaemon(true);
                th.start();
            }
            try { f.delete(); } catch (Throwable ignored) {}
            out.tookMs = System.currentTimeMillis() - t0;
        }
        log(String.format(Locale.ROOT, "janela %s modo=%s início=%.1fs dur=%.1fs: %s, %d ms, arquivo %d B, áudio %.2f s%s",
                name, mode, startMs / 1000.0, durMs / 1000.0, out.err.isEmpty() ? "ok" : out.err, out.tookMs, out.fileBytes, out.pcmSec,
                out.readBytes >= 0 ? ", lido da rede " + out.readBytes + " B" : ""));
        return out;
    }

    /** bytes lidos da rede por este player (Media.Stats.readBytes), -1 se não disponível; por reflexão para não quebrar a compilação */
    private static long readBytes(MediaPlayer mp) {
        Object media = null;
        try {
            media = mp.getClass().getMethod("getMedia").invoke(mp);
            if (media == null) return -1;
            Object st = media.getClass().getMethod("getStats").invoke(media);
            if (st == null) return -1;
            for (String fld : new String[]{"readBytes", "demuxReadBytes"}) {
                try { long v = ((Number) st.getClass().getField(fld).get(st)).longValue(); if (v > 0) return v; } catch (Throwable ignored) {}
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            try { if (media != null) media.getClass().getMethod("release").invoke(media); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readAll(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream((int) Math.max(1024, Math.min(f.length(), 40_000_000)));
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > 40_000_000) break; }
            return buf.toByteArray();
        } catch (Throwable t) { return null; }
    }
}
