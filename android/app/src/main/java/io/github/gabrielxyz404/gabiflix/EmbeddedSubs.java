package io.github.gabrielxyz404.gabiflix;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Texto da legenda EMBUTIDA no arquivo (faixa de texto de um MKV/WebM), lido SEM baixar o filme: o índice do Matroska (Cues) aponta para cada fala da faixa
 * (cluster + posição dentro dele) e cada fala é lida com um pedido pequeno (Range) ao arquivo ou ao servidor de streaming. Com o texto e os tempos o app
 * desenha a legenda ele mesmo (SubtitleOverlay) e a sincronização pelo áudio passa a funcionar como numa legenda baixada. É a mesma leitura do Mac (EmbeddedSubs.swift).
 *
 * Memória baixa (Fire TV Stick 4K Max: ARM de 32 bits, ~1,6 GB de RAM): o arquivo nunca é carregado; o índice (Cues) é lido em pedaços de 128 KB e só as
 * entradas da faixa escolhida ficam guardadas (quatro números por fala); cada fala é um pedido de 4 KB; poucas conexões ao mesmo tempo.
 *
 * Limites (ditos ao usuário, nunca em silêncio): só MKV/WebM; só faixas de TEXTO (SRT, ASS/SSA; as de imagem, PGS/VobSub, não têm texto); o arquivo precisa
 * ter as falas da faixa no índice (o mkvmerge e o ffmpeg põem). Sem isso a razão exata volta em Out.why.
 */
final class EmbeddedSubs {
    private EmbeddedSubs() {}

    /** fonte de bytes: até len bytes a partir de pos (menos no fim do arquivo); null se falhou (a razão fica em failure) */
    interface Src {
        byte[] read(long pos, int len);
        String failure();
        void close();
    }

    interface Cancel { boolean is(); }
    interface Say { void on(String s); }

    static final class Out {
        String srt;
        int cues;
        String why = "";
        String codec = "";
        String track = "";
        long ms;
    }

    // ---------------------------------------------------------------- fontes

    static final class FileSrc implements Src {
        private final RandomAccessFile f;
        private String fail = "";
        FileSrc(File file) throws IOException { f = new RandomAccessFile(file, "r"); }
        @Override public synchronized byte[] read(long pos, int len) {
            try {
                f.seek(Math.max(0, pos));
                byte[] b = new byte[len];
                int n = 0;
                while (n < len) { int r = f.read(b, n, len - n); if (r < 0) break; n += r; }
                if (n == 0) return null;
                return n == len ? b : Arrays.copyOf(b, n);
            } catch (IOException e) { fail = String.valueOf(e); return null; }
        }
        @Override public String failure() { return fail; }
        @Override public void close() { try { f.close(); } catch (IOException ignored) {} }
    }

