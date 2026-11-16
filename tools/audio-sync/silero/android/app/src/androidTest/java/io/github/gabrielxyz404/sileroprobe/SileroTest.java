package io.github.gabrielxyz404.sileroprobe;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.gabrielxyz404.gabiflix.audiosync.SileroVad;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Roda o Silero (ONNX Runtime Android) no WAV de teste (assets), grava as probabilidades (float32, 100 Hz) e o tempo. Logcat: tag SILERO. */
@RunWith(AndroidJUnit4.class)
public class SileroTest {
    static byte[] asset(Context c, String n) throws Exception {
        try (InputStream in = c.getAssets().open(n); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            return o.toByteArray();
        }
    }

    @Test
    public void silero() throws Exception {
        Context tc = InstrumentationRegistry.getInstrumentation().getContext();
        Context ac = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] model = asset(tc, "silero_vad.onnx");
        byte[] w = asset(tc, "test.wav");
        int p = 12, off = -1, len = 0;
        while (p + 8 <= w.length) {
            int sz = ByteBuffer.wrap(w, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (w[p] == 'd' && w[p + 1] == 'a' && w[p + 2] == 't' && w[p + 3] == 'a') { off = p + 8; len = Math.min(sz, w.length - off); break; }
            p += 8 + sz + (sz & 1);
        }
        short[] pcm = new short[len / 2];
        ByteBuffer.wrap(w, off, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        long cpu0 = android.os.Process.getElapsedCpuTime();
        long t0 = System.nanoTime();
        float[] pr;
        try (SileroVad v = new SileroVad(model)) {
            pr = v.speechProb(pcm, pcm.length, 16000);
        }
        double wall = (System.nanoTime() - t0) / 1e9, cpu = (android.os.Process.getElapsedCpuTime() - cpu0) / 1000.0;
        ByteBuffer bb = ByteBuffer.allocate(pr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : pr) bb.putFloat(f);
        File o = new File(ac.getFilesDir(), "silero.f32");
        try (FileOutputStream fo = new FileOutputStream(o)) { fo.write(bb.array()); }
        String line = String.format(java.util.Locale.ROOT, "{\"abi\":\"%s\",\"audio_s\":%.1f,\"wall_s\":%.2f,\"cpu_s\":%.2f,\"xRT\":%.1f,\"frames\":%d}",
                android.os.Build.SUPPORTED_ABIS[0], pcm.length / 16000.0, wall, cpu, pcm.length / 16000.0 / wall, pr.length);
        Log.i("SILERO", line);
    }
}
