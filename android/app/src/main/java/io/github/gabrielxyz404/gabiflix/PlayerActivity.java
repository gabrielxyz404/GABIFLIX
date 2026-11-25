package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;

/** Player embutido com o VLC (libVLC): toca praticamente qualquer formato (MKV, HEVC, DTS, AC3...). */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    /** legendas em português vindas dos addons do Stremio (links .srt/.vtt) */
    static final String EXTRA_SUBS = "subs";
    /** tipo (movie/series) e código do título, para buscar legendas sozinho se o site não mandar nenhuma */
    static final String EXTRA_TYPE = "type";
    static final String EXTRA_ID = "id";
    /** título mostrado no topo dos controles (se faltar, sai do nome do arquivo) */
    static final String EXTRA_TITLE = "title";
    private String titleText;
    /** onde começar (ms), vindo do Continuar assistindo do Stremio */
    static final String EXTRA_START = "start";
    /** última sessão (tempo/duração), lida pelo app ao voltar para devolver o progresso ao Stremio */
    static final String LAST_PREFS = "gf-last-session";
    private long startMs = 0;
    private String[] subs = new String[0];
    private String metaType, metaId;
    private boolean playing = false;
    private int addedSubs = 0;
    private static final java.util.regex.Pattern PT = java.util.regex.Pattern.compile("portug|brazil|brasil|\\bpt\\b|\\bpor\\b|\\bpob\\b|pt-br|pt_br", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int HIDE_DELAY_MS = 3500;
    /** ajuste manual da legenda ATIVA (ms; positivo = a legenda aparece mais tarde), lembrado por vídeo + fonte + legenda */
    private int subDelayMs = 0;
    private static final int MAX_DELAY_MS = 600000;
    /** identificação da fonte (hash do OpenSubtitles ou nome do arquivo), preenchida pela thread de legendas */
    private volatile String srcHash, srcName;
    /** última combinação vídeo+fonte+legenda para a qual o atraso foi carregado; "\0" = ainda nenhuma */
    private String lastSyncKey = "\0", announcedKey = "";
    private TextView noticeLabel;
    private final Runnable hideNotice = () -> { if (noticeLabel != null) noticeLabel.setVisibility(View.GONE); };

    /** o player aberto agora (para o pedido de memória do sistema, que chega pelo App) */
    private static volatile java.lang.ref.WeakReference<PlayerActivity> current;
    private MemTelemetry memTel;

    /** o sistema está apertado de memória (RUNNING_LOW / RUNNING_CRITICAL) com o filme aberto: solta o que o player pode soltar sem mexer na reprodução */
    static void onMemoryPressure(int level) {
        java.lang.ref.WeakReference<PlayerActivity> r = current;
        final PlayerActivity a = r == null ? null : r.get();
        if (a == null || a.player == null || a.isFinishing()) return;
        a.ui.post(() -> a.trimCaches(level));
    }

    private void trimCaches(int level) {
        try {
            glog("memória: o sistema pediu memória (nível " + level + "); soltando caches. " + CrashReporter.memLine());
            synchronized (barRowSamples) { barRowSamples.clear(); barColSamples.clear(); }   // amostras de barras pretas (bitmaps pequenos já foram soltos; aqui as listas)
            io.github.gabrielxyz404.gabiflix.tv.TvCaches.release(this);                      // JSON do catálogo e bitmaps dos pôsteres que a navegação deixou
            Thread gc = new Thread(() -> { Runtime.getRuntime().gc(); System.runFinalization(); }, "gf-trim-gc");
            gc.setPriority(Thread.MIN_PRIORITY);
            gc.start();
        } catch (Throwable t) { glog("memória: não soltou os caches: " + t); }
    }

    /**
     * Qual decodificador o libVLC está usando. O libVLC 3.6 não diz isso pela API Java, mas quando decodifica por MediaCodec (hardware) o processo ganha as threads
     * do MediaCodec NDK ("CodecLooper", "NDK MediaCodec_"); na decodificação por software (ffmpeg dentro do libVLC, o que acontece no emulador sem codec de hardware aceito)
     * essas threads não existem. Medido no emulador: celular API 30 (MediaCodec) tem "CodecLooper"; TV API 30 (ffmpeg) não tem. Lê só /proc/self/task, sem tocar no player.
     */
    private String decoderKind() {
        int n = 0;
        try {
            java.io.File[] ts = new java.io.File("/proc/self/task").listFiles();
            if (ts != null) for (java.io.File t : ts) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(t, "comm")))) {
                    String c = r.readLine();
                    if (c != null && (c.startsWith("CodecLooper") || c.startsWith("NDK MediaCodec") || c.startsWith("MediaCodec_loop"))) n++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { return "?"; }
        return n > 0 ? "hardware (MediaCodec, " + n + " threads)" : "software (ffmpeg no libVLC)";
    }

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private String url;

    private View controls;
    private boolean controlsShown = false;
    /** carregando o início do vídeo: só a seta, o título e o círculo vermelho aparecem; os botões só depois do primeiro quadro */
    private boolean loading = true, buffering = false;
    private View midBox;
    private PlayerUi.Btn playButton;
    private TextView timeLabel, lengthLabel, messageLabel, titleLabel, loadingLabel;
    private PlayerUi.Timeline seek;
    private PlayerUi.Spinner spinner;
    private PlayerUi.TapFx tapFx;
    private boolean userSeeking = false;
    private boolean resumeDone = false;
    private boolean ended = false;
    private long lastSaved = 0;
    private int scaleIndex = 0;
    // 0 = Normal (imagem inteira, com barras pretas se precisar), 1 = Preencher (zoom sem distorcer: corta o excesso até cobrir a tela).
    // O VLC fica sempre em SURFACE_BEST_FIT e sem proporção forçada (nunca estica). O zoom de Preencher é feito na vista do vídeo
    // (TextureView na TV, SurfaceView no celular). A legenda vem desenhada dentro da imagem, perto da borda de baixo; por isso o
    // zoom fica ancorado embaixo (a borda de baixo da imagem = a borda de baixo da tela) e o corte vertical sai todo por cima.
    // O libVLC 3.6 não tem API de recorte (crop) nem SURFACE_FILL/FIT_SCREEN que mantenham a legenda dentro da tela.
    private static final String[] SCALE_NAMES = {"Normal", "Preencher"};
    private int lastScaleW = -1, lastScaleH = -1;
    private boolean vlcFitSet = false;

    /** troca o modo de tela (0 Normal, 1 Preencher) e aplica de uma vez só */
    private void setScaleMode(int mode) {
        scaleIndex = mode % SCALE_NAMES.length;
        if (scaleIndex != 1) endFillPending(false);
        else { fillGaveUp = false; }
        glog("tela: modo " + SCALE_NAMES[scaleIndex] + (scaleIndex == 1 ? (barsReady ? " (barras já medidas)" : " (mede as barras antes de aplicar)") : ""));
        applyScale();
        if (scaleBtn != null) scaleBtn.setLabel("Tela: " + SCALE_NAMES[scaleIndex]);
    }
    /** Preencher: no máximo quantas vezes a largura da tela pode ter a imagem ampliada (filmes ultra-largos, como 2,76:1 em 16:9, ficariam com cortes demais nas laterais) */
    private static final float COVER_MAX_WIDTH = 1.45f;
    /**
     * Preencher: zoom máximo (igual em X e em Y, nunca estica). Escolhido pelo usuário: 1,10x (corta no máximo 4,5% de cada lado).
     * Um 1,85:1 num quadro 16:9 (pede 1,04x) fica sem barras; um 2,39:1 (pede 1,34x) fica com as barras bem mais finas do que com 1,05x.
     * UMA constante por plataforma, fácil de mudar (celular e TV aqui; o Mac tem a sua, zoomMax em PlayerWindow.swift).
     */
    private static final float ZOOM_MAX_TV = 1.10f, ZOOM_MAX_PHONE = 1.10f;

    private void applyScale() {
        if (player == null || videoLayout == null) return;
        int w = videoLayout.getWidth(), h = videoLayout.getHeight();
        lastScaleW = w; lastScaleH = h;
        if (w <= 0 || h <= 0) return;
        if (!vlcFitSet) {   // o libVLC já começa assim; mexer de novo a cada troca refazia o layout da vista do vídeo no meio da troca
            vlcFitSet = true;
            try {
                player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
                player.setScale(0f);
                player.setAspectRatio(null);
            } catch (Throwable t) { glog("escala: " + t); }
        }
        beginFillIfNeeded();
        zoomVideo();
        ui.postDelayed(this::zoomVideo, 300);
        ui.postDelayed(this::zoomVideo, 1000);
        startBarSampling();
    }

    /** a vista do vídeo: a TextureView (TV) ou, no celular, a primeira SurfaceView (a segunda é a das legendas) */
    private View findVideoView(View v) {
        ArrayList<View> found = new ArrayList<>();
        collectVideoViews(v, found);
        for (View c : found) if (c instanceof android.view.TextureView) return c;
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collectVideoViews(View v, ArrayList<View> out) {
        if (v instanceof android.view.TextureView || v instanceof android.view.SurfaceView) out.add(v);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) collectVideoViews(g.getChildAt(i), out); }
    }

    /** proporção real da imagem (largura x SAR / altura) da faixa de vídeo atual; 0 se ainda não se sabe */
    private float videoAspect() {
        try {
            IMedia.VideoTrack vt = player == null ? null : player.getCurrentVideoTrack();
            if (vt == null || vt.width <= 0 || vt.height <= 0) return 0f;
            float sar = vt.sarNum > 0 && vt.sarDen > 0 ? (float) vt.sarNum / vt.sarDen : 1f;
            return vt.width * sar / vt.height;
        } catch (Throwable t) { glog("proporção: " + t); return 0f; }
    }

    // ---- barras pretas gravadas dentro do vídeo (filme 2,39:1 dentro de um quadro 16:9, ou 4:3 com laterais pretas) ----
    // Fração do quadro ocupada por cada barra (0 = sem barra). Medidas por amostras do que a vista do vídeo mostra, fora da thread da tela.
    private volatile float barT, barB, barL, barR;
    private final ArrayList<boolean[]> barRowSamples = new ArrayList<>(), barColSamples = new ArrayList<>();
    private android.os.HandlerThread barThread;
    private Handler barHandler;
    private int barTicks = 0;
    private static final int BAR_MIN_SAMPLES = 3, BAR_MAX_SAMPLES = 8, BAR_LUMA = 40;
    /** na TV a legenda é desenhada dentro da imagem, a ~8% da borda de baixo do quadro: o zoom pode cortar no máximo isto da borda de baixo */
    private static final float SUB_CUT = 0.055f;
    /**
     * Só na TV, só com legenda EMBUTIDA no arquivo (o libVLC a desenha dentro do quadro e ela cresce junto com o zoom): o zoom só pode cortar isto de cada
     * lado (0,019 = limite de ~1,04x). Com o limite geral de 1,10x a linha mais larga que o libVLC monta (~91% do quadro) ficava cortada na lateral
     * (medido no emulador de TV: 99,4% da tela, encostando na borda), então este caso fica com o limite menor. Legenda baixada (desenhada pelo app) e o
     * celular usam ZOOM_MAX_TV/ZOOM_MAX_PHONE (1,10x).
     */
    private static final float SUB_SAFE_X = 0.019f;
    private final Runnable barTickRun = this::barTick;
    private PlayerUi.Btn scaleBtn;

    // Troca para Preencher sem passo intermediário: enquanto as barras gravadas no vídeo ainda não foram medidas, a imagem fica como
    // está (Normal) e uma rajada rápida de amostras mede as barras; só então o zoom final é aplicado, de uma vez. Se não der para medir
    // (cena escura, vídeo sem quadro) em FILL_WAIT_MS, aplica mesmo assim, como antes, e a medida normal refina depois.
    private volatile boolean barsReady = false, fillPending = false;
    private int diagSamples = 0;
    private boolean fillGaveUp = false;
    private static final long FILL_WAIT_MS = 2500, FILL_BURST_MS = 110;
    private final Runnable fillBurstRun = new Runnable() {
        @Override public void run() {
            if (!fillPending || scaleIndex != 1 || player == null || isFinishing()) return;
            sampleBarsNow();
            ui.postDelayed(this, FILL_BURST_MS);
        }
    };
    private final Runnable fillTimeoutRun = () -> { if (fillPending) { glog("zoom Preencher: não deu para medir as barras a tempo; aplica sem elas"); fillGaveUp = true; endFillPending(true); } };

    private void beginFillIfNeeded() {
        if (scaleIndex != 1 || barsReady || fillPending || fillGaveUp) return;
        fillPending = true;
        ui.removeCallbacks(fillBurstRun); ui.removeCallbacks(fillTimeoutRun);
        ui.post(fillBurstRun);
        ui.postDelayed(fillTimeoutRun, FILL_WAIT_MS);
    }

    private void endFillPending(boolean applyNow) {
        fillPending = false;
        ui.removeCallbacks(fillBurstRun); ui.removeCallbacks(fillTimeoutRun);
        if (applyNow) zoomVideo();
    }

    private void startBarSampling() {
        ui.removeCallbacks(barTickRun);
        if (scaleIndex == 1) ui.postDelayed(barTickRun, 1500);
    }

    private void barTick() {
        if (scaleIndex != 1 || player == null || isFinishing()) return;
        barTicks++;
        ui.postDelayed(barTickRun, barTicks < 10 ? 2000 : 20000);
        try {
            if (!player.isPlaying() || controlsShown) return;  // com os controles na tela a legenda sobe para o meio da imagem e sujaria a medida
            sampleBarsNow();
        } catch (Throwable t) { glog("barras: " + t); }
    }

    /** pede uma amostra do que a vista do vídeo mostra (o resultado chega em analyzeBars, fora da thread da tela) */
    private void sampleBarsNow() {
        try {
            if (player == null) return;
            View v = findVideoView(videoLayout);
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
            final float va = videoAspect();
            final int bw = 192, bh = Math.max(36, Math.round(bw * (float) v.getHeight() / v.getWidth()));
            if (barHandler == null) { barThread = new android.os.HandlerThread("barras"); barThread.start(); barHandler = new Handler(barThread.getLooper()); }
            if (v instanceof android.view.TextureView) {
                android.view.TextureView tv2 = (android.view.TextureView) v;
                if (!tv2.isAvailable()) return;
                final android.graphics.Bitmap bmp = tv2.getBitmap(bw, bh);
                if (bmp != null) barHandler.post(() -> analyzeBars(bmp, va, true));
            } else if (v instanceof android.view.SurfaceView && android.os.Build.VERSION.SDK_INT >= 24) {
                final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request((android.view.SurfaceView) v, bmp, res -> {
                    if (res == android.view.PixelCopy.SUCCESS) analyzeBars(bmp, va, false);
                    else glog("barras: a cópia da imagem falhou (código " + res + ")");
                }, barHandler);
            }
        } catch (Throwable t) { glog("barras: " + t); }
    }

    /** thread de fundo: uma amostra (quais linhas/colunas do quadro têm algo que não é preto) e, com amostras suficientes, o resultado */
    private void analyzeBars(android.graphics.Bitmap bmp, float va, boolean inTexture) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            float fw = w, fh = h;
            if (va > 0) { if ((float) w / h > va) fw = h * va; else fh = w / va; }
            int fW = Math.max(8, Math.round(fw)), fH = Math.max(8, Math.round(fh));
            int fx = (w - fW) / 2, fy = (h - fH) / 2;
            boolean[] rows = new boolean[fH], cols = new boolean[fW];
            int x0 = fx + fW / 5, x1 = fx + fW * 4 / 5, y0 = fy + fH / 5, y1 = fy + fH * 13 / 20, any = 0;  // colunas: só 20%-65% da altura (a legenda fica embaixo)
            for (int y = 0; y < fH; y++) {
                int cnt = 0;
                for (int x = x0; x < x1; x++) if (luma(px[(fy + y) * w + x]) > BAR_LUMA) cnt++;
                rows[y] = cnt * 100 > (x1 - x0) * 2;
                if (rows[y]) any++;
            }
            for (int x = 0; x < fW; x++) {
                int cnt = 0;
                for (int y = y0; y < y1; y++) if (luma(px[y * w + fx + x]) > BAR_LUMA) cnt++;
                cols[x] = cnt * 100 > (y1 - y0) * 2;
            }
            if (diagSamples++ < 60) glog(String.format(Locale.ROOT, "amostra: imagem %dx%d, proporção do vídeo %.3f, quadro %dx%d, linhas com imagem %d, preto em cima %d, embaixo %d", w, h, va, fW, fH, any, lead(rows), trail(rows)));
            // quadro quase todo preto (fade, transição): não conta
            if (any * 100 < fH * 8) return;
            float[] r;
            synchronized (barRowSamples) {
                if (!barRowSamples.isEmpty() && (barRowSamples.get(0).length != fH || barColSamples.get(0).length != fW)) { barRowSamples.clear(); barColSamples.clear(); }
                barRowSamples.add(rows); barColSamples.add(cols);
                while (barRowSamples.size() > BAR_MAX_SAMPLES) { barRowSamples.remove(0); barColSamples.remove(0); }
                if (barRowSamples.size() < BAR_MIN_SAMPLES) return;
                // união das amostras: linha/coluna só é "barra" se foi preta em TODAS (cenas escuras não criam barras falsas)
                boolean[] ur = new boolean[fH], uc = new boolean[fW];
                for (boolean[] a : barRowSamples) for (int i = 0; i < fH; i++) ur[i] |= a[i];
                for (boolean[] a : barColSamples) for (int i = 0; i < fW; i++) uc[i] |= a[i];
                r = new float[]{lead(ur) / (float) fH, trail(ur) / (float) fH, lead(uc) / (float) fW, trail(uc) / (float) fW};
            }
            for (int i = 0; i < 4; i++) if (r[i] < 0.015f) r[i] = 0f;
            // não confiável: sobraria pouco da imagem (ou imagem inteira preta)
            if (1f - r[0] - r[1] < 0.35f || 1f - r[2] - r[3] < 0.35f) r[0] = r[1] = r[2] = r[3] = 0f;
            // barras de cima/baixo vêm sempre em par: sem a de cima não se confia na de baixo (na TV a legenda "suja" a de baixo)
            if (r[0] == 0f && inTexture) r[1] = 0f;
            if (inTexture && r[0] > r[1]) r[1] = r[0];
            barsReady = true;
            boolean changed = Math.abs(r[0] - barT) > 0.004f || Math.abs(r[1] - barB) > 0.004f || Math.abs(r[2] - barL) > 0.004f || Math.abs(r[3] - barR) > 0.004f;
            if (changed) {
                barT = r[0]; barB = r[1]; barL = r[2]; barR = r[3];
                glog(String.format(Locale.ROOT, "barras: cima %.3f baixo %.3f esq %.3f dir %.3f (amostras %d)", r[0], r[1], r[2], r[3], barRowSamples.size()));
            }
            if (fillPending) ui.post(() -> { if (fillPending) endFillPending(true); });   // medida pronta: aplica o zoom final de uma vez
            else if (changed) ui.post(this::zoomVideo);
        } catch (Throwable t) { glog("barras: " + t); }
    }

    private static int luma(int c) { return (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8; }
    private static int lead(boolean[] a) { int n = 0; while (n < a.length && !a[n]) n++; return n == a.length ? 0 : n; }
    private static int trail(boolean[] a) { int n = 0; while (n < a.length && !a[a.length - 1 - n]) n++; return n == a.length ? 0 : n; }

    /**
     * Preencher: amplia a imagem ATIVA (sem as barras pretas gravadas no vídeo) até cobrir a tela; nos outros modos volta a 1.
     * Sem barras gravadas o comportamento é o de sempre (corte por cima, borda de baixo da imagem na borda de baixo da tela).
     * Com barras: a imagem ativa fica centrada; na TV, onde a legenda é desenhada dentro do quadro (dentro da barra de baixo), o zoom
     * deixa visível a faixa da legenda (só corta SUB_CUT do fim do quadro) e o corte de cima é feito só nas barras; no celular a legenda
     * fica numa camada separada, presa à tela, então a imagem ativa pode cobrir a tela inteira.
     */
    private void zoomVideo() {
        try {
            View tex = findVideoView(videoLayout);
            if (tex == null) return;
            if (tex.getTag() == null) {
                tex.setTag("zoom");
                tex.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot || l != ol || t != ot) ui.post(this::zoomVideo); });
            }
            float f = 1f;
            int tw = tex.getWidth(), th = tex.getHeight(), w = videoLayout.getWidth(), h = videoLayout.getHeight();
            float px = tw / 2f, py = th / 2f;
            if (scaleIndex == 1 && !fillPending && tw > 0 && th > 0 && w > 0 && h > 0) {
                // o quadro do vídeo (proporção real) ajustado dentro da vista (na TV a vista é do tamanho da tela e o VLC desenha o preto em volta)
                float dw = tw, dh = th, va = videoAspect();
                if (va > 0) { if ((float) tw / th > va) dw = th * va; else dh = tw / va; }
                float fx0 = (tw - dw) / 2f, fy0 = (th - dh) / 2f;
                float bT = barT, bB = barB, bL = barL, bR = barR;
                float ax0 = fx0 + dw * bL, ax1 = fx0 + dw * (1f - bR), ay0 = fy0 + dh * bT, ay1 = fy0 + dh * (1f - bB);
                float aw = ax1 - ax0, ah = ay1 - ay0;
                // TV com legenda desenhada pelo app (vista à parte): o zoom não mexe nela, então vale a mesma regra do celular
                boolean subsInside = tex instanceof android.view.TextureView && !appSubs && embSubActive;
                // fim da janela visível (em baixo): sem barra de baixo, o fim da imagem; com ela, na TV, ainda um pedaço da barra para a legenda
                float kb = ay1;
                if (subsInside && bB > 0f) kb = Math.max(ay1, fy0 + dh * (1f - SUB_CUT));
                f = Math.max((float) w / aw, (float) h / ah);
                if (aw * f > COVER_MAX_WIDTH * w) f = COVER_MAX_WIDTH * w / aw;
                f = Math.min(f, tex instanceof android.view.TextureView ? ZOOM_MAX_TV : ZOOM_MAX_PHONE);
                if (subsInside) f = Math.min(f, w / (dw * (1f - 2f * SUB_SAFE_X)));   // TV: legenda nunca cortada nas laterais
                if (kb > ay1 + 0.5f) f = Math.min(f, h / (kb - ay0));
                f = Math.max(1f, f);
                if (f > 1.001f) {
                    float winW = w / f, winH = h / f;
                    float wx0 = (ax0 + ax1) / 2f - winW / 2f;
                    if (winW <= dw) wx0 = Math.max(fx0, Math.min(fx0 + dw - winW, wx0)); else wx0 = fx0 + dw / 2f - winW / 2f;
                    float wy0;
                    if (subsInside && winH >= (kb > ay1 + 0.5f ? kb - ay0 : ah) - 0.5f) {  // TV: janela maior que a imagem ativa: centra (letterbox simétrico)
                        wy0 = (ay0 + ay1) / 2f - winH / 2f;
                        if (kb > ay1 + 0.5f) wy0 = Math.max(wy0, kb - winH);              // sem cortar a faixa da legenda
                    }
                    else if (kb > ay1 + 0.5f) wy0 = kb - winH;                             // TV com barra: janela termina no fim da faixa da legenda
                    else if (!subsInside && (bT > 0f || bB > 0f)) {                       // celular com barras em cima/baixo: imagem ativa centrada
                        wy0 = (ay0 + ay1) / 2f - winH / 2f;
                        if (winH <= dh) wy0 = Math.max(fy0, Math.min(fy0 + dh - winH, wy0));
                    } else if (winH > dh) wy0 = fy0 + dh / 2f - winH / 2f;                // limite de zoom: a imagem não cobre a altura, fica centrada
                    else wy0 = fy0 + dh - winH;                                            // sem barras: borda de baixo do quadro = borda de baixo da tela
                    // a vista pode não começar no canto da tela (celular: vista 2400x1006 centrada numa tela 2400x1080): conta o deslocamento
                    px = (f * wx0 + tex.getLeft()) / (f - 1f);
                    py = (f * wy0 + tex.getTop()) / (f - 1f);
                } else f = 1f;
                glog(String.format(Locale.ROOT, "zoom Preencher: vista %dx%d (%s em %d,%d), tela %dx%d, proporção do vídeo %.3f, barras %.3f/%.3f/%.3f/%.3f, fator %.3f", tw, th, tex.getClass().getSimpleName(), tex.getLeft(), tex.getTop(), w, h, va, bT, bB, bL, bR, f));
            }
            tex.setPivotX(px); tex.setPivotY(py);
            tex.setScaleX(f); tex.setScaleY(f);
        } catch (Throwable t) { glog("escala: " + t); }
    }

    // ---------- legenda desenhada pelo app (TV e celular) ----------
    // Na TV o libVLC (TextureView) desenha a legenda DENTRO da imagem do vídeo: qualquer zoom a amplia junto e a corta nas laterais; e em
    // qualquer tela o libVLC lê o arquivo só para a frente, então depois de mudar o atraso ou pular no filme a legenda some por um tempo.
    // Por isso, para as legendas baixadas (SRT/VTT/ASS), o libVLC recebe um arquivo "em branco" (mesma faixa, mesmo atraso, nada na tela) e as
    // falas são desenhadas aqui, numa vista à parte acima do vídeo. As legendas que vêm DENTRO do arquivo continuam no libVLC.
    private SubtitleOverlay.Text subOverlay;
    private SubtitleSync.Cand ovCand;
    private SubtitleOverlay ovCues;
    private final java.util.IdentityHashMap<SubtitleSync.Cand, SubtitleOverlay> ovCache = new java.util.IdentityHashMap<>();
    /** a legenda ativa está sendo desenhada pelo app (então o zoom do Preencher não precisa guardar folga para a legenda) */
    private volatile boolean appSubs = false, embSubActive = false;
    private int ovTicks = 0;
    private long ovLastGet = Long.MIN_VALUE, ovLastWall = 0, ovLastEst = 0;
    private String ovShown = null;
    private final Runnable ovTickRun = new Runnable() {
        @Override public void run() {
            if (player == null || isFinishing()) return;
            try { overlayTick(); } catch (Throwable t) { glog("legenda do app: " + t); }
            ui.postDelayed(this, 100);
        }
    };

    private void startOverlay(FrameLayout root) {
        int sh = getResources().getDisplayMetrics().heightPixels, sw = getResources().getDisplayMetrics().widthPixels;
        float px = Math.min(sh, sw) * 0.055f;   // igual à legenda do libVLC com sub-text-scale 90 numa tela de 1080 (linhas de ~70 px)
        subOverlay = new SubtitleOverlay.Text(this, px * 0.11f);
        subOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        subOverlay.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));   // Roboto, como a legenda do libVLC
        int side = Math.round(Math.max(sw, sh) * 0.03f), bottom = Math.round(Math.min(sh, sw) * 0.045f);
        subOverlay.setPadding(side, 0, side, bottom);
        subOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(subOverlay, lp);
        ui.postDelayed(ovTickRun, 300);
    }

    /** um arquivo de legenda "em branco" para o libVLC (a faixa existe e responde a atraso/seek, mas não desenha nada) */
    private Uri blankSubtitle(int n) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "legendas");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "app-" + n + ".srt");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write("1\n00:00:00,000 --> 99:00:00,000\n\u00A0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.close();
            return Uri.fromFile(f);
        } catch (Exception e) { return null; }
    }

    /** só nos testes (am start --es gfOvScript "delay:3000@12000+seek:100@30000"): muda o atraso / pula no filme N ms depois de começar */
    private void ovTestScript() {
        String sc = getIntent().getStringExtra("gfOvScript");
        if (sc == null) return;
        for (String tok : sc.split("\\+")) {
            try {
                String[] a = tok.trim().split("[:@]");
                final String kind = a[0];
                final long val = Long.parseLong(a[1]);
                ui.postDelayed(() -> {
                    if (player == null) return;
                    glog("teste: " + kind + " " + val + " (tempo " + player.getTime() + " ms)");
                    if ("delay".equals(kind)) changeSubDelay((int) val, false, panelValue);
                    else if ("seek".equals(kind)) player.setTime(val * 1000);
                    else if ("tela".equals(kind)) setScaleMode((int) val);   // só nos testes: troca o modo de tela como o botão Tela
                }, Long.parseLong(a[2]));
            } catch (Throwable t) { glog("teste: roteiro inválido " + tok); }
        }
    }

    private void overlayTick() {
        if (subOverlay == null) return;
        if (ovTicks++ % 5 == 0) {
            SubtitleSync.Cand c = null;
            try { c = candForTrack(player.getSpuTrack(), player.getSpuTracks()); } catch (Throwable ignored) {}
            if (c != ovCand) {
                ovCand = c;
                ovCues = null;
                if (c != null && c.text != null) {
                    ovCues = ovCache.get(c);
                    if (ovCues == null) { ovCues = SubtitleOverlay.parse(c.text); ovCache.put(c, ovCues); glog("legenda do app: " + ovCues.size() + " falas da faixa ativa"); }
                }
                ovShown = null;
            }
            boolean on = ovCues != null && c != null && c.uri != null && c.uri.getLastPathSegment() != null && c.uri.getLastPathSegment().startsWith("app-");
            if (!on) ovCues = null;
            if (on != appSubs) { appSubs = on; glog("legenda do app: " + (on ? "ligada" : "desligada (faixa embutida ou nenhuma: o libVLC desenha)")); zoomVideo(); if (!on) applySubDelay(); }
            // legenda do libVLC de verdade na tela (faixa embutida ativa): só então o zoom da TV respeita a margem lateral dela; sem legenda nenhuma vale o limite geral
            boolean emb = !on && player.getSpuTrack() != -1;
            if (emb != embSubActive) { embSubActive = emb; glog("legenda embutida (libVLC) ativa: " + emb); zoomVideo(); }
        }
        String want = null;
        if (ovCues != null) {
            long t = player.getTime(), now = SystemClock.uptimeMillis();
            if (t >= 0) {
                if (t != ovLastGet) { ovLastGet = t; ovLastWall = now; }
                else if (player.isPlaying()) t += Math.min(500, now - ovLastWall);   // o relógio do libVLC anda aos saltos: entre um e outro, estima
                // a estimativa não volta para trás por ruído (< 300 ms): senão a fala some por um instante na borda dela; um salto de verdade (pular para trás) vale na hora
                if (t < ovLastEst && ovLastEst - t < 300) t = ovLastEst;
                ovLastEst = t;
                // atraso positivo = a legenda aparece MAIS TARDE (mesma regra do setSpuDelay e do painel)
                want = ovCues.at(t - subDelayMs, 150);
            } else want = ovShown;
        }
        if (want == null ? ovShown != null : !want.equals(ovShown)) {
            ovShown = want;
            glog("legenda do app: tempo " + player.getTime() + " ms, atraso " + subDelayMs + " ms -> " + (want == null ? "(nada)" : "\"" + want.replace('\n', '/') + "\""));
            if (want == null) subOverlay.setVisibility(View.GONE);
            else { subOverlay.setText(want); subOverlay.setVisibility(View.VISIBLE); }
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> setControlsVisible(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra(EXTRA_URL);
        String[] extraSubs = getIntent().getStringArrayExtra(EXTRA_SUBS);
        if (extraSubs != null) subs = extraSubs;
        metaType = getIntent().getStringExtra(EXTRA_TYPE);
        metaId = getIntent().getStringExtra(EXTRA_ID);
        startMs = getIntent().getLongExtra(EXTRA_START, 0);
        // o sistema matou o processo com o filme em segundo plano: volta de onde estava (o ponto do Intent é o de quando abriu)
        if (savedInstanceState != null && savedInstanceState.getLong("gfPos", 0) > 0) { startMs = savedInstanceState.getLong("gfPos"); glog("ciclo: processo recriado pelo sistema, volta do ponto " + startMs + " ms"); }
        titleText = getIntent().getStringExtra(EXTRA_TITLE);
        getSharedPreferences(LAST_PREFS, MODE_PRIVATE).edit().clear().apply();
        if (url == null) { finish(); return; }
        subDelayMs = 0; // o atraso guardado só entra quando a legenda ativa é conhecida (syncDelayToTrack)
        loadSubtitlesInBackground();

        tv = MainActivity.isTv(this);
        current = new java.lang.ref.WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        focusSink = root;
        if (tv) { root.setFocusable(true); root.setFocusableInTouchMode(true); }
        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, match());
        startOverlay(root);

        spinner = new PlayerUi.Spinner(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER);
        root.addView(spinner, sp);
        loadingLabel = text("", 14, false);
        loadingLabel.setTypeface(PlayerUi.font(this, 500));
        loadingLabel.setGravity(Gravity.CENTER);
        loadingLabel.setText("Carregando 0%");
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lbp.topMargin = dp(52);
        root.addView(loadingLabel, lbp);

        messageLabel = text("", 17, true);
        messageLabel.setGravity(Gravity.CENTER);
        messageLabel.setVisibility(View.GONE);
        messageLabel.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(messageLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        controls = buildControls();
        root.addView(controls, match()); // visível desde o início só com a seta e o título (ver applyChrome)
        applyChrome();
        tapFx = new PlayerUi.TapFx(this);
        root.addView(tapFx, match());
        noticeLabel = text("", 14, false);
        noticeLabel.setVisibility(View.GONE);
        noticeLabel.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(0x99141416);
        nb.setCornerRadius(dp(18));
        noticeLabel.setBackground(nb);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        nlp.topMargin = dp(28);
        root.addView(noticeLabel, nlp);
        setContentView(root);
        if (tv) root.requestFocus();

        // toque: mostra/esconde os controles; toque duplo nas laterais: volta ou avança 10 s
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { setControlsVisible(!controlsShown); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                boolean right = e.getX() > root.getWidth() / 2f;
                skip(right ? 10000 : -10000, false);
                tapFx.play(e.getX(), e.getY(), right);
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        root.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
        controls.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));

        ArrayList<String> options = new ArrayList<>();
        options.add("--http-reconnect");
        options.add("--network-caching=3000");
        options.add("--audio-time-stretch");
        options.add("--sub-text-scale=90");
        // só nos testes (diagnóstico de memória): log detalhado do libVLC (qual decodificador/saída de vídeo ele escolheu)
        if (getIntent().getStringExtra("gfVlcV") != null) options.add("-vv");
        // só nos testes: opções extras do libVLC separadas por vírgula (por exemplo "--vout=android_display,--avcodec-threads=1"), para isolar a causa do crescimento de memória
        String gOpt = getIntent().getStringExtra("gfVlcOpt");
        if (gOpt != null) for (String o : gOpt.trim().split("[\\s,]+")) if (o.startsWith("-")) options.add(o);
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        // na TV (Fire TV) o vídeo vai por TextureView: com SurfaceView a imagem ficava preta (só som)
        // quando os controles sumiam, porque o Fire TV passa o vídeo para outra camada da tela
        String gTex = getIntent().getStringExtra("gfTexture");   // só nos testes: força TextureView (1) ou SurfaceView (0), qualquer que seja o aparelho
        player.attachViews(videoLayout, null, true, gTex != null ? "1".equals(gTex) : tv);
        player.setEventListener(this::onPlayerEvent);
        // rotação / mudança de tamanho da tela: refaz o zoom do modo Preencher e a proporção do Esticar
        videoLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (scaleIndex != 0 && (v.getWidth() != lastScaleW || v.getHeight() != lastScaleH)) ui.post(this::applyScale);
        });

        Media media = new Media(libVLC, Uri.parse(url));
        String gHw = getIntent().getStringExtra("gfHw");   // só nos testes: "0" = só software, "force" = só hardware (MediaCodec), sem recuo
        media.setHWDecoderEnabled(!"0".equals(gHw), "force".equals(gHw));
        media.addOption(":network-caching=3000");
        player.setMedia(media);
        media.release();
        player.play();
        immersive();
    }

    /** botões do meio e barra de baixo: escondidos ao carregar; o do meio some também enquanto o círculo gira no meio do filme */
    private void applyChrome() {
        if (midBox == null || bottomBar == null) return;
        midBox.setVisibility(loading ? View.GONE : buffering ? View.INVISIBLE : View.VISIBLE);
        bottomBar.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    /** primeiro quadro tocando: mostra os controles uma vez (somem sozinhos como sempre) */
    private void endLoading() {
        if (!loading) return;
        loading = false;
        applyChrome();
        for (View v : new View[]{midBox, bottomBar}) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(180).start(); }
        controlsShown = false;
        setControlsVisible(true);
        // sincronização pelo áudio: o botão passa a usar o motor encadeado (referência primeiro, áudio confere) e o modo automático começa a vigiar
        try {
            audioSync = new AudioSyncAuto(this, audioHost);
            audioSync.setProgress((i, n) -> ui.post(() -> autoProgress = n > 1 ? " (janela " + i + " de " + n + ")" : ""));
            autoEngine = audioSync.chain(autoEngine);
            audioSync.start();
        } catch (Throwable t) { glog("AUDIOSYNC não iniciou: " + t); }
        // TV: relatório anônimo MEMORIA a cada 15 min de reprodução (máx. 8 por sessão)
        try {
            long gMemMs = 0;
            try { String g = getIntent().getStringExtra("gfMemTelMs"); if (g != null) gMemMs = Long.parseLong(g); } catch (Throwable ignored) {}   // só nos testes
            if (memTel == null) memTel = MemTelemetry.startIfTv(this, tv, new MemTelemetry.Host() {
                @Override public boolean playing() { return player != null && player.isPlaying() && !buffering; }
                @Override public String decoder() { return decoderKind(); }
                @Override public String surface() { View v = videoLayout == null ? null : findVideoView(videoLayout); return v == null ? "?" : v.getClass().getSimpleName(); }
            }, gMemMs);
        } catch (Throwable t) { glog("telemetria de memória não iniciou: " + t); }
        // só nos testes: modo de tela, atraso da legenda e registro da posição (para medir quando cada fala aparece)
        try {
            String sc = getIntent().getStringExtra("gfScale");
            if (sc != null) { scaleIndex = Integer.parseInt(sc); applyScale(); }
            // só nos testes (gfRx): ações do usuário por "adb shell am broadcast -a ...TEST --es cmd seek|delay|track --es val N", para provar os
            // gatilhos do motor de legendas sem depender de toques na tela (seek = segundo; delay = ajuste manual do atraso em ms; track = id da faixa)
            if (getIntent().getStringExtra("gfRx") != null && testRx == null) {
                testRx = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        try {
                            String cmd = in.getStringExtra("cmd"), val = in.getStringExtra("val");
                            if (player == null || cmd == null || val == null) return;
                            glog("teste: ação do usuário " + cmd + " " + val);
                            if (cmd.equals("seek")) player.setTime(Long.parseLong(val) * 1000L);
                            else if (cmd.equals("delay")) { userEdits++; setSubDelay(Integer.parseInt(val), panelValue); }
                            else if (cmd.equals("sync")) startAutoSync(player.getSpuTrack(), false);   // só nos testes: o mesmo toque do botão, sem passar pelo painel
                            else if (cmd.equals("panelstate")) glog("painel do ajuste manual: aberto=" + subPanelOpen + " estado=\"" + (panelStatus == null ? "" : panelStatus.getText()) + "\" atraso=" + subDelayMs + " ms");
                            else if (cmd.equals("embstate")) glog("embutidas lidas: " + embVirtual.keySet() + " faixaAtiva=" + player.getSpuTrack() + " legendaApp=" + (candForTrack(player.getSpuTrack(), player.getSpuTracks()) != null) + " rodando=" + autoRunning);
                            else if (cmd.equals("track")) { userPickedSub = true; player.setSpuTrack(Integer.parseInt(val)); syncDelayToTrack(Integer.parseInt(val)); }
                            else if (cmd.equals("audionext")) { MediaPlayer.TrackDescription[] at = player.getAudioTracks(); if (at != null && at.length > 0) { int cur = player.getAudioTrack(), k = 0; for (int q = 0; q < at.length; q++) if (at[q].id == cur) k = q; player.setAudioTrack(at[(k + 1) % at.length].id); } }
                            else if (cmd.equals("spunext")) { userPickedSub = true; MediaPlayer.TrackDescription[] st = player.getSpuTracks(); if (st != null && st.length > 0) { int cur = player.getSpuTrack(), k = 0; for (int q = 0; q < st.length; q++) if (st[q].id == cur) k = q; for (int q = 1; q <= st.length; q++) { int id = st[(k + q) % st.length].id; if (id != -1) { player.setSpuTrack(id); syncDelayToTrack(id); break; } } } }
                            else if (cmd.equals("tela")) setScaleMode(Integer.parseInt(val));
                            else if (cmd.equals("estado")) {   // só nos testes: uma linha com o que está na tela (o teste de estresse confere cada ação por ela)
                                glog("ESTADO tempo=" + player.getTime() + " tocando=" + player.isPlaying() + " fim=" + ended + " spu=" + player.getSpuTrack() + " audio=" + player.getAudioTrack()
                                    + " legenda=" + (subOverlay != null && subOverlay.getVisibility() == View.VISIBLE ? "visivel" : "nada") + " tela=" + SCALE_NAMES[scaleIndex]
                                    + " video=" + player.getVideoTrack() + " mem=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 + "k");
                            }
                        } catch (Throwable t) { glog("teste: ação falhou: " + t); }
                    }
                };
                android.content.IntentFilter f = new android.content.IntentFilter("io.github.gabrielxyz404.gabiflix.TEST");
                if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(testRx, f, 2 /* RECEIVER_EXPORTED */); else registerReceiver(testRx, f);
            }
            String sd = getIntent().getStringExtra("gfSubDelay");
            if (sd != null) ui.postDelayed(() -> { subDelayMs = Integer.parseInt(sd); applySubDelay(); glog("teste: atraso da legenda posto em " + subDelayMs + " ms"); }, 4000);
            if (getIntent().getStringExtra("gfTrace") != null) ui.post(new Runnable() { @Override public void run() {
                if (player == null) return;
                glog("pos " + player.getTime() + " spu=" + player.getSpuTrack() + " atraso=" + subDelayMs);
                ui.postDelayed(this, 400);
            } });
        } catch (Throwable ignored) {}
    }

    // ---------- eventos do VLC ----------
    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                spinner.setVisibility(View.GONE);
                loadingLabel.setVisibility(View.GONE);
                buffering = false;
                applyChrome();
                if (loading) ui.postDelayed(this::endLoading, 1500); // se o primeiro quadro não avisar (só áudio), libera mesmo assim
                playButton.setIcon(PlayerUi.Icon.PAUSE);
                if (!resumeDone) resume();
                if (!playing) { playing = true; ui.postDelayed(this::attachReadySubs, 1500); selectEmbeddedPortugueseLater(); ovTestScript(); }
                scheduleHide();
                break;
            case MediaPlayer.Event.Vout:
                glog("ciclo: evento Vout, saídas=" + event.getVoutCount());
                if (refreshFrame && event.getVoutCount() > 0) ui.postDelayed(this::refreshFrameNow, 400);
                if (loading && playing) ui.post(this::endLoading);
                if (scaleIndex != 0) ui.post(this::applyScale);
                if (controlsShown) ui.post(() -> raiseSubs(true));
                break;
            case MediaPlayer.Event.Paused:
                playButton.setIcon(PlayerUi.Icon.PLAY);
                savePosition(true);
                break;
            case MediaPlayer.Event.Buffering:
                boolean buf = event.getBuffering() < 100f;
                spinner.setVisibility(buf ? View.VISIBLE : View.GONE);
                loadingLabel.setVisibility(buf ? View.VISIBLE : View.GONE);
                buffering = buf;
                applyChrome();
                if (buf) loadingLabel.setText(String.format(Locale.ROOT, "Carregando %d%%", (int) event.getBuffering()));
                break;
            case MediaPlayer.Event.LengthChanged:
                lengthLabel.setText(format(player.getLength()));
                seek.setSeekable(player.getLength() > 0);
                break;
            case MediaPlayer.Event.TimeChanged:
                long t = player.getTime(), len = player.getLength();
                if (len > 0) seek.setSeekable(true);
                if (!userSeeking && len > 0 && SystemClock.uptimeMillis() >= seekHoldUntil) seek.setProgress(t / (float) len);
                if (!userSeeking && SystemClock.uptimeMillis() >= seekHoldUntil) timeLabel.setText(format(t));
                if (len > 0) lengthLabel.setText(format(len));
                savePosition(false);
                break;
            case MediaPlayer.Event.EndReached:
                ended = true;
                if (metaId == null) clearPosition();
                else reportSession(player.getLength(), player.getLength(), true);
                finish();
                break;
            case MediaPlayer.Event.EncounteredError:
                spinner.setVisibility(View.GONE);
                loadingLabel.setVisibility(View.GONE);
                loading = false; buffering = false;
                applyChrome();
                messageLabel.setText("Não foi possível reproduzir este vídeo.\nToque em voltar e escolha outra fonte.");
                messageLabel.setVisibility(View.VISIBLE);
                setControlsVisible(true);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try { if (player != null && !ended) { long t = player.getTime(); if (t > 0) out.putLong("gfPos", t); } } catch (Throwable ignored) {}
    }

    // ---------- continuar de onde parou ----------
    private SharedPreferences prefs() { return getSharedPreferences("positions", MODE_PRIVATE); }
    private String key() { return "p" + url.hashCode(); }

    private void resume() {
        resumeDone = true;
        // aberto pelo FramePlay: o ponto certo é o do Stremio (se foi removido do Continuar assistindo, começa do zero)
        long saved = metaId != null ? startMs : prefs().getLong(key(), 0);
        long len = player.getLength();
        if (saved > 30000 && (len <= 0 || saved < len - 60000)) player.setTime(saved);
        String sk = getIntent().getStringExtra("gfSeekSec");   // só nos testes: começa depois (onde há fala)
        if (sk != null) try { player.setTime(Long.parseLong(sk) * 1000); } catch (Throwable ignored) {}
    }

    private void savePosition(boolean force) {
        long now = System.currentTimeMillis();
        if (ended) return;
        if (!force && now - lastSaved < 5000) return;
        lastSaved = now;
        if (player == null) return;
        long t = player.getTime();
        if (t <= 0) return;
        if (metaId == null) prefs().edit().putLong(key(), t).apply();
        else reportSession(t, player.getLength(), false);
    }

    private void clearPosition() { prefs().edit().remove(key()).apply(); }

    private void reportSession(long time, long length, boolean finished) {
        getSharedPreferences(LAST_PREFS, MODE_PRIVATE).edit()
                .putLong("time", time).putLong("length", length).putBoolean("ended", finished).putString("url", url)
                .putLong("at", System.currentTimeMillis()).apply();
    }

    // ---------- controles ----------
    /** título do topo: o que veio do app, ou o nome do arquivo sem extensão, pontos e etiquetas (1080p, WEB-DL...) */
    private String deriveTitle() {
        if (titleText != null && !titleText.trim().isEmpty()) return titleText.trim();
        String n = null;
        try { n = SubtitleSync.fileNameOf(url, null); } catch (Exception ignored) {}
        if (n == null || n.isEmpty()) return "";
        String raw = n;
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 5) n = n.substring(0, dot);
        n = n.replace('.', ' ').replace('_', ' ').trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)[ \\[(](?:(?:19|20)\\d{2}|\\d{3,4}p|4k|uhd|web-?dl|webrip|blu-?ray|brrip|hdr|x26[45]|hevc|dual)(?=[ \\])]|$)").matcher(n);
        if (m.find() && m.start() > 0) n = n.substring(0, m.start());
        n = n.trim();
        return n.isEmpty() ? raw : n;
    }

    private View buildControls() {
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        // recortes da tela (notch) e barras: os controles ficam dentro da área segura
        box.setOnApplyWindowInsetsListener((v, ins) -> {
            int l = ins.getSystemWindowInsetLeft(), r = ins.getSystemWindowInsetRight();
            if (android.os.Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                l = Math.max(l, ins.getDisplayCutout().getSafeInsetLeft());
                r = Math.max(r, ins.getDisplayCutout().getSafeInsetRight());
            }
            v.setPadding(l, 0, r, 0);
            return ins;
        });
        final int idPlay = View.generateViewId(), idSeek = View.generateViewId(), idSubs = View.generateViewId(), idBack = View.generateViewId();

        // faixa de cima: voltar + título, com degradê suave
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(14), dp(24), dp(44));
        top.setBackground(gradient(new int[]{0xD9000000, 0x80000000, 0x00000000}, GradientDrawable.Orientation.TOP_BOTTOM));
        PlayerUi.Btn back = new PlayerUi.Btn(this, PlayerUi.Icon.BACK, null, 46, tv);
        back.setId(idBack);
        back.setContentDescription("Voltar");
        back.setOnClickListener(v -> finish());
        remote(back);
        back.setNextFocusDownId(idPlay);
        backBtn = back;
        top.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        titleLabel = text(deriveTitle(), 19, true);
        titleLabel.setTypeface(PlayerUi.font(this, 600));
        titleLabel.setSingleLine(true);
        titleLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(16);
        top.addView(titleLabel, tlp);
        box.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // centro: voltar 10 s, play/pausa, avançar 10 s
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        PlayerUi.Btn rw = new PlayerUi.Btn(this, PlayerUi.Icon.REPLAY, null, 58, tv);
        rw.setContentDescription("Retroceder 10 s");
        rw.setOnClickListener(v -> skip(-10000));
        playButton = new PlayerUi.Btn(this, PlayerUi.Icon.PAUSE, null, 78, tv);
        playButton.setId(idPlay);
        playButton.setContentDescription("Reproduzir ou pausar");
        playButton.setOnClickListener(v -> togglePlay());
        PlayerUi.Btn ff = new PlayerUi.Btn(this, PlayerUi.Icon.FORWARD, null, 58, tv);
        ff.setContentDescription("Avançar 10 s");
        ff.setOnClickListener(v -> skip(10000));
        remote(rw); remote(playButton); remote(ff);
        rwBtn = rw; ffBtn = ff;
        playButton.setNextFocusUpId(idBack);
        for (View b : new View[]{rw, playButton, ff}) b.setNextFocusDownId(idSeek);
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(58), dp(58));
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(dp(78), dp(78));
        small.setMargins(dp(26), 0, dp(26), 0);
        big.setMargins(dp(26), 0, dp(26), 0);
        mid.addView(rw, small);
        mid.addView(playButton, big);
        mid.addView(ff, new LinearLayout.LayoutParams(small));
        box.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        midBox = mid;

        // faixa de baixo: linha do tempo vermelha e opções
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setPadding(dp(24), dp(36), dp(24), dp(14));
        bottom.setBackground(gradient(new int[]{0x00000000, 0x8C000000, 0xE6000000}, GradientDrawable.Orientation.TOP_BOTTOM));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipChildren(false);
        timeLabel = text("0:00", 14, true);
        lengthLabel = text("0:00", 14, true);
        for (TextView t : new TextView[]{timeLabel, lengthLabel}) {
            t.setTypeface(PlayerUi.font(this, 500));
            t.setMinWidth(dp(44));
        }
        timeLabel.setGravity(Gravity.START);
        lengthLabel.setGravity(Gravity.END);
        seek = new PlayerUi.Timeline(this, tv);
        seek.setId(idSeek);
        seek.setNextFocusUpId(idPlay);
        seek.setNextFocusDownId(idSubs);
        seek.listener = new PlayerUi.Timeline.Listener() {
            @Override public void onStart() { userSeeking = true; ui.removeCallbacks(hideControls); }
            @Override public String onMove(float frac) {
                long len = player == null ? 0 : player.getLength();
                String s = format((long) (len * (double) frac));
                timeLabel.setText(s);
                return s;
            }
            @Override public void onStop(float frac) {
                userSeeking = false;
                long len = player == null ? 0 : player.getLength();
                if (frac >= 0 && len > 0) {
                    seekHoldUntil = SystemClock.uptimeMillis() + 800;
                    long from = player.getTime(); player.setTime((long) (len * (double) frac));
                    glog("seek pela barra: " + from + " -> " + (long) (len * (double) frac) + " ms, faixa " + player.getSpuTrack() + ", atraso " + subDelayMs);
                }
                scheduleHide();
            }
        };
        if (tv) {
            // na TV: selecione a linha e use esquerda/direita para correr o filme (segurar acelera)
            remote(seek);
            seek.setOnFocusChangeListener((view, has) -> { if (has) setControlsVisible(true); else commitSeek.run(); });
        }
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.BOTTOM;
        labelLp.bottomMargin = dp(12);
        LinearLayout.LayoutParams labelLp2 = new LinearLayout.LayoutParams(labelLp);
        bar.addView(timeLabel, labelLp);
        bar.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(lengthLabel, labelLp2);
        bottom.addView(bar);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.END);
        opts.setPadding(0, dp(2), 0, 0);
        opts.setClipChildren(false); // o botão selecionado cresce sem ser cortado
        top.setClipChildren(false);
        PlayerUi.Btn subsB = new PlayerUi.Btn(this, PlayerUi.Icon.SUBS, "Legendas", 42, tv);
        subsB.setId(idSubs);
        subsB.setOnClickListener(v -> openSubPanel());
        subsBtn = subsB;
        PlayerUi.Btn audioB = new PlayerUi.Btn(this, PlayerUi.Icon.AUDIO, "Áudio", 42, tv);
        audioB.setOnClickListener(v -> chooseAudio());
        PlayerUi.Btn scaleB = new PlayerUi.Btn(this, PlayerUi.Icon.SCALE, "Tela: " + SCALE_NAMES[0], 42, tv);
        scaleBtn = scaleB;
        scaleB.setOnClickListener(v -> {
            setScaleMode(scaleIndex + 1);
            scheduleHide();
        });
        for (PlayerUi.Btn b : new PlayerUi.Btn[]{subsB, audioB, scaleB}) {
            remote(b);
            optBtns.add(b);
            b.setNextFocusUpId(idSeek);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            lp.leftMargin = dp(12);
            opts.addView(b, lp);
        }
        bottom.addView(opts);

        box.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        bottomBar = bottom;
        return box;
    }

    // ---------- controle remoto da TV ----------
    private boolean tv = false;
    private boolean backEaten = false;
    private View bottomBar;
    private View focusSink; // recebe o foco quando nenhum botão está selecionado
    private final ArrayList<View> remoteButtons = new ArrayList<>();
    private View backBtn, rwBtn, ffBtn;
    private final ArrayList<View> optBtns = new ArrayList<>();
    /** depois de um salto, o VLC ainda avisa por um instante o tempo antigo: a barra ignora isso até esta hora */
    private long seekHoldUntil = 0;

    /** botões que o controle remoto alcança (na TV): o próprio botão desenha o foco (branco, maior) */
    private void remote(View v) {
        remoteButtons.add(v);
        if (!tv) return;
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, has) -> { if (has) setControlsVisible(true); });
    }

    private boolean controlFocused() {
        View f = getCurrentFocus();
        return f != null && remoteButtons.contains(f);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (player == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        boolean visible = controlsShown;
        // o Voltar que fechou o painel de legendas não pode sair do player quando a tecla sobe
        if (k == android.view.KeyEvent.KEYCODE_BACK && panelBackEat) { if (!down) panelBackEat = false; return true; }
        if (loading) { // carregando: nenhuma tecla traz os controles; só o Voltar (que sai)
            if (k == android.view.KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
            return true;
        }
        if (subPanelOpen) {
            if (k == android.view.KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0) { panelBackEat = true; if (detailsView != null) closeDetails(); else closeSubPanel(); }
                return true;
            }
            switch (k) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_TAB:
                    return super.dispatchKeyEvent(e); // a navegação normal entre os botões do painel
                default:
                    return true;
            }
        }
        if (down && tv && (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN || k == android.view.KeyEvent.KEYCODE_DPAD_UP))
            android.util.Log.d("GFPlayer", "recebeu tecla " + k + "; foco em " + (getCurrentFocus() == null ? "nada" : getCurrentFocus().getContentDescription()) + "; controlFocused=" + controlFocused() + "; controles=" + controlsShown);
        switch (k) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down) { togglePlay(); setControlsVisible(true); }
                return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                // PLAY só toca e PAUSE só pausa (antes os dois alternavam: PLAY com o vídeo tocando pausava)
                if (down) { if (player.isPlaying() == (k == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)) togglePlay(); setControlsVisible(true); }
                return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) skip(30000);
                return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) skip(-30000);
                return true;
            case android.view.KeyEvent.KEYCODE_BACK:
                // na TV, com os controles à mostra: o Voltar só os esconde (o segundo Voltar sai)
                if (tv && down && e.getRepeatCount() == 0 && visible) {
                    setControlsVisible(false);
                    backEaten = true;
                    return true;
                }
                if (tv && backEaten) {
                    if (!down) backEaten = false;
                    return true;
                }
                return super.dispatchKeyEvent(e);
        }
        if (!tv) return super.dispatchKeyEvent(e);
        if (getCurrentFocus() == seek) {
            switch (k) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) scrub(k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, e.getRepeatCount());
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) { if (pendingSeek >= 0) commitSeek.run(); else togglePlay(); }
                    return true;
            }
        }
        // controles escondidos ou nenhum botão selecionado: OK pausa, esquerda/direita correm 10 s (segurar acelera)
        if (!controlFocused()) {
            switch (k) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) { togglePlay(); setControlsVisible(true); }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) { setControlsVisible(true); scrub(k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, e.getRepeatCount()); }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    // cima/baixo: mostra os controles e seleciona o play (dali chega na linha do tempo, Legendas...)
                    if (down) { setControlsVisible(true); if (!playButton.requestFocus()) playButton.post(playButton::requestFocus); }
                    return true;
            }
        } else if (down) {
            scheduleHide();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN || k == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                // ordem fixa: voltar / (-10, play, +10) / linha do tempo / opções
                View cur = getCurrentFocus(), nxt = null;
                boolean mid = cur == playButton || cur == rwBtn || cur == ffBtn;
                if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) nxt = cur == backBtn ? playButton : mid ? seek : cur == seek ? optBtns.get(0) : null;
                else nxt = mid ? backBtn : cur == seek ? playButton : optBtns.contains(cur) ? seek : null;
                boolean got = nxt != null && nxt.requestFocus();
                android.util.Log.d("GFPlayer", "tecla " + k + ": de " + (cur == null ? "nada" : cur.getContentDescription()) + " para " + (nxt == null ? "nada" : nxt.getContentDescription()) + " -> " + got + "; agora " + (getCurrentFocus() == null ? "nada" : getCurrentFocus().getContentDescription()));
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- correr a linha do tempo com o controle (TV) ----------
    private long pendingSeek = -1, scrubBase = 0;
    private final Runnable commitSeek = () -> {
        if (pendingSeek < 0 || player == null) return;
        seekHoldUntil = SystemClock.uptimeMillis() + 800;
        player.setTime(pendingSeek);
        pendingSeek = -1;
        userSeeking = false;
        seek.setScrub(false, 0f, null);
        scheduleHide();
    };

    private static String signedClock(long ms) {
        long s = Math.abs(ms) / 1000;
        String v = s >= 60 ? (s / 60) + " min" + (s % 60 != 0 ? " " + (s % 60) + " s" : "") : s + " s";
        return (ms >= 0 ? "+" : "−") + v;
    }

    private void scrub(int dir, int repeat) {
        if (player == null) return;
        long len = player.getLength();
        if (len <= 0) return;
        long step = repeat < 6 ? 10000 : repeat < 20 ? 30000 : 60000;
        if (pendingSeek < 0) { pendingSeek = player.getTime(); scrubBase = pendingSeek; }
        pendingSeek = Math.max(0, Math.min(len - 1000, pendingSeek + dir * step));
        userSeeking = true;
        ui.removeCallbacks(hideControls);
        seek.setScrub(true, pendingSeek / (float) len, format(pendingSeek) + "   " + signedClock(pendingSeek - scrubBase));
        timeLabel.setText(format(pendingSeek));
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, 600);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        scheduleHide();
    }

    private void skip(long ms) { skip(ms, true); }

    private void skip(long ms, boolean showControls) {
        if (player == null || loading) return;
        long len = player.getLength();
        long t = Math.max(0, player.getTime() + ms);
        if (len > 0) t = Math.min(t, len - 1000);
        seekHoldUntil = SystemClock.uptimeMillis() + 800;
        final long from = player.getTime();
        player.setTime(t);
        glog("seek: " + from + " -> " + t + " ms, faixa de legenda " + player.getSpuTrack() + ", atraso " + subDelayMs + " ms");
        ui.postDelayed(() -> { if (player != null) glog("seek: 2 s depois: posição " + player.getTime() + " ms, faixa de legenda " + player.getSpuTrack() + ", atraso " + subDelayMs + " ms, tocando=" + player.isPlaying()); }, 2000);
        if (len > 0) { seek.setProgress(t / (float) len); timeLabel.setText(format(t)); }
        if (showControls) setControlsVisible(true);
    }

    private void chooseAudio() {
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
        pickTrack("Áudio", tracks, player.getAudioTrack(), id -> player.setAudioTrack(id));
    }

    // ---------- legendas em português ----------
    // Baixa as legendas (dos addons, ou direto do OpenSubtitles do Stremio) para arquivos .srt
    // no celular e liga no VLC. Arquivo local com extensão é o jeito mais confiável para o VLC.
    private final ArrayList<SubtitleSync.Cand> cands = new ArrayList<>();
    private final ArrayList<SubtitleSync.Cand> attached = new ArrayList<>();
    private final ArrayList<String> subLabels = new ArrayList<>();
    private boolean subsDownloaded = false, subsAttached = false, userPickedSub = false;
    private int attachTries = 0;
    /** diagnóstico mostrado no topo do menu de legendas */
    private String diagId = "Procurando legendas…", diagRef = "", diagHash = "…", diagEmb = "";
    private static final int NO_TRACK = Integer.MIN_VALUE;
    private int embeddedPtId = NO_TRACK;
    /** a referência de tempo é confiável (outro idioma que casa com o hash, ou PT que casa com o hash) */
    private volatile boolean refTrusted = false;

    private static void glog(String s) { SubtitleSync.log(s); }

    private static String shortReason(String s) {
        if (s == null) return "?";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static String langShort(String lang) {
        if (lang == null || lang.isEmpty()) return "?";
        if (lang.equals("eng") || lang.equals("en")) return "EN";
        return lang.toUpperCase(Locale.ROOT);
    }

    private void loadSubtitlesInBackground() {
        new Thread(() -> {
            // 1) hash do arquivo (como o Stremio desktop); o link do debrid pode demorar a resolver
            SubtitleSync.VideoHash vh = SubtitleSync.computeHashTimeboxed(url, 25000);
            String fname = SubtitleSync.fileNameOf(url, vh);
            srcHash = vh.hash;
            srcName = fname;
            if (vh.hash != null) glog("hash=" + vh.hash + " tamanho=" + vh.size + " arquivo=" + fname);
            else glog("hash não calculado: " + vh.error + "; arquivo=" + fname);

            // 2) busca no addon com os extras (hash/tamanho/nome); os resultados do hash vêm primeiro
            SubtitleSync.SearchResult sr = null;
            if (metaId != null && metaType != null) {
                String extras = SubtitleSync.extras(vh, fname);
                if (!extras.isEmpty()) sr = SubtitleSync.search(metaType, metaId, extras);
                if (sr == null || sr.pt.isEmpty()) {
                    SubtitleSync.SearchResult plain = SubtitleSync.search(metaType, metaId, "");
                    if (sr == null) sr = plain;
                    else {
                        sr.pt.addAll(plain.pt);
                        if (sr.refs.isEmpty()) { sr.refs.addAll(plain.refs); sr.refsAssumed = plain.refsAssumed; }
                        if (!sr.ok) sr.ok = plain.ok;
                    }
                }
            }
            int hashHits = 0;
            if (sr != null) {
                for (SubtitleSync.Found f : sr.pt) if (f.hashMatch) hashHits++;
                for (SubtitleSync.Found f : sr.refs) if (f.hashMatch) hashHits++;
            }
            final String hashLine = hashHits > 0 ? "sim" : vh.hash != null ? "calculado, sem casamento" : "não";
            String idLine;
            if (vh.hash == null) idLine = "Vídeo não identificado (sem hash: " + shortReason(vh.error) + ")";
            else if (sr == null || !sr.ok) idLine = "Hash calculado, mas a busca de legendas falhou";
            else if (!sr.sawMatchField) idLine = "Hash calculado (o addon não confirma a correspondência)";
            else if (hashHits > 0) idLine = "Vídeo identificado ✓";
            else idLine = "Hash calculado, sem correspondência no OpenSubtitles";
            glog(idLine + (sr != null ? "; addon: " + sr.total + " itens, PT=" + sr.pt.size() + ", referências=" + sr.refs.size()
                    + (sr.refsAssumed ? " (palpite)" : "") + ", hash igual=" + hashHits : ""));

            ArrayList<SubtitleSync.Found> merged = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            if (sr != null) for (SubtitleSync.Found f : sr.pt) if (seen.add(f.url)) merged.add(f);
            for (String u : subs) if (u != null && u.startsWith("http") && seen.add(u)) merged.add(new SubtitleSync.Found(u, false, "por"));
            while (merged.size() > 14) merged.remove(merged.size() - 1);
            glog("candidatas para baixar: " + merged.size() + " (do site: " + subs.length + ")");

            java.io.File dir = new java.io.File(getCacheDir(), "legendas");
            if (!dir.exists()) dir.mkdirs();
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            // baixa várias ao mesmo tempo; cada uma é convertida para UTF-8 e só fica se for mesmo português
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            // referências de tempo (outro idioma, nunca mostradas)
            ArrayList<java.util.concurrent.Future<long[]>> refFut = new ArrayList<>();
            ArrayList<String> refLangs = new ArrayList<>();
            // referência "de palpite" (o addon não diz quem casa com o hash): outra versão do filme daria um tempo errado com cara de certo; na dúvida não usa
            if (sr != null && !sr.refsAssumed) for (SubtitleSync.Found rf : sr.refs) {
                final String ru = rf.url;
                refLangs.add(rf.lang);
                refFut.add(pool.submit((java.util.concurrent.Callable<long[]>) () -> fetchStarts(ru)));
            }
            ArrayList<java.util.concurrent.Callable<SubtitleSync.Cand>> jobs = new ArrayList<>();
            for (int i = 0; i < merged.size(); i++) {
                final int n = i;
                final SubtitleSync.Found fo = merged.get(i);
                jobs.add(() -> fetchSubtitle(fo, n, new java.io.File(dir, (n + 1) + ".srt")));
            }
            ArrayList<SubtitleSync.Cand> ok = new ArrayList<>();
            try {
                for (java.util.concurrent.Future<SubtitleSync.Cand> f : pool.invokeAll(jobs, 20, java.util.concurrent.TimeUnit.SECONDS)) {
                    try { if (!f.isCancelled()) { SubtitleSync.Cand c = f.get(); if (c != null) ok.add(c); } } catch (Exception ignored) {}
                }
            } catch (InterruptedException ignored) {}
            ArrayList<long[]> refStarts = new ArrayList<>();
            String refLang = "";
            for (int i = 0; i < refFut.size(); i++) {
                try {
                    long[] st = refFut.get(i).get(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (st != null && st.length >= 20) { refStarts.add(st); if (refLang.isEmpty()) refLang = refLangs.get(i); }
                } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            String[] testRefs = getIntent().getStringArrayExtra("gfTestRefs"); // só para os testes: referências de tempo conhecidas (links de legendas)
            if (testRefs != null) for (String tr : testRefs) { long[] st = fetchStarts(tr); if (st != null && st.length >= 20) refStarts.add(st); }
            refStartsAll = new ArrayList<>(refStarts);
            glog("legendas PT válidas: " + ok.size() + " de " + merged.size() + "; referências baixadas: " + refStarts.size());

            // 3) alinha cada legenda PT à referência (escala x deslocamento)
            StringBuilder lg = new StringBuilder("alinhamento com " + refStarts.size() + " referência(s):");
            SubtitleSync.AlignReport ar = SubtitleSync.alignAll(ok, refStarts, lg);
            int nAligned = ar.aligned;
            glog(lg.toString() + "\n  alinhadas: " + nAligned);
            String refLine;
            if (ar.refKind.equals("outro idioma")) refLine = "outro idioma (" + langShort(refLang) + ")" + (sr != null && sr.refsAssumed ? ", palpite" : ", do hash") + (nAligned == 0 ? ", nenhuma legenda casou" : "");
            else if (ar.refKind.equals("PT do hash")) refLine = "PT do hash";
            else if (ar.refKind.equals("consenso")) refLine = "nenhuma de tempo; consenso entre " + ar.ptCount + " PT (maior grupo: " + ar.consensusMax + ") escolhe a legenda certa, não alinha";
            else refLine = (sr != null && sr.refsAssumed && !sr.refs.isEmpty()) ? "nenhuma (o addon não confirma o hash: referência de palpite ignorada)"
                    : (sr != null && !sr.refs.isEmpty()) ? "nenhuma (a referência não baixou)" : "nenhuma";

            if (ar.pieced > 0) refLine += "; " + ar.pieced + " por partes (cortes)";
            if (ar.freeScaled > 0) refLine += "; " + ar.freeScaled + " com escala livre";
            refTrusted = (ar.refKind.equals("outro idioma") && sr != null && !sr.refsAssumed) || ar.refKind.equals("PT do hash");
            final String fIdLine = idLine, fRefLine = refLine;
            ui.post(() -> { cands.addAll(ok); candsSnap = new ArrayList<>(ok); diagId = fIdLine; diagRef = fRefLine; diagHash = hashLine; subsDownloaded = true; attachReadySubs(); });
        }).start();
    }

    /** texto UTF-8 de uma legenda baixada (null se não for legenda): gzip/zip, BOM, UTF-16, cp1252, ASS, fins de linha (ver SubtitleText) */
    private static String fetchText(String u) {
        try { return SubtitleText.decode(httpGet(u)); } catch (Exception e) { return null; }
    }

    private static long[] fetchStarts(String u) {
        String t = fetchText(u);
        return t == null ? null : SubtitleAlign.alignStarts(t);
    }

    /** baixa uma legenda; devolve null se não for uma legenda válida em português */
    private static SubtitleSync.Cand fetchSubtitle(SubtitleSync.Found fo, int rank, java.io.File f) {
        try {
            String text = fetchText(fo.url);
            if (text == null) return null;
            if (!looksPortuguese(text)) return null; // legenda de outro idioma com etiqueta errada
            java.io.File out = text.startsWith("WEBVTT") ? new java.io.File(f.getParentFile(), f.getName().replace(".srt", ".vtt")) : f;
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); // sempre UTF-8: acentos corretos no VLC
            os.close();
            SubtitleSync.Cand c = new SubtitleSync.Cand();
            c.uri = Uri.fromFile(out);
            c.url = fo.url;
            c.file = out;
            c.text = text;
            c.lastMs = lastCueEnd(text);
            c.rank = rank;
            c.hashMatch = fo.hashMatch;
            c.rel = fo.rel;
            return c;
        } catch (Exception e) { return null; }
    }

    private static boolean looksPortuguese(String text) { return SubtitleText.looksPortuguese(text); }

    /** fim da última fala da legenda (ms): serve para ver se ela combina com a duração do filme */
    static long lastCueEnd(String text) { return SubtitleText.lastCueEnd(text); }

    /** legendas que já vinham no arquivo (as que forem adicionadas depois são as baixadas, em português) */
    private final java.util.HashSet<Integer> embeddedSpu = new java.util.HashSet<>();
    private boolean embeddedKnown = false;

    /** legenda PT/BR que veio dentro do arquivo (a primeira que não seja "forçada"); NO_TRACK se não houver */
    private int findEmbeddedPt(MediaPlayer.TrackDescription[] t) { return findEmbeddedPt(t, true); }

    /** allowForced=false: ignora a faixa "forçada" (só traduz falas estrangeiras; escolhê-la no lugar da legenda completa deixaria o filme sem diálogo legendado) */
    private int findEmbeddedPt(MediaPlayer.TrackDescription[] t, boolean allowForced) {
        if (t == null) return NO_TRACK;
        int first = NO_TRACK;
        for (MediaPlayer.TrackDescription d : t) {
            if (d.id == -1 || !isPortuguese(d.name)) continue;
            if (embeddedKnown && !embeddedSpu.contains(d.id)) continue;
            String n = d.name.toLowerCase(Locale.ROOT);
            if (n.contains("forced") || n.contains("forçad") || n.contains("forcad") || n.contains("foreign")) { if (first == NO_TRACK) first = d.id; continue; }
            return d.id;
        }
        return allowForced ? first : NO_TRACK;
    }

    /** liga as legendas baixadas: as alinhadas à referência primeiro; a legenda PT embutida no arquivo (se houver) fica ativa */
    private void attachReadySubs() {
        if (player == null || !playing || !subsDownloaded || subsAttached) return;
        long len = player.getLength();
        if (len <= 0 && attachTries++ < 12) { ui.postDelayed(this::attachReadySubs, 1000); return; }
        subsAttached = true;
        MediaPlayer.TrackDescription[] t = player.getSpuTracks();
        embeddedKnown = true;
        if (t != null) for (MediaPlayer.TrackDescription d : t) embeddedSpu.add(d.id);
        // a faixa embutida "forçada" só vale se não houver NENHUMA legenda baixada para mostrar no lugar dela
        embeddedPtId = findEmbeddedPt(t, cands.isEmpty());
        glog("legenda PT embutida: " + (embeddedPtId == NO_TRACK ? "não há" : "faixa " + embeddedPtId));
        diagEmb = embeddedPtId == NO_TRACK ? "" : refTrusted ? "PT embutida mantida (há referência confiável do hash; o texto embutido não é lido)"
                : "PT embutida mantida, sem validação (sem referência de tempo confiável)";
        if (cands.isEmpty()) return;
        StringBuilder lg = new StringBuilder("ranking das legendas:");
        ArrayList<SubtitleSync.Cand> use = SubtitleSync.rank(cands, len, srcName, lg);
        glog(lg.toString());
        final boolean embeddedFirst = embeddedPtId != NO_TRACK;
        for (int i = 0; i < use.size() && i < 6; i++) {
            SubtitleSync.Cand c = use.get(i);
            { Uri b = blankSubtitle(i + 1); if (b != null) c.uri = b; }   // o app desenha esta legenda; o libVLC recebe o arquivo em branco (mesma faixa, atraso e seek sem lacuna)
            try {
                player.addSlave(0 /* legenda */, c.uri, i == 0 && !embeddedFirst);
                addedSubs++;
                attached.add(c);
                subLabels.add("Português " + (subLabels.size() + 1) + (c.sparse ? " (poucas falas)" : ""));
            } catch (Exception ignored) {}
        }
        if (embeddedFirst && !userPickedSub) player.setSpuTrack(embeddedPtId);
        if (!use.isEmpty()) {
            SubtitleSync.Cand f = use.get(0);
            glog("escolhida: " + (embeddedFirst ? "embutida (faixa " + embeddedPtId + ")" : "candidata #" + f.rank)
                    + "; melhor baixada: #" + f.rank + " alinhada=" + f.aligned + " nota=" + String.format(Locale.ROOT, "%.2f", f.alignScore)
                    + " deslocamento=" + f.alignOffsetMs + "ms hash=" + f.hashMatch + " corrigida=" + f.fixed + "; " + addedSubs + " no menu");
        }
        syncDelayToTrack(embeddedFirst && !userPickedSub ? embeddedPtId : player.getSpuTrack());
        ui.postDelayed(() -> {
            if (player == null) return;
            if (embeddedFirst && !userPickedSub) player.setSpuTrack(embeddedPtId);
            syncDelayToTrack(player.getSpuTrack());
        }, 1500);
        // a legenda baixada escolhida por addSlave pode demorar a ficar ativa: confere de novo
        ui.postDelayed(() -> { if (player != null) syncDelayToTrack(player.getSpuTrack()); }, 4000);
    }

    private void selectEmbeddedPortugueseLater() {
        // liga a PT/BR que vier dentro do próprio arquivo (vem perfeitamente sincronizada); se não houver nada em português, desliga
        ui.postDelayed(() -> {
            if (player == null) return;
            MediaPlayer.TrackDescription[] t = player.getSpuTracks();
            if (t == null) return;
            int id = findEmbeddedPt(t, cands.isEmpty() && !subsDownloaded);
            if (id != NO_TRACK) { embeddedPtId = id; if (!userPickedSub) { player.setSpuTrack(id); syncDelayToTrack(id); } return; }
            if (addedSubs > 0) return;
            player.setSpuTrack(-1);
        }, 2500);
    }

    private static boolean looksLikeVtt(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 16), java.nio.charset.StandardCharsets.UTF_8);
        return head.replace("\uFEFF", "").startsWith("WEBVTT");
    }

    static byte[] httpGet(String u) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GabiFlix");
        try {
            if (c.getResponseCode() >= 400) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > 5_000_000) break; }
            in.close();
            return buf.toByteArray();
        } finally { c.disconnect(); }
    }

    private static boolean isPortuguese(String name) { return name != null && PT.matcher(name).find(); }
    private boolean isDownloaded(int id) { return addedSubs > 0 && embeddedKnown && id != -1 && !embeddedSpu.contains(id); }

    private static String signedSecs(long ms) {
        String v = String.format(Locale.ROOT, "%.1f", Math.abs(ms) / 1000.0).replace('.', ',');
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s";
    }

    // ---------- painel de legendas: lista + sincronização automática + ajuste manual ----------
    // O botão "Legendas" abre este painel (sobre o vídeo, que continua a tocar). Tudo da legenda fica aqui.
    private FrameLayout subPanel;
    private boolean subPanelOpen = false, panelBackEat = false;
    /** faixa mostrada como atual no painel (id do VLC; -1 = desativada) */
    private int panelTrack = -1;
    private LinearLayout panelList;
    private android.widget.ScrollView panelScroll;
    private TextView panelValue, panelStatus, panelAuto;
    private final ArrayList<PanelRow> panelRows = new ArrayList<>();
    private PlayerUi.Btn subsBtn;
    private boolean autoRunning = false, statusFixed = false;
    /** o estado da sincronização automática pelo áudio, em português simples (null = nada a dizer); mostrado na linha de estado do painel */
    private volatile String audioStatus;
    private volatile int audioTone;

    private static int toneColor(int tone) { return tone == AudioSyncAuto.TONE_GOOD ? 0xFF7BD88F : tone == AudioSyncAuto.TONE_WARN ? 0xFFFFC857 : 0xFFC8C8C8; }
    /** referências de tempo (outro idioma) baixadas pela thread de legendas; o motor automático usa as mesmas */
    private volatile ArrayList<long[]> refStartsAll = new ArrayList<>();
    private volatile ArrayList<SubtitleSync.Cand> candsSnap = new ArrayList<>();
    /** motor da sincronização automática: para ligar outro (ex.: o que ouve o áudio) basta trocar este campo por outro AutoSync.Engine */
    private AutoSync.Engine autoEngine = new AutoSync.RefEngine(() -> refStartsAll, () -> candsSnap);

    // ---------- sincronização pelo áudio (AudioSyncAuto): o que a tela empresta ao controlador ----------
    private AudioSyncAuto audioSync;
    private android.content.BroadcastReceiver testRx;
    private final AudioSyncAuto.Host audioHost = new AudioSyncAuto.Host() {
        @Override public boolean buffering() { return spinner != null && spinner.getVisibility() == View.VISIBLE; }
        @Override public boolean playing() { return player != null && player.isPlaying() && !buffering(); }
        @Override public long timeMs() { return player == null ? 0 : player.getTime(); }
        @Override public long durationMs() { return player == null ? 0 : player.getLength(); }
        @Override public String url() { return url; }
        @Override public SubtitleSync.Cand activeCand() { return player == null ? null : candForTrack(player.getSpuTrack(), player.getSpuTracks()); }
        @Override public String delayKey() { return player == null ? null : delayKeyFor(player.getSpuTrack(), player.getSpuTracks()); }
        @Override public int delayMs() { return subDelayMs; }
        @Override public int savedDelay(String key) { return PlayerActivity.this.savedDelay(key); }
        @Override public void applyDelay(int ms, boolean persist) {
            int before = subDelayMs;
            if (persist) setSubDelay(ms, panelValue);
            else {
                subDelayMs = Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, ms));
                applySubDelay();
                if (panelValue != null) panelValue.setText(delayLabel());
            }
            reflushSubs(before, subDelayMs);
        }
        @Override public void notice(String s) { PlayerActivity.this.notice(s); }
        @Override public String debug() {
            if (player == null) return "sem player";
            StringBuilder b = new StringBuilder("tocando=" + player.isPlaying() + " carregando=" + buffering() + " faixa=" + player.getSpuTrack() + " baixadas=" + attached.size() + " addedSubs=" + addedSubs + " embeddedKnown=" + embeddedKnown + " faixas=[");
            MediaPlayer.TrackDescription[] t = player.getSpuTracks();
            if (t != null) for (MediaPlayer.TrackDescription d : t) b.append(d.id).append(':').append(d.name).append(isDownloaded(d.id) ? "(baixada)" : "").append("; ");
            return b.append("] cand=").append(candForTrack(player.getSpuTrack(), t) != null).toString();
        }
        @Override public int audioTrackId() {
            if (player == null) return -1;
            try {
                // só nos testes: usa a N-ésima faixa de áudio da lista (para provar a captura da dublagem sem mexer no que toca)
                String idx = getIntent().getStringExtra("gfAudioTrackIdx");
                if (idx != null) {
                    int n = 0, want = Integer.parseInt(idx);
                    MediaPlayer.TrackDescription[] t = player.getAudioTracks();
                    if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id != -1 && n++ == want) return d.id;
                }
                return player.getAudioTrack();
            } catch (Throwable e) { return -1; }
        }
        @Override public void status(String text, int tone) {
            audioStatus = text;
            audioTone = tone;
            ui.post(() -> {
                if (!subPanelOpen || panelStatus == null) return;
                if (autoRunning) return;   // o botão está rodando: a linha de estado mostra "A sincronizar… N s (janela i de n)" (showAutoProgress)
                if (!statusFixed) refreshSubPanel();
            });
        }
        @Override public String subSource() {
            SubtitleSync.Cand c = player == null ? null : candForTrack(player.getSpuTrack(), player.getSpuTracks());
            return c != null && c.url != null && c.url.startsWith("emb:") ? autoSource : "externa";
        }
        @Override public long lostFrames() {
            // MediaPlayer.getMedia() faz retain() no Media (confirmado no bytecode do libVLC 3.6): quem chama tem de dar release(). Sem isso, a contagem do Media
            // nunca chegava a zero ao fechar o player e ele (com a instância inteira do libVLC que ele segura) ficava na memória a cada filme.
            IMedia m = null;
            try {
                m = player == null ? null : player.getMedia();
                Object st = m == null ? null : m.getClass().getMethod("getStats").invoke(m);
                return st == null ? -1 : ((Number) st.getClass().getField("lostPictures").get(st)).longValue();
            } catch (Throwable t) { return -1; }
            finally { if (m != null) { try { m.release(); } catch (Throwable ignored) {} } }
        }
    };

    private static final class PanelRow {
        LinearLayout view;
        View dot;
        TextView label;
        int id;
        boolean sel, foc;
    }

    private void openSubPanel() {
        if (player == null || subPanelOpen) return;
        ui.removeCallbacks(hideControls);
        subPanelOpen = true;
        statusFixed = false;
        panelTrack = player.getSpuTrack();
        buildSubPanel();
        // a sincronização do botão continua por trás do painel fechado: ao reabrir, mostra o andamento ou o último resultado
        if (autoRunning) setAutoStatus(audioStatus != null ? audioStatus : "A sincronizar…", 0xFFFFFFFF);
        else if (lastAutoMsg != null && lastAutoTrack == panelTrack) setAutoStatus(lastAutoMsg, lastAutoColor);
        ((ViewGroup) controls.getParent()).addView(subPanel, match());
        setControlsVisible(false);
        controls.setVisibility(View.GONE); // não deixa os botões de baixo roubarem o foco enquanto o painel está aberto
        refreshSubPanel();
        PanelRow cur = null;
        for (PanelRow r : panelRows) if (r.id == panelTrack) cur = r;
        if (cur == null && !panelRows.isEmpty()) cur = panelRows.get(0);
        if (cur != null) {
            final View cv = cur.view;
            cv.requestFocus();
            if (panelScroll != null) panelScroll.post(() -> panelScroll.scrollTo(0, Math.max(0, cv.getTop() - dp(48)))); // a faixa atual fica à vista mesmo com muitas
        }
    }

    private View detailsView;

    /** tela de diagnóstico: versão, aparelho, rede e as últimas linhas do registro do motor; um print dela basta para entender o que aconteceu */
    private void showDetails() {
        if (subPanel == null || detailsView != null) return;
        StringBuilder b = new StringBuilder();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            b.append("FramePlay ").append(BuildConfigHelper.versionName(this)).append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    .append(" · Android ").append(android.os.Build.VERSION.RELEASE).append(" · ").append(android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?")
                    .append(" · rede ").append(cm != null && cm.isActiveNetworkMetered() ? "com limite de dados" : "sem limite").append('\n');
        } catch (Throwable ignored) {}
        String u = url == null ? "?" : url;
        try { Uri pu = Uri.parse(u); String path = pu.getLastPathSegment(); b.append("Fonte: ").append(pu.getHost()).append(" · ").append(path == null ? "?" : path.substring(Math.max(0, path.length() - 40))).append(" · duração ").append(player == null ? 0 : player.getLength() / 60000).append(" min\n"); } catch (Throwable ignored) {}
        SubtitleSync.Cand c = activeCand();
        b.append("Legenda: ").append(c == null ? (panelTrack == -1 ? "nenhuma" : "do próprio vídeo (não é analisada)") : "baixada").append(" · atraso ").append(delayLabel()).append('\n');
        b.append("Estado: ").append(audioStatus == null ? "—" : audioStatus).append("\n\n");
        String log = SubtitleSync.recent(70, "AUDIOSYNC");
        b.append(log.isEmpty() ? "(o motor ainda não registou nada: ele começa ~25 s depois de a legenda baixada ficar ativa)\n" : log);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xF5101012);
        box.setClickable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(false);
        TextView tx = new TextView(this);
        tx.setText(b.toString());
        tx.setTextColor(0xFFE6E6E6);
        tx.setTypeface(Typeface.MONOSPACE);
        tx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tx.setPadding(dp(14), dp(46), dp(14), dp(14));
        sv.addView(tx);
        box.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView x = syncButton("Fechar");
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        x.setPadding(dp(14), dp(5), dp(14), dp(5));
        x.setOnClickListener(v -> closeDetails());
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        xl.setMargins(dp(10), dp(8), dp(14), 0);
        box.addView(x, xl);
        subPanel.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailsView = box;
        x.requestFocus();
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));   // o fim do registro (o mais recente) primeiro
    }

    private void closeDetails() {
        if (detailsView != null && detailsView.getParent() != null) ((ViewGroup) detailsView.getParent()).removeView(detailsView);
        detailsView = null;
    }

    private void closeSubPanel() {
        if (!subPanelOpen) return;
        detailsView = null;
        subPanelOpen = false;
        if (subPanel != null && subPanel.getParent() != null) ((ViewGroup) subPanel.getParent()).removeView(subPanel);
        subPanel = null;
        panelRows.clear();
        setControlsVisible(true);
        if (tv && subsBtn != null) subsBtn.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (subPanelOpen) { if (detailsView != null) closeDetails(); else closeSubPanel(); return; }
        super.onBackPressed();
    }

    /** faixas que o painel lista: Desativado, as legendas em português baixadas e as embutidas em português */
    private ArrayList<Object[]> subEntries() {
        ArrayList<Object[]> out = new ArrayList<>();
        MediaPlayer.TrackDescription[] all = player.getSpuTracks();
        int n = 0, emb = 0, embTotal = 0;
        if (all != null) for (MediaPlayer.TrackDescription d : all) if (d.id != -1 && !isDownloaded(d.id) && isPortuguese(d.name)) embTotal++;
        if (all != null) for (MediaPlayer.TrackDescription d : all) {
            if (!(d.id == -1 || isPortuguese(d.name) || isDownloaded(d.id))) continue;
            String name;
            if (d.id == -1) name = "Desativado";
            else if (isDownloaded(d.id)) { n++; name = n <= subLabels.size() ? subLabels.get(n - 1) : "Português " + n; }
            else { emb++; name = "Português (do vídeo)" + (embTotal > 1 ? " " + emb : "") + embeddedQualifier(d.name); }
            out.add(new Object[]{d.id, name});
        }
        return out;
    }

    /** " (PT-BR, forçada)" a partir do nome que o VLC dá à faixa embutida; "" se não houver nada a dizer */
    private static String embeddedQualifier(String raw) {
        String n = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        ArrayList<String> q = new ArrayList<>();
        if (n.contains("brazil") || n.contains("brasil") || n.contains("pt-br") || n.contains("pt_br") || n.contains("pob")) q.add("PT-BR");
        else if (n.contains("pt-pt") || n.contains("portugal") || n.contains("european") || n.contains("europeu")) q.add("PT-PT");
        if (n.contains("sdh") || n.matches(".*\\bcc\\b.*") || n.contains("closed caption") || n.contains("hearing") || n.contains("deaf") || n.contains("surdos")) q.add("SDH");
        if (n.contains("forced") || n.contains("forçad") || n.contains("forcad") || n.contains("foreign")) q.add("forçada");
        if (q.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < q.size(); i++) sb.append(i > 0 ? ", " : "").append(q.get(i));
        return sb.append(")").toString();
    }

    private void styleRow(PanelRow r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(r.sel ? 0x33E50914 : 0x14FFFFFF);
        g.setStroke(dp(r.foc ? 3 : 1), r.foc ? 0xFFFFFFFF : r.sel ? PlayerUi.RED : 0x1FFFFFFF);
        r.view.setBackground(g);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (r.sel) d.setColor(PlayerUi.RED); else { d.setColor(0x00000000); d.setStroke(dp(2), 0x80FFFFFF); }
        r.dot.setBackground(d);
        r.label.setTypeface(r.sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void styleAuto() {
        boolean on = panelAuto.isEnabled();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        g.setColor(!on ? 0x26FFFFFF : autoRunning ? 0x99E50914 : PlayerUi.RED);
        boolean f = panelAuto.hasFocus();
        g.setStroke(dp(f ? 3 : 1), f ? 0xFFFFFFFF : 0x2EFFFFFF);
        panelAuto.setBackground(g);
        panelAuto.setTextColor(on ? Color.WHITE : 0x99FFFFFF);
    }

    private void buildSubPanel() {
        final int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        final int cardW = Math.min(sw - dp(48), dp(820)), cardH = Math.min(dp(tv ? 380 : 340), sh - dp(24));
        final int stepSp = cardW < dp(700) ? 11 : 13;
        subPanel = new FrameLayout(this);
        subPanel.setBackgroundColor(0xB8000000);
        subPanel.setClickable(true);
        subPanel.setOnClickListener(v -> closeSubPanel());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(14));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0xF2141416);
        cb.setCornerRadius(dp(22));
        cb.setStroke(dp(1), 0x2EFFFFFF);
        card.setBackground(cb);
        card.setClickable(true); // tocar dentro do cartão não fecha
        subPanel.addView(card, new FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER));

        // cabeçalho
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Legendas", 19, true);
        title.setTypeface(PlayerUi.font(this, 600));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView details = syncButton("Detalhes");
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        details.setPadding(dp(14), dp(5), dp(14), dp(5));
        details.setOnClickListener(v -> showDetails());
        LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtl.rightMargin = dp(8);
        head.addView(details, dtl);
        TextView close = syncButton("Fechar");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setPadding(dp(14), dp(5), dp(14), dp(5));
        close.setOnClickListener(v -> closeSubPanel());
        head.addView(close);
        card.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(8);
        card.addView(body, bodyLp);

        // esquerda: lista das legendas
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        panelScroll = sv;
        sv.setFocusable(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(3), dp(3), dp(3), dp(3)); // o contorno de foco (3 dp) não é cortado
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(panelList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(sv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f));

        ArrayList<Object[]> entries = subEntries();
        panelRows.clear();
        for (Object[] en : entries) {
            final PanelRow r = new PanelRow();
            r.id = (Integer) en[0];
            r.view = new LinearLayout(this);
            r.view.setOrientation(LinearLayout.HORIZONTAL);
            r.view.setGravity(Gravity.CENTER_VERTICAL);
            r.view.setPadding(dp(12), dp(8), dp(12), dp(8));
            r.view.setMinimumHeight(dp(42));
            r.view.setFocusable(true);
            r.view.setClickable(true);
            r.dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(11), dp(11));
            dlp.rightMargin = dp(10);
            r.view.addView(r.dot, dlp);
            r.label = text((String) en[1], 14, false);
            r.label.setMaxLines(2);
            r.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.view.addView(r.label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.view.setOnFocusChangeListener((v, has) -> { r.foc = has; styleRow(r); });
            r.view.setOnClickListener(v -> pickPanelTrack(r.id));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            panelList.addView(r.view, rlp);
            panelRows.add(r);
        }
        if (entries.size() <= 1) {
            TextView none = text(subsDownloaded ? "Não há legendas em português para este vídeo." : "A procurar legendas…", 13, false);
            none.setTextColor(0xFFA0A0A0);
            none.setPadding(dp(4), dp(6), dp(4), 0);
            panelList.addView(none);
        }

        // direita: sincronização automática + ajuste manual
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f);
        rightLp.leftMargin = dp(16);
        body.addView(right, rightLp);

        panelAuto = new TextView(this);
        panelAuto.setText("Sincronizar automaticamente");
        panelAuto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        panelAuto.setTypeface(Typeface.DEFAULT_BOLD);
        panelAuto.setGravity(Gravity.CENTER);
        panelAuto.setSingleLine(true);
        panelAuto.setPadding(dp(14), dp(12), dp(14), dp(12));
        panelAuto.setFocusable(true);
        panelAuto.setClickable(true);
        panelAuto.setOnFocusChangeListener((v, has) -> styleAuto());
        panelAuto.setOnClickListener(v -> onAutoClick());
        right.addView(panelAuto, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panelStatus = text("", 12, false);
        panelStatus.setTextColor(0xFFC8C8C8);
        panelStatus.setGravity(Gravity.CENTER);
        panelStatus.setMinLines(2);
        panelStatus.setPadding(dp(4), dp(6), dp(4), dp(6));
        panelStatus.setOnLongClickListener(v -> { showDetails(); return true; });   // toque longo na linha de estado = os detalhes do motor (para diagnóstico)
        right.addView(panelStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(2); llp.bottomMargin = dp(6);
        right.addView(line, llp);

        FrameLayout manualHead = new FrameLayout(this);
        TextView mt = text("Ajuste manual", 14, true);
        manualHead.addView(mt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        panelValue = text(delayLabel(), 18, true);
        panelValue.setGravity(Gravity.CENTER);
        TextView reset = syncButton("Repor");
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        reset.setPadding(dp(12), dp(3), dp(12), dp(3));
        reset.setOnClickListener(v -> changeSubDelay(0, true, panelValue));
        manualHead.addView(reset, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        right.addView(manualHead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // atraso positivo = a legenda aparece MAIS TARDE (setSpuDelay do libVLC)
        TextView hint = text("− legenda mais cedo  ·  + legenda mais tarde", 11, false);
        hint.setTextColor(0xFFA0A0A0);
        hint.setPadding(0, dp(2), 0, dp(6));
        right.addView(hint);

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        steps.setClipChildren(false);
        TextView[] neg = {stepButton("−10 s", -10000, panelValue), stepButton("−5 s", -5000, panelValue), stepButton("−1 s", -1000, panelValue)};
        TextView[] pos = {stepButton("+1 s", 1000, panelValue), stepButton("+5 s", 5000, panelValue), stepButton("+10 s", 10000, panelValue)};
        for (TextView b : neg) addStep(steps, b, stepSp);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(cardW < dp(700) ? 62 : 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        steps.addView(panelValue, vlp);
        for (TextView b : pos) addStep(steps, b, stepSp);
        right.addView(steps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hold = text("Segure um botão para repetir.", 11, false);
        hold.setTextColor(0xFF8A8A8A);
        hold.setPadding(0, dp(6), 0, 0);
        right.addView(hold);
    }

    private void addStep(LinearLayout row, TextView b, int sp) {
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setPadding(dp(2), dp(10), dp(2), dp(10));
        b.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        row.addView(b, lp);
    }

    /** marca a faixa atual, o atraso e o estado do botão automático */
    private void refreshSubPanel() {
        if (!subPanelOpen || panelAuto == null) return;
        for (PanelRow r : panelRows) { r.sel = r.id == panelTrack; styleRow(r); }
        if (panelValue != null) panelValue.setText(delayLabel());
        SubtitleSync.Cand a = activeCand();
        boolean can = a != null && a.text != null;
        boolean any = panelTrack != -1;
        panelAuto.setEnabled(any); // desligar o botão durante a espera tiraria o foco do controle: o clique é que responde "já estou a sincronizar"
        styleAuto();
        if (!autoRunning && !statusFixed) {
            String as = audioStatus;
            if (can && as != null) { panelStatus.setTextColor(toneColor(audioTone)); panelStatus.setText(as); }
            else if (can) { panelStatus.setTextColor(0xFFC8C8C8); panelStatus.setText("Acerta o tempo da legenda sozinha, sem parar o filme."); }
            else if (panelTrack == -1) { panelStatus.setTextColor(0xFFC8C8C8); panelStatus.setText("Escolha uma legenda para a poder sincronizar."); }
            else { panelStatus.setTextColor(0xFFC8C8C8); panelStatus.setText("Legenda do próprio vídeo: o app lê o texto dela e acerta o tempo pelo áudio. Se não der, usa os botões abaixo."); }
        }
    }

    /** a legenda baixada da faixa que o painel mostra (null se for embutida ou desativada) */
    private SubtitleSync.Cand activeCand() {
        return player == null || panelTrack == -1 ? null : candForTrack(panelTrack, player.getSpuTracks());
    }

    private void pickPanelTrack(int id) {
        if (player == null) return;
        userPickedSub = true;
        boolean okSet = player.setSpuTrack(id);
        glog("painel: escolheu a faixa " + id + " (setSpuTrack=" + okSet + ", VLC diz agora faixa " + player.getSpuTrack() + ", tempo " + player.getTime() + " ms)");
        ui.postDelayed(() -> { if (player != null) glog("painel: 2 s depois o VLC está na faixa " + player.getSpuTrack() + ", tempo " + player.getTime() + " ms"); }, 2000);
        panelTrack = id;
        statusFixed = false;
        lastAutoMsg = null;
        syncDelayToTrack(id);
        refreshSubPanel();
    }

    private void setAutoStatus(String s, int color) {
        statusFixed = true;
        if (panelStatus == null) return;
        panelStatus.setTextColor(color);
        panelStatus.setText(s);
    }

    private String lastAutoMsg;
    private int lastAutoColor, lastAutoTrack = -2;

    // ---------- botão "Sincronizar automaticamente" ----------
    // O toque SEMPRE termina numa resposta visível dentro de autoBudgetMs (300 s):
    //   "Legenda sincronizada (+X,X s)" | "Legenda já estava certa" | "Não consegui confirmar pelo áudio — ajusta à mão" (e o painel do ajuste manual abre sozinho com o motivo).
    // Nunca ignora o toque em silêncio (durante uma análise responde "Já estou a sincronizar…"), nunca usa o resultado guardado (sempre analisa de novo)
    // e funciona também com a legenda EMBUTIDA no MKV (o texto dela é lido pelo índice do arquivo, ver EmbeddedSubs.java, e passa a ser desenhado pelo app).
    /** tempo máximo de um toque (leitura da legenda embutida + áudio); só nos testes o intent gfSyncBudgetSec encurta */
    private long autoBudgetMs = 300_000L;
    /** geração do toque: o resultado de uma geração antiga (desistida por tempo ou superada) é descartado */
    private int autoGen = 0, autoAttempts = 0, autoTrack = -1, autoEditsAtStart = 0;
    private long autoStartedAt;
    private volatile String autoProgress = "";
    private String autoWhat = "A sincronizar…", autoSource = "externa";
    /** ajustes manuais feitos pelo usuário (botões do painel, segurar, teclado): a sincronização só descarta o resultado se este número mudou durante a análise */
    private int userEdits = 0;
    /** legenda embutida cujo texto foi lido: nome da faixa -> {legenda virtual desenhada pelo app, id da faixa dela no libVLC} */
    private final java.util.HashMap<String, Object[]> embVirtual = new java.util.HashMap<>();
    private Runnable closePanelSoon;
    private final Runnable autoTickRun = new Runnable() {
        @Override public void run() {
            if (!autoRunning || player == null || isFinishing()) return;
            long el = SystemClock.uptimeMillis() - autoStartedAt;
            if (el >= autoBudgetMs) {
                autoGen++;                               // o resultado que chegar depois é descartado
                if (audioSync != null) audioSync.cancelButton();
                glog("sincronização: tempo esgotado (" + el / 1000 + " s): desisto e entrego o ajuste manual");
                concludeAutoSync("tempo_esgotado", "passou de " + autoBudgetMs / 1000 + " s sem terminar (rede lenta, o filme ainda a carregar ou o servidor não responde)", null,
                        "Não consegui confirmar pelo áudio — ajusta à mão",
                        "Demorou demais a ouvir o filme (rede lenta ou o filme ainda a carregar). Ajuste à mão ou toque em Sincronizar de novo mais tarde.");
                return;
            }
            showAutoProgress();
            ui.postDelayed(this, 1000);
        }
    };

    /** o aviso "A sincronizar… N s (janela i de n)" fica na tela enquanto dura a análise (e na linha de estado do painel, se ele estiver aberto) */
    private void showAutoProgress() {
        String t = autoWhat + " " + (SystemClock.uptimeMillis() - autoStartedAt) / 1000 + " s" + autoProgress;
        noticeQuiet(t, 3000);
        if (subPanelOpen && panelStatus != null) setAutoStatus(t, 0xFFFFFFFF);
    }

    private void onAutoClick() { startAutoSync(panelTrack, true); }

    private void startAutoSync(int track, boolean fromPanel) {
        if (player == null || isFinishing()) return;
        if (autoRunning) {
            notice("Já estou a sincronizar… " + (SystemClock.uptimeMillis() - autoStartedAt) / 1000 + " s" + autoProgress, 4000);
            return;
        }
        if (!playing || buffering) { notice("O vídeo ainda está a carregar. Tenta de novo daqui a pouco.", 5000); return; }
        if (track == -1) { notice("Escolhe uma legenda em português (botão Legendas) e toca em Sincronizar de novo.", 6000); return; }
        if (!subsAttached) { notice("Ainda estou a preparar as legendas. Tenta de novo daqui a pouco.", 5000); return; }
        String bs = getIntent().getStringExtra("gfSyncBudgetSec");   // só nos testes
        autoBudgetMs = 300_000L;
        if (bs != null) try { autoBudgetMs = Math.max(5, Long.parseLong(bs)) * 1000L; } catch (NumberFormatException ignored) {}
        autoAttempts++;
        autoGen++;
        final int gen = autoGen;
        autoRunning = true;
        autoStartedAt = SystemClock.uptimeMillis();
        autoProgress = "";
        autoWhat = "A sincronizar…";
        autoSource = "externa";
        autoTrack = track;
        autoEditsAtStart = userEdits;
        statusFixed = true;
        glog("sincronização: toque #" + autoAttempts + " faixa=" + track + " atraso=" + subDelayMs + " ms (sempre analisa de novo; limite " + autoBudgetMs / 1000 + " s)");
        ui.removeCallbacks(autoTickRun);
        ui.postDelayed(autoTickRun, 1000);
        if (panelAuto != null) styleAuto();
        showAutoProgress();
        SubtitleSync.Cand a = candForTrack(track, player.getSpuTracks());
        if (a != null && a.text != null) beginAnalysis(a, track, gen);
        else beginEmbedded(track, gen);
        // volta direto ao filme: a análise segue por trás e o resultado aparece como aviso sobre o filme
        if (fromPanel) {
            if (closePanelSoon != null) ui.removeCallbacks(closePanelSoon);
            closePanelSoon = () -> { if (subPanelOpen && autoRunning && gen == autoGen) closeSubPanel(); };
            ui.postDelayed(closePanelSoon, 1000);
        }
    }

    /** o fim de uma tentativa: aviso visível, relatório anônimo (ntfy) e, se não deu para confirmar, o painel do ajuste manual com o motivo */
    private void concludeAutoSync(String label, String reason, AutoSync.Result res, String msg, String manual) {
        ui.removeCallbacks(autoTickRun);
        if (closePanelSoon != null) ui.removeCallbacks(closePanelSoon);
        autoRunning = false;
        glog("sincronização: resultado=" + label + " motivo=" + reason);
        notice(msg, 8000);
        try {
            CrashReporter.analise("botao_sincronizar", label, reason, res == null ? 1.0 : res.scale, res == null ? 0 : res.offsetMs, res == null ? 0 : res.z, autoSource, url,
                    player == null ? 0 : Math.max(0, player.getLength()), autoAttempts, res == null ? "" : res.report);
        } catch (Throwable ignored) {}
        lastAutoMsg = manual != null ? manual : msg;
        lastAutoColor = manual != null ? 0xFFFFC857 : 0xFF7BD88F;
        lastAutoTrack = autoTrack;
        if (player == null || isFinishing()) return;
        if (manual != null) {
            if (!subPanelOpen) { panelTrack = player.getSpuTrack(); openSubPanel(); }
            statusFixed = true;
            setAutoStatus(manual, 0xFFFFC857);
            if (panelAuto != null) styleAuto();
            glog("painel do ajuste manual: aberto=" + subPanelOpen + " motivo=\"" + manual + "\" atraso=" + subDelayMs + " ms");
        } else {
            statusFixed = subPanelOpen;
            if (subPanelOpen) { setAutoStatus(msg, 0xFF7BD88F); if (panelAuto != null) styleAuto(); }
        }
    }

    /** a análise pelo áudio (a referência primeiro, depois o áudio) numa thread à parte; o vídeo continua a tocar */
    private void beginAnalysis(final SubtitleSync.Cand active, int track, final int gen) {
        if (gen != autoGen || !autoRunning || player == null) return;
        autoTrack = track;
        autoWhat = "A sincronizar…";
        autoEditsAtStart = userEdits;
        final int delayAtStart = subDelayMs;
        final long now = player.getTime();
        final AutoSync.Engine eng = autoEngine;
        if (audioSync != null) audioSync.setButtonKey(delayKeyFor(track, player.getSpuTracks()));
        new Thread(() -> {
            AutoSync.Result r;
            try { r = eng.sync(active, now, delayAtStart); }
            catch (Throwable t) { r = AutoSync.Result.failed("erro: " + t); }
            final AutoSync.Result res = r;
            ui.post(() -> finishAutoSync(res, gen));
        }, "gf-autosync").start();
    }

    private void finishAutoSync(AutoSync.Result res, int gen) {
        if (gen != autoGen || !autoRunning) { glog("sincronização: resultado de uma tentativa antiga (já respondida) descartado"); return; }
        if (player == null || isFinishing()) { autoRunning = false; return; }
        glog("sincronização automática: status=" + res.status + " atraso=" + res.delayMs + " ms escala=" + res.scale + " (" + res.note + ")");
        if (player.getSpuTrack() != autoTrack) {
            concludeAutoSync("descartado", "a legenda foi trocada durante a análise", res, "A legenda mudou durante a análise. Toca em Sincronizar de novo.", null);
            return;
        }
        // BUG achado no Mac e igual aqui: o resultado do toque era descartado como "ajuste manual feito" quando o atraso mudava por QUALQUER motivo durante a análise
        // (o acompanhamento da escala mexe no atraso sozinho); agora só vale o que o usuário fez de verdade (userEdits)
        if (userEdits != autoEditsAtStart) {
            concludeAutoSync("descartado", "o usuário mexeu no atraso à mão durante a análise", res, "Ajuste manual feito: não alterei.", null);
            return;
        }
        if (res.status == AutoSync.FIXED) {
            boolean scaled = res.fromAudio && Math.abs(res.scale - 1.0) > 1e-9;
            int dBefore = subDelayMs;
            setSubDelay(res.delayMs, panelValue);   // guardado por fonte+legenda, como o ajuste manual
            int shown = res.delayMs;
            if (scaled) {
                // escala diferente de 1 (legenda de outro fps): o atraso certo AGORA, sem esperar o ciclo (que depois acompanha o modelo)
                shown = Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, AudioSyncAuto.delayAt(res.scale, res.offsetMs, player.getTime())));
                subDelayMs = shown;
                applySubDelay();
                if (panelValue != null) panelValue.setText(delayLabel());
            }
            reflushSubs(dBefore, subDelayMs);
            if (audioSync != null) audioSync.noteExternalDelay();
            String hint = scaled ? " — velocidade corrigida (" + CrashReporter.fpsHint(res.scale) + ")" : "";
            concludeAutoSync("sincronizada", res.note, res, "Legenda sincronizada (" + signedSecs(shown) + ")" + hint, null);
        } else if (res.status == AutoSync.OK) {
            String extra = subDelayMs != 0 ? " (" + signedSecs(subDelayMs) + " confere)" : "";
            if (audioSync != null) audioSync.noteExternalDelay();
            concludeAutoSync("ja_certa", res.note, res, "Legenda já estava certa" + extra, null);
        } else {
            String why = res.userMsg != null ? res.userMsg : "Não consegui acertar esta legenda.";
            concludeAutoSync("nao_confirmou", res.note + " | " + why, res, "Não consegui confirmar pelo áudio — ajusta à mão", why + " Ajuste à mão com os botões abaixo.");
        }
    }

    // ---------- legenda embutida: ler o texto pelo índice do MKV e desenhá-la pelo app (assim o atraso, a escala e a sincronização funcionam) ----------
    private void beginEmbedded(final int track, final int gen) {
        String found = null;
        int ordinal = -1, n = 0;
        MediaPlayer.TrackDescription[] all = player.getSpuTracks();
        if (all != null) for (MediaPlayer.TrackDescription d : all) {
            if (d.id == -1 || isDownloaded(d.id)) continue;
            if (d.id == track) { found = d.name == null ? "" : d.name; ordinal = n; }
            n++;
        }
        if (found == null) {
            concludeAutoSync("erro", "faixa de legenda não encontrada", null, "Não consegui confirmar pelo áudio — ajusta à mão", "Não achei a legenda ativa.");
            return;
        }
        final String name = found;
        final int ord = ordinal;
        autoSource = "embutida";
        Object[] known = embVirtual.get(name);
        if (known != null && trackExists((Integer) known[1])) {
            glog("legenda embutida já lida antes: uso o texto guardado");
            switchToVirtual((SubtitleSync.Cand) known[0], (Integer) known[1], name, gen);
            return;
        }
        autoWhat = "A ler a legenda do vídeo…";
        final String u = url;
        final boolean small = tv;   // Fire TV Stick (32 bits, pouca RAM): menos conexões ao mesmo tempo
        new Thread(() -> {
            EmbeddedSubs.Src src = EmbeddedSubs.source(u);
            EmbeddedSubs.Out out;
            if (src == null) { out = new EmbeddedSubs.Out(); out.why = "endereço do vídeo não suportado"; }
            else {
                out = EmbeddedSubs.extract(src, ord, () -> autoGen != gen, SystemClock.uptimeMillis() + 150_000L, small ? 3 : 4, m -> ui.post(() -> glog(m)));
                src.close();
            }
            final EmbeddedSubs.Out o = out;
            ui.post(() -> embeddedRead(o, track, name, gen));
        }, "gf-embsubs-main").start();
    }

    private boolean trackExists(int id) {
        MediaPlayer.TrackDescription[] all = player == null ? null : player.getSpuTracks();
        if (all != null) for (MediaPlayer.TrackDescription d : all) if (d.id == id) return true;
        return false;
    }

    private void embeddedRead(EmbeddedSubs.Out out, int track, String name, int gen) {
        if (gen != autoGen || !autoRunning || player == null || isFinishing()) return;
        if (out.srt == null) {
            autoSource = "embutida(sem texto: " + out.why + ")";
            glog("legenda embutida: não deu para ler o texto: " + out.why);
            concludeAutoSync("nao_confirmou", "legenda embutida sem texto: " + out.why, null, "Não consegui confirmar pelo áudio — ajusta à mão",
                    "Não consegui ler o texto da legenda do vídeo (" + out.why + "). Ela costuma já vir certa; se não estiver, ajuste à mão.");
            return;
        }
        autoSource = "embutida(texto lido: " + out.cues + " falas, " + out.codec + ", " + out.ms + " ms)";
        glog("legenda embutida lida: " + out.cues + " falas em " + out.ms + " ms (" + out.codec + "); passa a ser desenhada pelo app");
        int n = embVirtual.size();
        final SubtitleSync.Cand c = new SubtitleSync.Cand();
        c.url = "emb:" + name;
        c.text = out.srt;
        c.lastMs = lastCueEnd(out.srt);
        c.rank = 500 + n;
        Uri b = blankSubtitle(101 + n);   // "app-N.srt": o app desenha a legenda e o libVLC recebe a faixa em branco
        if (b == null) { concludeAutoSync("erro", "sem espaço para o arquivo em branco", null, "Não consegui confirmar pelo áudio — ajusta à mão", "Não consegui preparar a legenda lida do vídeo. Ajuste à mão."); return; }
        c.uri = b;
        final java.util.HashSet<Integer> before = trackIds();
        try { player.addSlave(0 /* legenda */, b, false); }
        catch (Throwable t) { concludeAutoSync("erro", "o libVLC não aceitou a legenda lida: " + t, null, "Não consegui confirmar pelo áudio — ajusta à mão", "O player não aceitou a legenda lida do vídeo. Ajuste à mão."); return; }
        addedSubs++;
        attached.add(c);
        subLabels.add("Português (do vídeo, sincronizável)");
        awaitNewTrack(before, 24, id -> {
            if (gen != autoGen || !autoRunning || player == null || isFinishing()) return;
            if (id == null) {
                concludeAutoSync("erro", "o libVLC não mostrou a faixa nova", null, "Não consegui confirmar pelo áudio — ajusta à mão", "O player não aceitou a legenda lida do vídeo. Ajuste à mão.");
                return;
            }
            embVirtual.put(name, new Object[]{c, id});
            switchToVirtual(c, id, name, gen);
        });
    }

    private java.util.HashSet<Integer> trackIds() {
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        MediaPlayer.TrackDescription[] all = player == null ? null : player.getSpuTracks();
        if (all != null) for (MediaPlayer.TrackDescription d : all) ids.add(d.id);
        return ids;
    }

    private interface IdDone { void on(Integer id); }

    private void awaitNewTrack(final java.util.HashSet<Integer> before, final int tries, final IdDone done) {
        Integer found = null;
        MediaPlayer.TrackDescription[] all = player == null ? null : player.getSpuTracks();
        if (all != null) for (MediaPlayer.TrackDescription d : all) if (!before.contains(d.id) && (found == null || d.id > found)) found = d.id;
        if (found != null) { done.on(found); return; }
        if (tries <= 0 || player == null) { done.on(null); return; }
        ui.postDelayed(() -> awaitNewTrack(before, tries - 1, done), 250);
    }

    /** passa para a legenda virtual (a embutida com o texto lido, em branco no libVLC e desenhada pelo app), mantém o atraso que a pessoa já tinha e analisa */
    private void switchToVirtual(final SubtitleSync.Cand c, final int tid, String name, final int gen) {
        final int carry = subDelayMs;
        userPickedSub = true;
        player.setSpuTrack(tid);
        panelTrack = tid;
        lastSyncKey = "\u0000";
        syncDelayToTrack(tid);
        if (carry != 0 && subDelayMs == 0) setSubDelay(carry, panelValue);
        glog("legenda embutida \"" + name + "\" agora desenhada pelo app (faixa " + tid + "); atraso mantido: " + subDelayMs + " ms");
        autoWhat = "A sincronizar…";
        ui.postDelayed(() -> {
            if (gen != autoGen || !autoRunning || player == null || isFinishing()) return;
            if (player.getSpuTrack() != tid) player.setSpuTrack(tid);
            lastSyncKey = "\u0000";
            syncDelayToTrack(tid);
            beginAnalysis(c, tid, gen);
        }, 1600);
    }

    // ---------- ajuste manual do atraso da legenda ----------
    // libVLC: setSpuDelay(microssegundos); positivo = a legenda aparece MAIS TARDE (funciona na Fire TV)
    // O atraso é guardado por VÍDEO + FONTE (hash/nome do arquivo) + LEGENDA (url da baixada ou "emb:" + nome da faixa):
    // só volta a ser aplicado quando a mesma fonte E a mesma legenda estão ativas ("sub-delay-3"; o antigo "sub-delay-2", por título, não é mais lido).
    private static final String DELAY_PREFS = "sub-delay-4";

    private String sourceId() {
        String h = srcHash;
        if (h != null) return "h:" + h;
        String n = srcName != null ? srcName : SubtitleSync.fileNameOf(url, null);
        if (n != null) return "f:" + n;
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return "u:" + Integer.toHexString(u.hashCode());
    }

    /** legenda baixada que corresponde à faixa do VLC (as baixadas são as faixas novas, na ordem em que foram adicionadas); null se for embutida/desativada */
    private SubtitleSync.Cand candForTrack(int trackId, MediaPlayer.TrackDescription[] tracks) {
        if (trackId == -1 || tracks == null) return null;
        int n = 0;
        for (MediaPlayer.TrackDescription d : tracks) {
            if (d.id == -1 || !isDownloaded(d.id)) continue;
            if (d.id == trackId) return n < attached.size() ? attached.get(n) : null;
            n++;
        }
        return null;
    }

    /** chave do atraso para a faixa; null se não há legenda ativa */
    private String delayKeyFor(int trackId, MediaPlayer.TrackDescription[] tracks) {
        if (trackId == -1 || tracks == null) return null;
        String sub = null;
        SubtitleSync.Cand c = candForTrack(trackId, tracks);
        // o atraso manual é medido em cima do arquivo JÁ reescrito pelo ajuste automático: se o ajuste for outro (hash resolveu desta vez, outra referência), o atraso guardado não vale
        if (c != null) sub = (c.url != null ? c.url : "cand" + c.rank) + "#" + c.sig;
        else for (MediaPlayer.TrackDescription d : tracks) if (d.id == trackId) sub = "emb:" + d.name;
        if (sub == null) return null;
        return "d3:" + (metaId != null ? metaId : "u") + "|" + sourceId() + "|" + sub;
    }

    private int savedDelay(String key) {
        if (key == null) return 0;
        try { return getSharedPreferences(DELAY_PREFS, MODE_PRIVATE).getInt(key, 0); } catch (Exception e) { return 0; }
    }

    /** a legenda ativa mudou (ou ficou conhecida): carrega o atraso guardado dela (ou 0) e aplica */
    private void syncDelayToTrack(int trackId) {
        if (player == null) return;
        String k = delayKeyFor(trackId, player.getSpuTracks());
        String tag = k == null ? "" : k;
        if (tag.equals(lastSyncKey)) return;
        lastSyncKey = tag;
        subDelayMs = Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, savedDelay(k)));
        applySubDelay();
        glog("atraso da legenda ativa (" + (k == null ? "nenhuma" : k) + "): " + subDelayMs + " ms");
        if (subDelayMs != 0 && !tag.equals(announcedKey)) {
            announcedKey = tag;
            notice("Legenda: atraso guardado " + signedSecs(subDelayMs));
        }
    }

    private void notice(String s) { notice(s, 3000); }

    private void notice(String s, long ms) {
        glog("AVISO na tela: " + s);
        noticeQuiet(s, ms);
    }

    /** o mesmo aviso, sem linha no registro (o andamento da sincronização se repete a cada segundo) */
    private void noticeQuiet(String s, long ms) {
        if (noticeLabel == null) return;
        noticeLabel.setText(s);
        noticeLabel.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNotice);
        ui.postDelayed(hideNotice, ms);
    }

    /**
     * O VLC lê o arquivo de legenda só para a frente: as falas que ele já mandou para o decodificador (as das próximas dezenas de segundos)
     * ficam com o tempo ANTIGO quando o atraso muda, e as que vêm depois do novo atraso só chegam quando o relógio alcança o tempo antigo:
     * a legenda some por um tempo (igual ao tamanho da mudança) e depois volta. Religar a faixa (sem mexer no vídeo) faz o VLC recomeçar
     * a legenda a partir do instante atual já com o atraso novo. Só para mudanças feitas pelo motor (o ajuste manual de 1 s não precisa).
     */
    private String delayStrategy = null;
    private void reflushSubs(int before, int after) {
        if (player == null || before == after) return;
        String st = delayStrategy;
        if (st == null) { st = getIntent().getStringExtra("gfDelayStrategy"); if (st == null) st = "plain"; delayStrategy = st; }
        int id = player.getSpuTrack();
        glog("AUDIOSYNC legenda: atraso " + before + " -> " + after + " ms, estratégia " + st + ", faixa " + id + ", posição " + player.getTime() + " ms");
        if (id == -1 || "plain".equals(st)) return;
        try {
            if ("toggle".equals(st)) { player.setSpuTrack(-1); player.setSpuTrack(id); }
            else if ("reselect".equals(st)) player.setSpuTrack(id);
            else if ("ramp".equals(st)) ramp(before, after);
            else if ("readd".equals(st)) {
                SubtitleSync.Cand c = candForTrack(id, player.getSpuTracks());
                if (c != null && c.uri != null) {
                    player.addSlave(0, c.uri, true);
                    addedSubs++;
                    attached.add(c);
                    subLabels.add("Português " + (subLabels.size() + 1));
                    glog("AUDIOSYNC legenda: religada como nova faixa (a anterior era " + id + ")");
                }
            }
        } catch (Throwable t) { glog("AUDIOSYNC religar a faixa falhou: " + t); }
    }

    private int rampCur, rampTo;
    private final Runnable rampStep = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            int diff = rampTo - rampCur;
            int step = Math.max(400, Math.abs(diff) / 12);
            rampCur += Math.abs(diff) <= step ? diff : (diff > 0 ? step : -step);
            try { player.setSpuDelay(rampCur * 1000L); } catch (Throwable ignored) {}
            if (rampCur != rampTo) ui.postDelayed(this, 300);
        }
    };

    /** muda o atraso aos poucos (o VLC só aplica de uma vez o que já passou pela leitura do arquivo) */
    private void ramp(int from, int to) {
        ui.removeCallbacks(rampStep);
        rampCur = from;
        rampTo = to;
        try { player.setSpuDelay(from * 1000L); } catch (Throwable ignored) {}
        ui.postDelayed(rampStep, 300);
    }

    private void applySubDelay() {
        if (player == null) return;
        // legenda desenhada pelo app: o atraso já é aplicado por overlayTick (tempo - subDelayMs); a faixa do libVLC é um arquivo em branco, então não há
        // por que chamar o código nativo (menos uma chamada nativa no momento em que o automático acerta a legenda)
        if (appSubs) return;
        try { player.setSpuDelay(subDelayMs * 1000L); } catch (Exception ignored) {}
    }

    private String delayLabel() { return subDelayMs == 0 ? "0,0 s" : signedSecs(subDelayMs); }

    /** define o atraso da legenda ativa (limite ±600 s), guarda por fonte+legenda, aplica e atualiza o texto */
    private void setSubDelay(int ms, TextView label) {
        subDelayMs = Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, ms));
        String k = player == null ? null : delayKeyFor(player.getSpuTrack(), player.getSpuTracks());
        if (k != null) {
            lastSyncKey = k;
            try { getSharedPreferences(DELAY_PREFS, MODE_PRIVATE).edit().putInt(k, subDelayMs).apply(); } catch (Exception ignored) {}
        }
        applySubDelay();
        if (label != null) label.setText(delayLabel());
        glog("atraso da legenda: " + subDelayMs + " ms (" + (k == null ? "sem legenda ativa, não guardado" : k) + ")");
    }

    private void changeSubDelay(int deltaMs, boolean reset, TextView label) {
        userEdits++;
        setSubDelay(reset ? 0 : subDelayMs + deltaMs, label);
        if (statusFixed && !autoRunning && subPanelOpen) { statusFixed = false; refreshSubPanel(); }
    }

    /** botão do diálogo de sincronização: selecionável com o controle remoto, com borda branca no foco */
    private TextView syncButton(String s) {
        TextView t = pill(s);
        t.setFocusable(true);
        t.setGravity(Gravity.CENTER);
        ((GradientDrawable) t.getBackground()).setColor(0x40FFFFFF);
        t.setOnFocusChangeListener((view, has) -> {
            GradientDrawable g = (GradientDrawable) view.getBackground();
            g.setStroke(dp(has ? 3 : 1), has ? 0xFFFFFFFF : 0x2EFFFFFF);
        });
        return t;
    }

    /** botão de passo: toque = um passo; segurar (toque longo, ou OK mantido no controle) repete a cada ~150 ms e acelera */
    private TextView stepButton(String label, final int deltaMs, final TextView value) {
        final TextView b = syncButton(label);
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        b.setOnClickListener(v -> changeSubDelay(deltaMs, false, value));
        final long[] since = {0, 0}; // [início do segurar, último passo do controle]
        final boolean[] held = {false};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            held[0] = true;
            stepHeld(deltaMs, since[0], value);
            ui.postDelayed(tick[0], 150);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    held[0] = false;
                    since[0] = SystemClock.uptimeMillis();
                    ui.postDelayed(tick[0], 400);
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(tick[0]);
                    if (held[0]) { held[0] = false; v.setPressed(false); return true; } // já repetiu: não conta como clique
                    return false;
                default:
                    return false;
            }
        });
        b.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER && keyCode != android.view.KeyEvent.KEYCODE_ENTER) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) { held[0] = false; return false; } // primeiro toque: clique normal
                long now = SystemClock.uptimeMillis();
                if (!held[0]) { held[0] = true; since[0] = now; since[1] = 0; }
                if (now - since[1] >= 150) { since[1] = now; stepHeld(deltaMs, since[0], value); }
                return true;
            }
            if (ev.getAction() == android.view.KeyEvent.ACTION_UP && held[0]) { held[0] = false; v.setPressed(false); return true; }
            return false;
        });
        return b;
    }

    /** um passo de quem está segurando o botão: quanto mais tempo, maior o salto */
    private void stepHeld(int deltaMs, long since, TextView value) {
        long el = SystemClock.uptimeMillis() - since;
        int mult = el < 1500 ? 1 : el < 3000 ? 3 : el < 5000 ? 10 : 30;
        changeSubDelay(deltaMs * mult, false, value);
    }

    private interface TrackSetter { void set(int id); }

    private void pickTrack(String title, MediaPlayer.TrackDescription[] tracks, int current, TrackSetter setter) {
        ui.removeCallbacks(hideControls);
        if (tracks == null || tracks.length == 0) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title).setMessage("Este vídeo não tem opções de " + title.toLowerCase(Locale.ROOT) + ".")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[tracks.length];
        int checked = -1;
        for (int i = 0; i < tracks.length; i++) {
            names[i] = tracks[i].id == -1 ? "Desativado" : tracks[i].name;
            if (tracks[i].id == current) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(names, checked, (d, which) -> { setter.set(tracks[which].id); d.dismiss(); scheduleHide(); })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void setControlsVisible(boolean show) {
        if (loading) return; // carregando: os controles não aparecem nem somem
        if (!show && controlFocused() && focusSink != null) focusSink.requestFocus();
        ui.removeCallbacks(hideControls);
        if (show != controlsShown || (show && controls.getAlpha() < 1f)) {
            controlsShown = show;
            raiseSubs(show);
            controls.animate().cancel();
            if (show) {
                if (controls.getVisibility() != View.VISIBLE) { controls.setAlpha(0f); controls.setVisibility(View.VISIBLE); }
                controls.animate().alpha(1f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            } else {
                controls.animate().alpha(0f).setDuration(240).setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> { if (!controlsShown) controls.setVisibility(View.GONE); }).start();
            }
        }
        if (show) scheduleHide();
        immersive();
    }

    // ---------- legendas acima dos botões ----------
    // Com os controles à mostra a legenda sobe para ficar acima da linha do tempo; ao esconder, volta ao lugar. Só mexe na posição
    // da vista das LEGENDAS (a segunda SurfaceView do VLC): a vista do vídeo, a superfície e a escala não são tocadas.
    private static final boolean RAISE_SUBS = true;

    private String lastViewsLog = "";

    private View subsView() {
        if (videoLayout == null) return null;
        ArrayList<View> found = new ArrayList<>();
        collectVideoViews(videoLayout, found);
        View vid = findVideoView(videoLayout);
        StringBuilder sb = new StringBuilder("vistas do vídeo:");
        for (View c : found) sb.append(' ').append(c.getClass().getSimpleName()).append(c == vid ? "(vídeo)" : "");
        if (!sb.toString().equals(lastViewsLog)) { lastViewsLog = sb.toString(); glog(lastViewsLog); }
        // a vista das legendas é a outra (SurfaceView no celular; na TV pode ser outra TextureView)
        for (View c : found) if (c != vid) return c;
        return null;
    }

    private void raiseSubs(boolean up) {
        if (!RAISE_SUBS) return;
        try {
            View sv = subsView();
            if (sv == null && subOverlay == null) return;
            float ty = 0f;
            if (up) {
                int h = ((View) controls).getHeight(), top = bottomBar == null ? 0 : bottomBar.getTop();
                ty = h > 0 && top > 0 ? -(h - top - dp(72)) : -dp(96);
                ty = Math.max(ty, -dp(160));
            }
            if (sv != null) {
                sv.animate().cancel();
                sv.animate().translationY(ty).setDuration(up ? 180 : 240).start();
            }
            if (subOverlay != null) {
                subOverlay.animate().cancel();
                subOverlay.animate().translationY(ty).setDuration(up ? 180 : 240).start();
            }
        } catch (Throwable t) { glog("legenda acima dos botões: " + t); }
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControls);
        if (player != null && player.isPlaying() && !userSeeking && messageLabel.getVisibility() != View.VISIBLE) {
            ui.postDelayed(hideControls, HIDE_DELAY_MS);
        }
    }

    // ---------- aparência ----------
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(6, 0, 1, 0x99000000);
        return t;
    }

    /** botão em "vidro" arredondado */
    private TextView pill(String s) {
        TextView t = text(s, 15, true);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x73141416);
        g.setCornerRadius(dp(22));
        g.setStroke(dp(1), 0x2EFFFFFF);
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    private GradientDrawable gradient(int[] colors, GradientDrawable.Orientation o) {
        return new GradientDrawable(o, colors);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String format(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    // ---------- ciclo de vida ----------
    private boolean resumeOnReturn = false;

    /** quem fechou a tela: fica nas migalhas; se não foi o usuário (Voltar) nem o fim do filme, manda um relatório (a causa do "voltou para a página inicial") */
    @Override
    public void finish() {
        try {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = new Throwable().getStackTrace();
            boolean user = false;
            for (int i = 1; i < Math.min(st.length, 14); i++) {
                String m = st[i].getClassName().replaceFirst(".*\\.", "") + "." + st[i].getMethodName() + ":" + st[i].getLineNumber();
                sb.append(m).append(" < ");
                if (m.contains("onBackPressed") || m.contains("performClick") || m.contains("dispatchKeyEvent")) user = true;
            }
            long t = player == null ? -1 : player.getTime(), len = player == null ? -1 : player.getLength();
            boolean fim = ended || (len > 0 && t > len - 60_000);
            glog("ciclo: finish() chamado por " + sb + " tempo=" + t + "/" + len + " usuario=" + user + " fim=" + fim);
            if (!user && !fim && !isFinishing()) CrashReporter.event("PLAYER_FECHOU_SOZINHO", "A tela do player foi fechada sem o usuário pedir.\nfinish() chamado por: " + sb + "\ntempo=" + t + " de " + len);
        } catch (Throwable ignored) {}
        super.finish();
    }

    /** diagnóstico do ciclo de vida: estado do player e das vistas de vídeo (aparece no log GabiFlixPlayer) */
    private void lifeLog(String w) {
        try {
            View v = videoLayout == null ? null : findVideoView(videoLayout);
            glog(String.format(Locale.ROOT, "ciclo: %s, tocando=%s, tempo=%d, escala=%d, atraso=%d, vista=%s %dx%d, vout=%s", w,
                    player != null && player.isPlaying(), player == null ? -1 : player.getTime(), scaleIndex, subDelayMs,
                    v == null ? "nenhuma" : v.getClass().getSimpleName(), v == null ? 0 : v.getWidth(), v == null ? 0 : v.getHeight(),
                    player != null && player.getVLCVout().areViewsAttached()));
        } catch (Throwable t) { glog("ciclo: " + w + " (erro " + t + ")"); }
    }

    /**
     * Sair do app (HOME, seletor de apps, tela apagada) destrói a superfície do vídeo. O libVLC fecha a saída de vídeo e, ao voltar,
     * a superfície nova não recebia imagem (tela toda preta; o som seguia). Por isso, ao parar, as vistas são soltas do player
     * (como faz o VLC para Android) e, ao começar de novo, são ligadas outra vez, o que abre uma saída de vídeo nova; se o filme
     * estava em pausa, um "seek" para o mesmo instante faz aparecer o quadro. O modo Preencher e o atraso da legenda são reaplicados.
     */
    private boolean viewsDetached = false, refreshFrame = false;
    private long returnTime = -1;

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && viewsDetached) reattachViews();
        lifeLog("onStart");
        if (audioSync != null) audioSync.onReturn("onStart");
    }

    @Override
    protected void onStop() {
        lifeLog("onStop");
        if (player != null && !viewsDetached && !isFinishing()) {
            try {
                returnTime = player.getTime();
                player.detachViews();
                viewsDetached = true;
            } catch (Throwable t) { glog("ciclo: não soltou as vistas: " + t); }
        }
        super.onStop();
    }

    private void reattachViews() {
        try {
            lastScaleW = -1; lastScaleH = -1;
            String gTex = getIntent().getStringExtra("gfTexture");
            player.attachViews(videoLayout, null, true, gTex != null ? "1".equals(gTex) : tv);
            viewsDetached = false;
            refreshFrame = true;
            applySubDelay();
            if (scaleIndex != 0) ui.post(this::applyScale);
            // se o evento de saída de vídeo não vier, força o quadro mesmo assim
            ui.postDelayed(this::refreshFrameNow, 2500);
        } catch (Throwable t) { glog("ciclo: não ligou as vistas: " + t); }
    }

    /** filme em pausa depois de voltar: um seek para o mesmo ponto faz a saída de vídeo nova desenhar o quadro */
    private void refreshFrameNow() {
        if (!refreshFrame || player == null || isFinishing()) return;
        refreshFrame = false;
        try {
            if (!player.isPlaying() && !resumeOnReturn) {
                long t = returnTime > 0 ? returnTime : player.getTime();
                if (t > 0) player.setTime(t);
            }
            glog("ciclo: quadro reposto, tempo=" + player.getTime());
        } catch (Throwable t) { glog("ciclo: quadro: " + t); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifeLog("onPause");
        if (audioSync != null) audioSync.onPause();
        if (player != null) { resumeOnReturn = player.isPlaying(); savePosition(true); player.pause(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifeLog("onResume");
        if (audioSync != null) audioSync.onReturn("onResume");
        if (player != null && resumeOnReturn) { resumeOnReturn = false; player.play(); }
        if (returnTime >= 0) { returnTime = -1; ui.post(() -> setControlsVisible(true)); } // volta com os controles à mostra
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (memTel != null) { memTel.stop(); memTel = null; }
        java.lang.ref.WeakReference<PlayerActivity> cr = current;
        if (cr != null && cr.get() == this) current = null;
        if (barThread != null) { barThread.quit(); barThread = null; }
        autoGen++;
        if (audioSync != null) audioSync.stop();
        if (testRx != null) { try { unregisterReceiver(testRx); } catch (Throwable ignored) {} testRx = null; }
        if (player != null) {
            savePosition(true);
            player.stop();
            try { player.detachViews(); } catch (Throwable ignored) {}
            player.release();
            player = null;
        }
        if (libVLC != null) { libVLC.release(); libVLC = null; }
        super.onDestroy();
    }
}
