import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Um filme do corpus (pasta montada por corpus.py): áudio 16 kHz, trilhas de VAD de referência, legendas e verdade de campo. */
final class Film {
    final String slug;
    final File dir;
    long durationMs;
    private RandomAccessFile wav;
    private long dataOff, dataLen;
    final ArrayList<Sub> subs = new ArrayList<>();
    private final HashMap<String, byte[]> tracks = new HashMap<>();

    static final class Sub {
        String name, lang;
        boolean truth;
        double agreeMed, agreeP90;
        Cue[] orig, truthCues;
        long[][] pairs; // {orig_start, truth_start}
        boolean pt() { return name.startsWith("pt"); }
    }

    Film(File dir) throws IOException {
        this.dir = dir;
        this.slug = dir.getName();
        File w = new File(dir, "audio16k.wav");
        wav = new RandomAccessFile(w, "r");
        // procura o chunk "data"
        byte[] h = new byte[12];
        wav.readFully(h);
        long pos = 12;
        while (true) {
            wav.seek(pos);
            byte[] ch = new byte[8];
            wav.readFully(ch);
            long sz = (ch[4] & 0xFFL) | ((ch[5] & 0xFFL) << 8) | ((ch[6] & 0xFFL) << 16) | ((ch[7] & 0xFFL) << 24);
            if (ch[0] == 'd' && ch[1] == 'a' && ch[2] == 't' && ch[3] == 'a') {
                dataOff = pos + 8;
                dataLen = Math.min(sz, wav.length() - dataOff);
                break;
            }
            pos += 8 + sz + (sz & 1);
        }
        durationMs = dataLen / 2 * 1000 / 16000;
        loadSubs();
    }

    private void loadSubs() throws IOException {
        File t = new File(dir, "truth.tsv");
        if (!t.exists()) return;
        List<String> lines = Files.readAllLines(t.toPath());
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t");
            Sub s = new Sub();
            s.name = c[0]; s.lang = c[1]; s.truth = c[2].equals("1");
            s.agreeMed = Double.parseDouble(c[7]); s.agreeP90 = Double.parseDouble(c[8]);
            File o = new File(dir, "subs/" + s.name + ".srt");
            if (!o.exists()) continue;
            s.orig = Cue.parseSrt(new String(Files.readAllBytes(o.toPath()), StandardCharsets.UTF_8));
            File ff = new File(dir, "subs/" + s.name + ".ffs.srt");
            if (ff.exists()) s.truthCues = Cue.parseSrt(new String(Files.readAllBytes(ff.toPath()), StandardCharsets.UTF_8));
            File pf = new File(dir, "subs/" + s.name + ".pairs.tsv");
            if (pf.exists()) {
                List<String> pl = Files.readAllLines(pf.toPath());
                s.pairs = new long[pl.size()][2];
                int n = 0;
                for (String l : pl) { String[] q = l.split("\t"); if (q.length >= 2) { s.pairs[n][0] = Long.parseLong(q[0]); s.pairs[n][1] = Long.parseLong(q[1]); n++; } }
                s.pairs = Arrays.copyOf(s.pairs, n);
            }
            if (s.orig.length >= 40) subs.add(s);
        }
    }

    /** PCM s16 16 kHz de [startMs, startMs+durMs) */
    synchronized short[] pcm(long startMs, long durMs) throws IOException {
        long s0 = Math.max(0, startMs) * 16;
        long n = Math.min(durMs * 16, dataLen / 2 - s0);
        if (n <= 0) return new short[0];
        byte[] b = new byte[(int) (n * 2)];
        wav.seek(dataOff + s0 * 2);
        wav.readFully(b);
        short[] out = new short[(int) n];
        for (int i = 0; i < n; i++) out[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        return out;
    }

    /** trilha de referência (100 Hz, 1 byte): vad_webrtc_m3.u8, vad_silero.u8 ... ; null se não existir */
    synchronized float[] track(String name, long startMs, long durMs) throws IOException {
        byte[] t = tracks.get(name);
        if (t == null) {
            File f = new File(dir, name + ".u8");
            if (!f.exists()) return null;
            t = Files.readAllBytes(f.toPath());
            tracks.put(name, t);
        }
        int a = (int) (startMs / 10), n = (int) (durMs / 10);
        float[] p = new float[n];
        for (int i = 0; i < n && a + i < t.length; i++) p[i] = (t[a + i] & 0xFF) / 255f;
        return p;
    }

    boolean hasTrack(String name) { return new File(dir, name + ".u8").exists(); }
}