    /** HTTP com Range (o servidor de streaming do Stremio, links diretos do debrid); segue redirecionamentos e lembra o destino final */
    static final class HttpSrc implements Src {
        private volatile String cur;
        private volatile String fail = "";
        HttpSrc(String url) { cur = url; }
        @Override public byte[] read(long pos, int len) {
            String u = cur;
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = null;
                boolean reuse = false;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(10_000);
                    c.setReadTimeout(20_000);
                    c.setRequestProperty("Range", "bytes=" + pos + "-" + (pos + len - 1));
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("User-Agent", "FramePlay");
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null) { fail = "redirecionamento sem destino"; return null; }
                        u = new URL(new URL(u), loc).toString();
                        continue;
                    }
                    if (code == 206 || (code == 200 && pos == 0)) {
                        if (!u.equals(cur)) cur = u;
                        InputStream in = c.getInputStream();
                        byte[] b = new byte[len];
                        int n = 0;
                        while (n < len) { int r = in.read(b, n, len - n); if (r < 0) break; n += r; }
                        reuse = code == 206 && c.getContentLength() >= 0 && c.getContentLength() <= len;   // a resposta toda foi lida: a conexão volta para o grupo
                        try { in.close(); } catch (IOException ignored) {}
                        if (n == 0) { fail = "resposta vazia"; return null; }
                        return n == len ? b : Arrays.copyOf(b, n);
                    }
                    fail = code == 200 ? "servidor sem suporte a Range" : code == 416 ? "além do fim do arquivo" : "HTTP " + code;
                    return null;
                } catch (IOException e) {
                    fail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    return null;
                } finally {
                    if (c != null && !reuse) { try { c.disconnect(); } catch (Throwable ignored) {} }
                }
            }
            fail = "redirecionamentos demais";
            return null;
        }
        @Override public String failure() { return fail; }
        @Override public void close() {}
    }

    /** a fonte certa para o endereço do filme (arquivo local ou HTTP); null se não for suportado */
    static Src source(String url) {
        if (url == null) return null;
        try {
            String l = url.toLowerCase(java.util.Locale.ROOT);
            if (l.startsWith("http://") || l.startsWith("https://")) return new HttpSrc(url);
            if (l.startsWith("file:")) return new FileSrc(new File(android.net.Uri.parse(url).getPath()));
            if (url.startsWith("/")) return new FileSrc(new File(url));
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------------------------------------------------------------- EBML

    private static final int ID_EBML = 0x1A45DFA3, ID_SEGMENT = 0x18538067, ID_SEEKHEAD = 0x114D9B74, ID_SEEK = 0x4DBB, ID_SEEK_ID = 0x53AB, ID_SEEK_POS = 0x53AC;
    private static final int ID_INFO = 0x1549A966, ID_SCALE = 0x2AD7B1, ID_TRACKS = 0x1654AE6B, ID_ENTRY = 0xAE, ID_NUMBER = 0xD7, ID_TYPE = 0x83, ID_CODEC = 0x86;
    private static final int ID_LANG = 0x22B59C, ID_LANG_BCP = 0x22B59D, ID_NAME = 0x536E, ID_FORCED = 0x55AA;
    private static final int ID_CUES = 0x1C53BB6B, ID_CUE_POINT = 0xBB, ID_CUE_TIME = 0xB3, ID_CUE_POS = 0xB7, ID_CUE_TRACK = 0xF7, ID_CUE_CLUSTER = 0xF1, ID_CUE_REL = 0xF0, ID_CUE_DUR = 0xB2;
    private static final int ID_CLUSTER = 0x1F43B675, ID_BLOCK_GROUP = 0xA0, ID_BLOCK = 0xA1, ID_SIMPLE = 0xA3, ID_BLOCK_DUR = 0x9B;

    private static final class Head { int id; long size; int hl; boolean unknown; }

    /** id (com os bits de tamanho, como no arquivo) em b[p]; devolve {valor, bytes} ou null */
    private static long[] readId(byte[] b, int p) {
        if (p < 0 || p >= b.length) return null;
        int f = b[p] & 0xFF, l = 1, m = 0x80;
        while (l <= 4 && (f & m) == 0) { l++; m >>= 1; }
        if (l > 4 || p + l > b.length) return null;
        long v = 0;
        for (int i = 0; i < l; i++) v = (v << 8) | (b[p + i] & 0xFF);
        return new long[]{v, l};
    }

    /** tamanho em b[p]: {valor, bytes usados, 1 se desconhecido} ou null */
    private static long[] readSize(byte[] b, int p) {
        if (p < 0 || p >= b.length) return null;
        int f = b[p] & 0xFF, l = 1, m = 0x80;
        while (l <= 8 && (f & m) == 0) { l++; m >>= 1; }
        if (l > 8 || p + l > b.length) return null;
        long v = f & (m - 1);
        for (int i = 1; i < l; i++) v = (v << 8) | (b[p + i] & 0xFF);
        long allOnes = (1L << (7 * l)) - 1;
        return new long[]{v, l, v == allOnes ? 1 : 0};
    }

    private static Head head(byte[] b, int p) {
        long[] id = readId(b, p);
        if (id == null) return null;
        long[] sz = readSize(b, p + (int) id[1]);
        if (sz == null) return null;
        Head h = new Head();
        h.id = (int) id[0];
        h.size = sz[0];
        h.hl = (int) (id[1] + sz[1]);
        h.unknown = sz[2] == 1;
        return h;
    }

    private static long uint(byte[] b, int p, int n) {
        long v = 0;
        for (int i = 0; i < Math.min(n, 8) && p + i < b.length; i++) v = (v << 8) | (b[p + i] & 0xFF);
        return v;
    }

    private static String str(byte[] b, int p, int n) {
        int e = Math.min(b.length, p + n);
        if (p >= e) return "";
        while (e > p && b[e - 1] == 0) e--;
        return new String(b, p, e - p, StandardCharsets.UTF_8);
    }

    /** filhos de um elemento já em memória: {id, posição do corpo, tamanho do corpo} */
    private static ArrayList<long[]> children(byte[] b, int from, int to) {
        ArrayList<long[]> out = new ArrayList<>();
        int p = from;
        int end = Math.min(to, b.length);
        while (p < end) {
            Head h = head(b, p);
            if (h == null) break;
            int body = p + h.hl;
            long sz = h.unknown ? end - body : Math.min(h.size, (long) (end - body));
            if (sz < 0) break;
            out.add(new long[]{h.id, body, sz});
            p = body + (int) sz;
        }
        return out;
    }

    private static final class Track {
        int number, type;
        String codec = "", lang = "", name = "";
        boolean forced;
        boolean isText() { return codec.startsWith("S_TEXT"); }
        boolean isPt() {
            String l = lang.toLowerCase(java.util.Locale.ROOT);
            if (l.equals("por") || l.equals("pob") || l.startsWith("pt")) return true;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("portug") || n.contains("brazil") || n.contains("brasil") || n.contains("pt-br");
        }
    }

    // ---------------------------------------------------------------- leitura

    /** pedaço do arquivo em memória (janela deslizante sobre o índice) */
    private static final class Win {
        final Src src;
        byte[] buf = new byte[0];
        long start = 0;
        Win(Src s) { src = s; }
        /** garante [pos, pos+need) na janela, lendo um pedaço de pelo menos `chunk` bytes; false se não deu */
        boolean ensure(long pos, int need, int chunk) {
            if (pos >= start && pos + need <= start + buf.length) return true;
            byte[] d = src.read(pos, Math.max(need, chunk));
            if (d == null || d.length < need) return false;
            buf = d;
            start = pos;
            return true;
        }
        int at(long pos) { return (int) (pos - start); }
    }

    /**
     * Lê a faixa de texto de ordem `ordinal` (entre as faixas de legenda do arquivo, na ordem do arquivo; o libVLC lista na mesma ordem); se ela não for
     * em português e houver uma única de texto em português, usa essa. cancel: true para parar; deadlineUptime: limite (SystemClock.uptimeMillis).
     */
    static Out extract(final Src src, int ordinal, final Cancel cancel, final long deadlineUptime, final int workers, final Say log) {
        final Out out = new Out();
        final long t0 = android.os.SystemClock.uptimeMillis();
        try {
            return extract0(src, ordinal, cancel, deadlineUptime, workers, log, out, t0);
        } catch (Throwable t) {
            out.srt = null;
            out.why = "erro ao ler a legenda do vídeo (" + t.getClass().getSimpleName() + ")";
            out.ms = android.os.SystemClock.uptimeMillis() - t0;
            return out;
        }
    }

    private static Out fail(Out out, long t0, String why) {
        out.srt = null;
        out.why = why;
        out.ms = android.os.SystemClock.uptimeMillis() - t0;
        return out;
    }

    private static Out extract0(final Src src, int ordinal, final Cancel cancel, final long deadlineUptime, int workers, final Say log, final Out out, final long t0) {
        byte[] b0 = src.read(0, 256 * 1024);
        if (b0 == null) return fail(out, t0, "não consegui ler o começo do arquivo (" + src.failure() + ")");
        Head eh = head(b0, 0);
        if (eh == null || eh.id != ID_EBML) return fail(out, t0, "o arquivo não é MKV (só MKV/WebM tem a legenda lida)");
        int segPos = (int) Math.min(Integer.MAX_VALUE, eh.size + eh.hl);
        Head sh = head(b0, segPos);
        if (sh == null || sh.id != ID_SEGMENT) return fail(out, t0, "MKV sem segmento");
        final long segStart = segPos + sh.hl;

        // elementos de nível 1 antes do primeiro cluster
        long tracksPos = -1, infoPos = -1, cuesPos = -1;
        long tracksLen = 0, infoLen = 0, cuesLen = 0;
        long seekTracks = -1, seekInfo = -1, seekCues = -1;
        long q = segStart;
        for (int n = 0; n < 64; n++) {
            byte[] hb = readAt(src, b0, q, 16);
            Head h = hb == null ? null : head(hb, 0);
            if (h == null || h.id == ID_CLUSTER || h.unknown) break;
            long body = q + h.hl;
            if (h.id == ID_SEEKHEAD) {
                byte[] bb = readAt(src, b0, body, (int) Math.min(h.size, 64 * 1024));
                if (bb != null) for (long[] e : children(bb, 0, bb.length)) {
                    if (e[0] != ID_SEEK) continue;
                    long sid = 0, spos = -1;
                    for (long[] c : children(bb, (int) e[1], (int) (e[1] + e[2]))) {
                        if (c[0] == ID_SEEK_ID) sid = uint(bb, (int) c[1], (int) c[2]);
                        if (c[0] == ID_SEEK_POS) spos = uint(bb, (int) c[1], (int) c[2]);
                    }
                    if (spos >= 0 && sid == ID_TRACKS) seekTracks = spos;
                    if (spos >= 0 && sid == ID_INFO) seekInfo = spos;
                    if (spos >= 0 && sid == ID_CUES) seekCues = spos;
                }
            } else if (h.id == ID_TRACKS) { tracksPos = body; tracksLen = h.size; }
            else if (h.id == ID_INFO) { infoPos = body; infoLen = h.size; }
            else if (h.id == ID_CUES) { cuesPos = body; cuesLen = h.size; }
            q = body + h.size;
        }
        if (tracksPos < 0 && seekTracks >= 0) {
            byte[] hb = src.read(segStart + seekTracks, 16);
            Head h = hb == null ? null : head(hb, 0);
            if (h != null && h.id == ID_TRACKS) { tracksPos = segStart + seekTracks + h.hl; tracksLen = h.size; }
        }
        if (infoPos < 0 && seekInfo >= 0) {
            byte[] hb = src.read(segStart + seekInfo, 16);
            Head h = hb == null ? null : head(hb, 0);
            if (h != null && h.id == ID_INFO) { infoPos = segStart + seekInfo + h.hl; infoLen = h.size; }
        }
        if (cuesPos < 0 && seekCues >= 0) {
            byte[] hb = src.read(segStart + seekCues, 16);
            Head h = hb == null ? null : head(hb, 0);
            if (h != null && h.id == ID_CUES) { cuesPos = segStart + seekCues + h.hl; cuesLen = h.size; }
        }
        byte[] tb = tracksPos < 0 ? null : readAt(src, b0, tracksPos, (int) Math.min(tracksLen, 4_000_000L));
        if (tb == null) return fail(out, t0, "MKV sem lista de faixas");

        long scaleNs = 1_000_000;
        if (infoPos >= 0) {
            byte[] ib = readAt(src, b0, infoPos, (int) Math.min(infoLen, 100_000L));
            if (ib != null) for (long[] e : children(ib, 0, ib.length)) if (e[0] == ID_SCALE) scaleNs = Math.max(1, uint(ib, (int) e[1], (int) e[2]));
        }

        ArrayList<Track> tracks = new ArrayList<>();
        for (long[] e : children(tb, 0, tb.length)) {
            if (e[0] != ID_ENTRY) continue;
            Track t = new Track();
            for (long[] c : children(tb, (int) e[1], (int) (e[1] + e[2]))) {
                int p = (int) c[1], l = (int) c[2];
                switch ((int) c[0]) {
                    case ID_NUMBER: t.number = (int) uint(tb, p, l); break;
                    case ID_TYPE: t.type = (int) uint(tb, p, l); break;
                    case ID_CODEC: t.codec = str(tb, p, l); break;
                    case ID_LANG: if (t.lang.isEmpty() || t.lang.equals("eng")) t.lang = str(tb, p, l); break;
                    case ID_LANG_BCP: t.lang = str(tb, p, l); break;
                    case ID_NAME: t.name = str(tb, p, l); break;
                    case ID_FORCED: t.forced = uint(tb, p, l) != 0; break;
                    default: break;
                }
            }
            tracks.add(t);
        }
        ArrayList<Track> subs = new ArrayList<>();
        for (Track t : tracks) if (t.type == 17) subs.add(t);
        if (subs.isEmpty()) return fail(out, t0, "o arquivo não tem faixas de legenda");
        Track pick = ordinal >= 0 && ordinal < subs.size() ? subs.get(ordinal) : null;
        if (pick == null || !pick.isPt()) {
            ArrayList<Track> pts = new ArrayList<>();
            for (Track t : subs) if (t.isPt() && t.isText()) pts.add(t);
            if (pts.size() == 1) pick = pts.get(0);
            else if (pick == null) {
                for (Track t : pts) if (!t.forced) { pick = t; break; }
                if (pick == null && !pts.isEmpty()) pick = pts.get(0);
            }
        }
        if (pick == null) return fail(out, t0, "não achei a faixa de legenda no arquivo");
        final Track tr = pick;
        out.codec = tr.codec;
        out.track = tr.name.isEmpty() ? tr.lang : tr.name;
        if (!tr.isText()) return fail(out, t0, "a legenda do vídeo é em imagem (" + tr.codec + "): não tem texto para ler");
        if (cuesPos < 0) return fail(out, t0, "o MKV não tem índice (Cues): não dá para achar as falas sem ler o filme todo");
        if (cuesLen > 96_000_000L) return fail(out, t0, "o índice do MKV é grande demais (" + (cuesLen >> 20) + " MB)");

        // ---- as falas da faixa no índice (Cues), em pedaços: só quatro números por fala ficam guardados
        long[] eT = new long[1024], eC = new long[1024], eR = new long[1024], eD = new long[1024];
        int ne = 0;
        Win w = new Win(src);
        long p = cuesPos, end = cuesPos + cuesLen;
        while (p < end) {
            if (cancel.is()) return fail(out, t0, "cancelado");
            if (!w.ensure(p, (int) Math.min(16, end - p), 128 * 1024)) return fail(out, t0, "não consegui ler o índice do MKV (" + src.failure() + ")");
            Head h = head(w.buf, w.at(p));
            if (h == null) break;
            long body = p + h.hl;
            if (h.unknown) break;
            if (h.id == ID_CUE_POINT && h.size <= 64 * 1024) {
                if (!w.ensure(body, (int) h.size, 128 * 1024)) return fail(out, t0, "não consegui ler o índice do MKV (" + src.failure() + ")");
                int bp = w.at(body);
                long time = 0;
                for (long[] c : children(w.buf, bp, bp + (int) h.size)) {
                    if (c[0] == ID_CUE_TIME) time = uint(w.buf, (int) c[1], (int) c[2]);
                    if (c[0] != ID_CUE_POS) continue;
                    int tn = 0;
                    long cluster = -1, rel = -1, dur = 0;
                    for (long[] x : children(w.buf, (int) c[1], (int) (c[1] + c[2]))) {
                        if (x[0] == ID_CUE_TRACK) tn = (int) uint(w.buf, (int) x[1], (int) x[2]);
                        if (x[0] == ID_CUE_CLUSTER) cluster = uint(w.buf, (int) x[1], (int) x[2]);
                        if (x[0] == ID_CUE_REL) rel = uint(w.buf, (int) x[1], (int) x[2]);
                        if (x[0] == ID_CUE_DUR) dur = uint(w.buf, (int) x[1], (int) x[2]);
                    }
                    if (tn == tr.number && cluster >= 0 && ne < 400_000) {
                        if (ne == eT.length) { int nl = ne * 2; eT = Arrays.copyOf(eT, nl); eC = Arrays.copyOf(eC, nl); eR = Arrays.copyOf(eR, nl); eD = Arrays.copyOf(eD, nl); }
                        eT[ne] = time; eC[ne] = cluster; eR[ne] = rel; eD[ne] = dur; ne++;
                    }
                }
            }
            p = body + h.size;
        }
        w.buf = new byte[0];
        log.on("EMBUTIDA faixa " + tr.number + " " + tr.codec + " lang=" + tr.lang + " falas no índice: " + ne);
        if (ne < 40) return fail(out, t0, ne == 0 ? "o índice do MKV não lista as falas da legenda (sem como achá-las sem ler o filme todo)" : "poucas falas no índice da legenda (" + ne + ")");
        if (cancel.is()) return fail(out, t0, "cancelado");

        // ---- cada fala: um pedido pequeno. O cabeçalho do cluster costuma ter o mesmo tamanho em todos: aprende no primeiro e confere em cada leitura
        final int count = ne;
        final long[] fT = eT, fC = eC, fR = eR, fD = eD;
        final long fScale = scaleNs;
        final int trackNo = tr.number;
        final boolean isAss = tr.codec.contains("ASS") || tr.codec.contains("SSA");
        final long[] gStart = new long[count], gEnd = new long[count];
        final String[] gText = new String[count];
        final int[] guessHl = {0};
        final AtomicInteger next = new AtomicInteger(), bad = new AtomicInteger();
        final boolean[] timedOut = {false};
        Thread[] th = new Thread[Math.max(1, workers)];
        for (int wi = 0; wi < th.length; wi++) {
            th[wi] = new Thread(() -> {
                int i;
                while ((i = next.getAndIncrement()) < count) {
                    if (cancel.is()) return;
                    if (android.os.SystemClock.uptimeMillis() > deadlineUptime) { timedOut[0] = true; return; }
                    boolean ok = false;
                    for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                        int hl;
                        if (attempt == 0 && guessHl[0] > 0) hl = guessHl[0];
                        else {
                            byte[] ch = src.read(segStart + fC[i], 16);
                            Head h = ch == null ? null : head(ch, 0);
                            if (h == null || h.id != ID_CLUSTER) break;
                            hl = h.hl;
                            if (guessHl[0] == 0) guessHl[0] = hl;
                        }
                        byte[] blk = src.read(segStart + fC[i] + hl + Math.max(0, fR[i]), 4096);
                        if (blk == null) continue;
                        Object[] r = parseBlock(blk, trackNo);
                        if (r == null) { if (attempt == 0) guessHl[0] = 0; continue; }
                        long start = fT[i] * fScale / 1_000_000L;
                        long bd = (Long) r[1];
                        long durTicks = bd >= 0 ? bd : (fD[i] > 0 ? fD[i] : 3000L * 1_000_000L / fScale);
                        gStart[i] = start;
                        gEnd[i] = start + Math.max(300, durTicks * fScale / 1_000_000L);
                        gText[i] = (String) r[0];
                        ok = true;
                    }
                    if (!ok) { if (bad.incrementAndGet() > 30 && bad.get() * 4 > count) return; }
                }
            }, "gf-embsubs");
            th[wi].setPriority(Thread.MIN_PRIORITY);
            th[wi].start();
        }
        for (Thread t : th) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return fail(out, t0, "cancelado"); } }
        if (cancel.is()) return fail(out, t0, "cancelado");
        if (timedOut[0]) return fail(out, t0, "a leitura da legenda do vídeo demorou demais (" + (android.os.SystemClock.uptimeMillis() - t0) / 1000 + " s)");
        if (bad.get() > 30 && bad.get() * 4 > count) return fail(out, t0, "o índice do MKV não bate com o conteúdo (" + bad.get() + " de " + count + " falas ilegíveis)");

        // ---- texto -> SRT
        ArrayList<Object[]> items = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (gText[i] == null) continue;
            String t = gText[i].replace("\r\n", "\n").replace('\r', '\n');
            if (isAss) {
                // "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text": o texto vem depois da 8ª vírgula
                int commas = 0, idx = 0;
                while (idx < t.length() && commas < 8) { if (t.charAt(idx) == ',') commas++; idx++; }
                if (commas == 8) t = t.substring(idx);
                t = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").replaceAll("\\{[^}]*\\}", "");
            }
            t = t.trim();
            if (t.isEmpty()) continue;
            if (!seen.add(gStart[i] + "|" + t)) continue;
            items.add(new Object[]{gStart[i], gEnd[i], t});
        }
        java.util.Collections.sort(items, (a, b) -> Long.compare((Long) a[0], (Long) b[0]));
        if (items.size() < 40) return fail(out, t0, "poucas falas legíveis na legenda do vídeo (" + items.size() + ")");
        StringBuilder sb = new StringBuilder(items.size() * 80);
        for (int i = 0; i < items.size(); i++) {
            Object[] it = items.get(i);
            sb.append(i + 1).append('\n').append(ts((Long) it[0])).append(" --> ").append(ts((Long) it[1])).append('\n').append((String) it[2]).append("\n\n");
        }
        out.srt = sb.toString();
        out.cues = items.size();
        out.ms = android.os.SystemClock.uptimeMillis() - t0;
        return out;
    }

    private static String ts(long v) {
        long x = Math.max(0, v);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", x / 3_600_000, x / 60_000 % 60, x / 1000 % 60, x % 1000);
    }

    /** bytes [abs, abs+len) do arquivo, usando o começo já lido quando der */
    private static byte[] readAt(Src src, byte[] b0, long abs, int len) {
        if (len <= 0) return new byte[0];
        if (abs >= 0 && abs + len <= b0.length) return Arrays.copyOfRange(b0, (int) abs, (int) abs + len);
        return src.read(abs, len);
    }

    /** um SimpleBlock ou BlockGroup da faixa: {texto, duração em ticks (-1 se não diz)}; null se não for desta faixa ou não for um bloco */
    private static Object[] parseBlock(byte[] buf, int trackNo) {
        Head h = head(buf, 0);
        if (h == null) return null;
        int pp = -1, pl = 0;
        long dur = -1;
        if (h.id == ID_SIMPLE) {
            pp = h.hl;
            pl = (int) Math.min(h.size, (long) (buf.length - h.hl));
        } else if (h.id == ID_BLOCK_GROUP) {
            for (long[] c : children(buf, h.hl, (int) Math.min(buf.length, h.hl + Math.min(h.size, (long) buf.length)))) {
                if (c[0] == ID_BLOCK && pp < 0) { pp = (int) c[1]; pl = (int) c[2]; }
                if (c[0] == ID_BLOCK_DUR) dur = uint(buf, (int) c[1], (int) c[2]);
            }
        } else return null;
        if (pp < 0 || pl <= 4) return null;
        long[] tk = readSize(buf, pp);
        if (tk == null || (int) tk[0] != trackNo) return null;
        int tl = (int) tk[1];
        if (pp + tl + 3 > buf.length) return null;
        int flags = buf[pp + tl + 2] & 0xFF;
        if (((flags >> 1) & 3) != 0) return null;   // legenda de texto não usa "lacing"
        int fs = pp + tl + 3, fe = Math.min(buf.length, pp + pl);
        if (fs >= fe) return new Object[]{"", dur};
        while (fe > fs && buf[fe - 1] == 0) fe--;
        return new Object[]{new String(buf, fs, fe - fs, StandardCharsets.UTF_8), dur};
    }
}
