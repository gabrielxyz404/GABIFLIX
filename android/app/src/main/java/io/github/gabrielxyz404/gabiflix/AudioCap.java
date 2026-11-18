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
        /** bytes lidos da rede pelo libVLC nesta janela (-1 = o libVLC não disse) */
        long bytes = -1;
        /** qual faixa de áudio a segunda instância usou (para o registro) */
        String track = "?";
    }

    private final Context ctx;
    /** UM libVLC para todas as capturas (cada captura é uma mídia/player próprio nele) */
    private static LibVLC vlc;
    private static int users;
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

    private LibVLC instance() {
        synchronized (AudioCap.class) {
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
    }

    static void close() {
        synchronized (AudioCap.class) {
            if (vlc != null) { try { vlc.release(); } catch (Throwable ignored) {} vlc = null; }
        }
    }

    private static void log(String m) { SubtitleSync.log("AUDIOSYNC " + m); }

    /** bytes que o libVLC leu da rede para esta mídia (reflexão: o nome do campo muda entre versões); -1 se não sabe */
    private static long bytesRead(Media m) {
        try {
            Object st = m.getClass().getMethod("getStats").invoke(m);
            if (st == null) return -1;
            for (String f : new String[]{"readBytes", "demuxReadBytes"}) {
                try {
                    long v = ((Number) st.getClass().getField(f).get(st)).longValue();
                    if (v > 0) return v;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static String trackInfo(MediaPlayer mp, int wanted) {
        try {
            int cur = mp.getAudioTrack();
            MediaPlayer.TrackDescription[] t = mp.getAudioTracks();
            StringBuilder b = new StringBuilder("usou a faixa " + cur + (wanted >= 0 ? " (pedida " + wanted + ")" : " (a do arquivo)") + " de [");
            if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id != -1) b.append(d.id).append(':').append(d.name).append("; ");
            return b.append(']').toString();
        } catch (Throwable e) { return "faixa ?"; }
    }

    /**
     * Bloqueia até terminar (ou cancelar). Não há mais um tempo fixo por janela: vale enquanto o arquivo de PCM CRESCE (uma busca lenta num
     * MKV remoto ou uma rede lenta não derrubam a captura); falha só se ficar parado por muito tempo ou passar do limite total.
     * @param audioTrackId faixa de áudio a ouvir (a que está tocando no player); -1 = a que o arquivo marca como padrão
     */
    Out capture(String url, long startMs, long durMs, File out, long timeoutMs, int audioTrackId) {
        Out r = new Out();
        if (out.exists()) out.delete();
        if (cancelled) { r.end = "cancelado"; return r; }
        LibVLC v = instance();
        Media media = new Media(v, Uri.parse(url));
        media.addOption(":no-video");
        // sem :input-fast-seek: começa no quadro-chave anterior, a janela sai 5-10 s mais longa e deslocada (recusava legenda certa)
        media.addOption(":no-spu");
        media.addOption(":network-caching=600");
        media.addOption(":http-reconnect");
        if (audioTrackId >= 0) media.addOption(":audio-track-id=" + audioTrackId);
        media.addOption(":start-time=" + (startMs / 1000.0));
        media.addOption(":stop-time=" + ((startMs + durMs) / 1000.0));
        media.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=" + out.getAbsolutePath() + "}");
        MediaPlayer mp = new MediaPlayer(v);
        final CountDownLatch done = new CountDownLatch(1);
        curLatch = done;
        final String[] why = {"tempo esgotado"}, fin = {null};
        mp.setEventListener(ev -> {
            switch (ev.type) {
                case MediaPlayer.Event.EndReached: why[0] = "fim"; done.countDown(); break;
                case MediaPlayer.Event.EncounteredError: why[0] = "erro do libVLC"; done.countDown(); break;
                case MediaPlayer.Event.Stopped: if (!"fim".equals(why[0])) why[0] = "parou"; done.countDown(); break;
                default: break;
            }
        });
        mp.setMedia(media);
        long t0 = System.nanoTime();
        try {
            mp.play();
            long lastGrow = t0, lastLen = 0;
            boolean tracked = false;
            while (!cancelled) {
                if (done.await(1000, TimeUnit.MILLISECONDS)) break;
                long now = System.nanoTime();
                long len = out.exists() ? out.length() : 0;
                if (len > lastLen) { lastLen = len; lastGrow = now; }
                if (!tracked && now - t0 > 3_000_000_000L) { tracked = true; r.track = trackInfo(mp, audioTrackId); }
                // antes do primeiro dado: a busca no arquivo remoto pode demorar (60 s); depois, 30 s parado = rede ou servidor travou
                long idleLimit = (lastLen > 44 ? 30_000L : 60_000L) * 1_000_000L;
                if (now - lastGrow > idleLimit) { why[0] = lastLen > 44 ? "a leitura parou" : "sem dados do servidor"; break; }
                if (now - t0 > timeoutMs * 1_000_000L) { why[0] = "tempo esgotado"; break; }
            }
            fin[0] = why[0];   // o Stopped que o stop() dispara a seguir não pode trocar o motivo
            if (!tracked) r.track = trackInfo(mp, audioTrackId);
            r.bytes = bytesRead(media);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            r.wallS = (System.nanoTime() - t0) / 1e9;
            r.end = cancelled ? "cancelado" : fin[0] != null ? fin[0] : why[0];
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
