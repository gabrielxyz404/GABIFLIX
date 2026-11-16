package io.github.gabrielxyz404.audiocap;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.util.Locale;

/**
 * Medição da captura de áudio no libVLC 3.6.0 (Android). Resultados em linhas JSON no logcat (tag CAPRES); os WAVs ficam em
 * /sdcard/Android/data/io.github.gabrielxyz404.audiocap/files/cap para o analisador do host (analyze.py) conferir o instante exato.
 *
 * Argumentos (am instrument -e): base (padrão http://10.0.2.2:8000), codecs (ac3,eac3,...), variants (sout,sout+novideo,afile,extractor),
 * starts (s, separados por vírgula), win (s), conc (1 = teste de convivência com a reprodução).
 */
@RunWith(AndroidJUnit4.class)
public class CaptureBenchTest {
    static final String TAG = "CAPRES";

    static String arg(String k, String d) {
        Bundle b = InstrumentationRegistry.getArguments();
        String v = b.getString(k);
        return v == null || v.isEmpty() ? d : v;
    }

    static void res(String json) { Log.i(TAG, json); System.out.println(TAG + " " + json); }

    @Test
    public void bench() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String base = arg("base", "http://10.0.2.2:8000");
        String[] codecs = arg("codecs", "ac3").split(",");
        String[] variants = arg("variants", "sout").split(",");
        String[] starts = arg("starts", "120,600,1100").split(",");
        int win = Integer.parseInt(arg("win", "90"));
        File dir = new File(ctx.getExternalFilesDir(null), "cap");
        dir.mkdirs();
        res("{\"info\":\"start\",\"abi\":\"" + android.os.Build.SUPPORTED_ABIS[0] + "\",\"sdk\":" + android.os.Build.VERSION.SDK_INT + ",\"cpus\":" + Runtime.getRuntime().availableProcessors() + "}");
        for (String variant : variants) {
            for (String codec : codecs) {
                String url = base + "/" + codec + ".mkv";
                LibVLC vlc = null;
                File afileOut = null;
                try {
                    if (variant.equals("afile")) {
                        // o aout afile escreve em UM arquivo por instância: uma instância por janela
                    } else if (!variant.equals("extractor")) {
                        vlc = AudioCapture.newInstance(ctx, null);
                    }
                    double wallSum = 0, cpuSum = 0;
                    for (String st : starts) {
                        long start = Long.parseLong(st.trim()) * 1000L;
                        File out = new File(dir, variant.replace('+', '_') + "_" + codec + "_" + st + ".wav");
                        String json;
                        if (variant.equals("extractor")) {
                            long t0 = System.nanoTime(), c0 = android.os.Process.getElapsedCpuTime();
                            String err = "";
                            ExtractorCapture.Out o = null;
                            try { o = ExtractorCapture.capture(url, start, win * 1000L, 240_000); } catch (Throwable t) { err = t.toString().replace('"', '\''); }
                            double wall = (System.nanoTime() - t0) / 1e9, cpu = (android.os.Process.getElapsedCpuTime() - c0) / 1000.0;
                            if (o != null && o.pcm.length > 0) {
                                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(o.pcm); }
                            }
                            json = String.format(Locale.ROOT, "{\"variant\":\"%s\",\"codec\":\"%s\",\"start_s\":%s,\"win_s\":%d,\"wall_s\":%.2f,\"cpu_s\":%.2f,\"pcm_s\":%.2f,\"rate\":%d,\"ch\":%d,\"mime\":\"%s\",\"raw\":true,\"err\":\"%s\"}",
                                    variant, codec, st, win, wall, cpu, o == null ? 0 : o.pcm.length / (2.0 * Math.max(1, o.channels) * Math.max(1, o.rate)),
                                    o == null ? 0 : o.rate, o == null ? 0 : o.channels, o == null ? "" : o.mime, err);
                        } else {
                            LibVLC use = vlc;
                            if (variant.equals("afile")) { afileOut = out; use = AudioCapture.newInstance(ctx, out.getAbsolutePath()); }
                            AudioCapture.Result r = AudioCapture.capture(use, url, start, win * 1000L, out, !variant.equals("afile"), variant.contains("novideo"), 600_000);
                            if (variant.equals("afile")) use.release();
                            wallSum += r.wallS; cpuSum += r.cpuS;
                            json = String.format(Locale.ROOT, "{\"variant\":\"%s\",\"codec\":\"%s\",\"start_s\":%s,\"win_s\":%d,\"wall_s\":%.2f,\"cpu_s\":%.2f,\"pcm_s\":%.2f,\"end\":\"%s\",\"xRT\":%.1f}",
                                    variant, codec, st, win, r.wallS, r.cpuS, r.pcmS, r.end, win / Math.max(r.wallS, 0.01));
                        }
                        res(json);
                    }
                    res(String.format(Locale.ROOT, "{\"summary\":true,\"variant\":\"%s\",\"codec\":\"%s\",\"windows\":%d,\"win_s\":%d,\"wall_total_s\":%.2f,\"cpu_total_s\":%.2f}", variant, codec, starts.length, win, wallSum, cpuSum));
                } catch (Throwable t) {
                    res("{\"variant\":\"" + variant + "\",\"codec\":\"" + codec + "\",\"fatal\":\"" + t.toString().replace('"', '\'') + "\"}");
                } finally {
                    if (vlc != null) vlc.release();
                }
            }
        }
        if (arg("conc", "0").equals("1")) conc(ctx, base);
        res("{\"info\":\"fim\"}");
    }

    /** convivência: toca o vídeo (instância 1, só áudio) por ~24 s sem e com captura na instância 2; compara o avanço do relógio e as paradas (buffering) */
    void conc(Context ctx, String base) throws Exception {
        File dir = new File(ctx.getExternalFilesDir(null), "cap");
        for (int pass = 0; pass < 2; pass++) {
            boolean withCap = pass == 1;
            java.util.ArrayList<String> o = new java.util.ArrayList<>();
            o.add("--no-lua");
            LibVLC play = new LibVLC(ctx.getApplicationContext(), o);
            Media m = new Media(play, android.net.Uri.parse(base + "/ac3.mkv"));
            m.addOption(":no-video");
            m.addOption(":network-caching=1500");
            m.addOption(":start-time=30");
            MediaPlayer mp = new MediaPlayer(play);
            final int[] buf = {0}, under = {0};
            mp.setEventListener(ev -> { if (ev.type == MediaPlayer.Event.Buffering) { buf[0]++; if (ev.getBuffering() < 100f) under[0]++; } });
            mp.setMedia(m);
            mp.play();
            Thread.sleep(4000);
            long t0 = System.nanoTime(), p0 = mp.getTime();
            final LibVLC cap = AudioCapture.newInstance(ctx, null);
            Thread th = null;
            final double[] capWall = {0};
            if (withCap) {
                th = new Thread(() -> {
                    for (long s : new long[]{300, 700}) {
                        AudioCapture.Result r = AudioCapture.capture(cap, base + "/ac3.mkv", s * 1000, 60_000, new File(dir, "conc_" + s + ".wav"), true, false, 300_000);
                        capWall[0] += r.wallS;
                    }
                });
                th.start();
            }
            Thread.sleep(20000);
            long t1 = System.nanoTime(), p1 = mp.getTime();
            if (th != null) th.join(120_000);
            res(String.format(Locale.ROOT, "{\"conc\":true,\"with_capture\":%b,\"wall_s\":%.2f,\"play_advance_s\":%.2f,\"buffering_events\":%d,\"buffering_below100\":%d,\"capture_wall_s\":%.2f}",
                    withCap, (t1 - t0) / 1e9, (p1 - p0) / 1000.0, buf[0], under[0], capWall[0]));
            mp.stop(); mp.release(); m.release(); play.release(); cap.release();
        }
    }
}
