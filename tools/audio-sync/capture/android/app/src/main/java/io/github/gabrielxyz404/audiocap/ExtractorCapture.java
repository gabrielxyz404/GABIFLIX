package io.github.gabrielxyz404.audiocap;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Alternativa sem libVLC: MediaExtractor + MediaCodec (só serve para áudios que o aparelho decodifica: AAC, FLAC, às vezes AC3/EAC3 com licença Dolby).
 * Devolve PCM s16 intercalado como veio do decodificador (taxa/canais em out[0], out[1]) para o chamador reamostrar/misturar.
 */
public final class ExtractorCapture {
    private ExtractorCapture() {}

    public static final class Out { public byte[] pcm; public int rate, channels; public String mime; public long firstPtsUs = -1; }

    public static Out capture(String url, long startMs, long durMs, long timeoutMs) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(url, new HashMap<String, String>());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) throw new IllegalStateException("sem faixa de áudio");
        ex.selectTrack(track);
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();
        Out o = new Out();
        o.mime = fmt.getString(MediaFormat.KEY_MIME);
        o.rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        o.channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long endUs = (startMs + durMs) * 1000, t0 = System.nanoTime();
        boolean inEos = false, outEos = false;
        while (!outEos && (System.nanoTime() - t0) / 1_000_000 < timeoutMs) {
            if (!inEos) {
                int ii = codec.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(ii);
                    int n = ex.readSampleData(ib, 0);
                    long pts = ex.getSampleTime();
                    if (n < 0 || pts > endUs) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                    else { codec.queueInputBuffer(ii, 0, n, pts, 0); ex.advance(); }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 10_000);
            if (oi >= 0) {
                if (info.size > 0 && info.presentationTimeUs >= startMs * 1000 && info.presentationTimeUs < endUs) {
                    if (o.firstPtsUs < 0) o.firstPtsUs = info.presentationTimeUs;
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    byte[] chunk = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(chunk);
                    bos.write(chunk);
                }
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat nf = codec.getOutputFormat();
                o.rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                o.channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop(); codec.release(); ex.release();
        o.pcm = bos.toByteArray();
        return o;
    }
}
