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
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
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
    /** ajuste manual da legenda (ms; positivo = a legenda aparece mais tarde), lembrado por título */
    private int subDelayMs = 0;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private String url;

    private View controls;
    private TextView playButton, timeLabel, lengthLabel, messageLabel;
    private SeekBar seek;
    private ProgressBar spinner;
    private boolean userSeeking = false;
    private boolean resumeDone = false;
    private boolean ended = false;
    private long lastSaved = 0;
    private int scaleIndex = 0;
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    };
    private static final String[] SCALE_NAMES = {"Ajustar", "Preencher", "Esticar"};

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
        getSharedPreferences(LAST_PREFS, MODE_PRIVATE).edit().clear().apply();
        if (url == null) { finish(); return; }
        subDelayMs = savedSubDelay();
        loadSubtitlesInBackground();

        tv = MainActivity.isTv(this);
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

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        root.addView(spinner, sp);

        messageLabel = text("", 17, true);
        messageLabel.setGravity(Gravity.CENTER);
        messageLabel.setVisibility(View.GONE);
        messageLabel.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(messageLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        controls = buildControls();
        root.addView(controls, match());
        setContentView(root);
        if (tv) root.requestFocus();

        // toque: mostra/esconde os controles; toque duplo nas laterais: volta ou avança 10 s
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { setControlsVisible(controls.getVisibility() != View.VISIBLE); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                boolean right = e.getX() > root.getWidth() / 2f;
                skip(right ? 10000 : -10000);
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
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        // na TV (Fire TV) o vídeo vai por TextureView: com SurfaceView a imagem ficava preta (só som)
        // quando os controles sumiam, porque o Fire TV passa o vídeo para outra camada da tela
        player.attachViews(videoLayout, null, true, tv);
        player.setEventListener(this::onPlayerEvent);

        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=3000");
        player.setMedia(media);
        media.release();
        player.play();
        setControlsVisible(true);
    }

    // ---------- eventos do VLC ----------
    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                spinner.setVisibility(View.GONE);
                playButton.setText("❚❚");
                if (!resumeDone) resume();
                if (!playing) { playing = true; ui.postDelayed(this::attachReadySubs, 1500); selectEmbeddedPortugueseLater(); }
                scheduleHide();
                break;
            case MediaPlayer.Event.Paused:
                playButton.setText("▶");
                savePosition(true);
                break;
            case MediaPlayer.Event.Buffering:
                spinner.setVisibility(event.getBuffering() < 100f ? View.VISIBLE : View.GONE);
                break;
            case MediaPlayer.Event.LengthChanged:
                lengthLabel.setText(format(player.getLength()));
                break;
            case MediaPlayer.Event.TimeChanged:
                long t = player.getTime(), len = player.getLength();
                if (!userSeeking && len > 0) seek.setProgress((int) (t * 1000 / len));
                timeLabel.setText(format(t));
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
                messageLabel.setText("Não foi possível reproduzir este vídeo.\nToque em voltar e escolha outra fonte.");
                messageLabel.setVisibility(View.VISIBLE);
                setControlsVisible(true);
                break;
            default:
                break;
        }
    }

    // ---------- continuar de onde parou ----------
    private SharedPreferences prefs() { return getSharedPreferences("positions", MODE_PRIVATE); }
    private String key() { return "p" + url.hashCode(); }

    private void resume() {
        resumeDone = true;
        // aberto pelo GabiFlix: o ponto certo é o do Stremio (se foi removido do Continuar assistindo, começa do zero)
        long saved = metaId != null ? startMs : prefs().getLong(key(), 0);
        long len = player.getLength();
        if (saved > 30000 && (len <= 0 || saved < len - 60000)) player.setTime(saved);
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
    private View buildControls() {
        FrameLayout box = new FrameLayout(this);

        // faixa de cima: voltar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(16), dp(20), dp(28));
        top.setBackground(gradient(new int[]{0xB3000000, 0x00000000}, GradientDrawable.Orientation.TOP_BOTTOM));
        TextView back = pill("←  Voltar");
        back.setOnClickListener(v -> finish());
        makeRemoteFriendly(back, 0);
        top.addView(back);
        box.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // centro: voltar 10 s, play/pausa, avançar 10 s
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        TextView rw = round("−10", 22);
        rw.setOnClickListener(v -> skip(-10000));
        playButton = round("❚❚", 30);
        playButton.setOnClickListener(v -> togglePlay());
        TextView ff = round("+10", 22);
        ff.setOnClickListener(v -> skip(10000));
        makeRemoteFriendly(rw, 1);
        makeRemoteFriendly(playButton, 1);
        makeRemoteFriendly(ff, 1);
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(64), dp(64));
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(dp(84), dp(84));
        small.setMargins(dp(22), 0, dp(22), 0);
        big.setMargins(dp(22), 0, dp(22), 0);
        mid.addView(rw, small);
        mid.addView(playButton, big);
        mid.addView(ff, small);
        box.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // faixa de baixo: tempo, barra e opções
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(24), dp(28), dp(24), dp(16));
        bottom.setBackground(gradient(new int[]{0x00000000, 0xCC000000}, GradientDrawable.Orientation.TOP_BOTTOM));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        timeLabel = text("0:00", 14, true);
        lengthLabel = text("0:00", 14, true);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setFocusable(false);
        if (tv) {
            // na TV: selecione a barra e use esquerda/direita para correr o filme (segurar acelera)
            seek.setFocusable(true);
            remoteButtons.add(seek);
            seek.setOnFocusChangeListener((view, has) -> {
                GradientDrawable hl = new GradientDrawable();
                hl.setColor(0x59FFFFFF);
                hl.setCornerRadius(dp(12));
                seek.setBackground(has ? hl : null);
                if (has) setControlsVisible(true); else commitSeek.run();
            });
        }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getLength() > 0) timeLabel.setText(format(player.getLength() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; ui.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null && player.getLength() > 0) player.setTime(player.getLength() * s.getProgress() / 1000);
                scheduleHide();
            }
        });
        bar.addView(timeLabel);
        bar.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(lengthLabel);
        bottom.addView(bar);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.END);
        opts.setPadding(0, dp(8), 0, 0);
        TextView audio = pill("Áudio");
        audio.setOnClickListener(v -> chooseAudio());
        TextView subs = pill("Legendas");
        subs.setOnClickListener(v -> chooseSubtitles());
        makeRemoteFriendly(audio, 0);
        makeRemoteFriendly(subs, 0);
        TextView scale = pill("Tela: " + SCALE_NAMES[0]);
        makeRemoteFriendly(scale, 0);
        scale.setOnClickListener(v -> {
            scaleIndex = (scaleIndex + 1) % SCALES.length;
            player.setVideoScale(SCALES[scaleIndex]);
            scale.setText("Tela: " + SCALE_NAMES[scaleIndex]);
            scheduleHide();
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMargins(dp(10), 0, 0, 0);
        opts.addView(audio, gap);
        opts.addView(subs, new LinearLayout.LayoutParams(gap));
        opts.addView(scale, new LinearLayout.LayoutParams(gap));
        bottom.addView(opts);

        box.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return box;
    }

    // ---------- controle remoto da TV ----------
    private boolean tv = false;
    private boolean backEaten = false;
    private View focusSink; // recebe o foco quando nenhum botão está selecionado
    private final ArrayList<View> remoteButtons = new ArrayList<>();

    /** botões que o controle remoto alcança (na TV): ficam com borda branca quando selecionados */
    private void makeRemoteFriendly(TextView v, int oval) {
        remoteButtons.add(v);
        if (!tv) return;
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, has) -> {
            GradientDrawable g = (GradientDrawable) view.getBackground();
            g.setStroke(dp(has ? 3 : 1), has ? 0xFFFFFFFF : (oval == 1 ? 0x33FFFFFF : 0x2EFFFFFF));
            if (has) setControlsVisible(true);
        });
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
        boolean visible = controls.getVisibility() == View.VISIBLE;
        switch (k) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) { togglePlay(); setControlsVisible(true); }
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
        // controles escondidos ou nenhum botão selecionado: OK pausa, esquerda/direita andam 10 s
        if (!controlFocused()) {
            switch (k) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) { togglePlay(); setControlsVisible(true); }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) skip(-10000);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) skip(10000);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    // cima/baixo: mostra os controles e seleciona o play (dali chega em Áudio, Legendas...)
                    if (down) { setControlsVisible(true); playButton.requestFocus(); }
                    return true;
            }
        } else if (down) {
            scheduleHide();
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- correr a barra com o controle (TV) ----------
    private long pendingSeek = -1;
    private final Runnable commitSeek = () -> {
        if (pendingSeek < 0 || player == null) return;
        player.setTime(pendingSeek);
        pendingSeek = -1;
        userSeeking = false;
        scheduleHide();
    };

    private void scrub(int dir, int repeat) {
        if (player == null) return;
        long len = player.getLength();
        if (len <= 0) return;
        long step = repeat < 6 ? 10000 : repeat < 20 ? 30000 : 60000;
        if (pendingSeek < 0) pendingSeek = player.getTime();
        pendingSeek = Math.max(0, Math.min(len - 1000, pendingSeek + dir * step));
        userSeeking = true;
        ui.removeCallbacks(hideControls);
        seek.setProgress((int) (pendingSeek * 1000 / len));
        timeLabel.setText(format(pendingSeek));
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, 700);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        scheduleHide();
    }

    private void skip(long ms) {
        if (player == null) return;
        long len = player.getLength();
        long t = Math.max(0, player.getTime() + ms);
        if (len > 0) t = Math.min(t, len - 1000);
        player.setTime(t);
        setControlsVisible(true);
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
    private String diagId = "Procurando legendas…", diagRef = "";
    private static final int NO_TRACK = Integer.MIN_VALUE;
    private int embeddedPtId = NO_TRACK;

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
            if (sr != null) for (SubtitleSync.Found rf : sr.refs) {
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
            glog("legendas PT válidas: " + ok.size() + " de " + merged.size() + "; referências baixadas: " + refStarts.size());

            // 3) alinha cada legenda PT à referência (escala x deslocamento)
            StringBuilder lg = new StringBuilder("alinhamento com " + refStarts.size() + " referência(s):");
            int nAligned = SubtitleSync.alignAll(ok, refStarts, lg);
            glog(lg.toString() + "\n  alinhadas: " + nAligned);
            String refLine;
            if (refStarts.isEmpty()) refLine = (sr != null && !sr.refs.isEmpty()) ? "referência não baixou" : "sem referência";
            else refLine = "referência " + langShort(refLang) + " ✓" + (nAligned == 0 ? " (nenhuma legenda casou)" : "");

            final String fIdLine = idLine, fRefLine = refLine;
            ui.post(() -> { cands.addAll(ok); diagId = fIdLine; diagRef = fRefLine; subsDownloaded = true; attachReadySubs(); });
        }).start();
    }

    /** texto UTF-8 de uma legenda baixada (null se não for legenda) */
    private static String fetchText(String u) {
        try {
            byte[] data = httpGet(u);
            if (data == null || data.length < 20) return null;
            if ((data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) data = gunzip(data);
            String text = decodeText(data);
            if (text == null || !text.contains("-->")) return null; // não é legenda (página de erro, arquivo vazio...)
            return text.replace("﻿", "");
        } catch (Exception e) { return null; }
    }

    private static long[] fetchStarts(String u) {
        String t = fetchText(u);
        return t == null ? null : SubtitleAlign.cueStarts(t);
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
            c.file = out;
            c.text = text;
            c.lastMs = lastCueEnd(text);
            c.rank = rank;
            c.hashMatch = fo.hashMatch;
            return c;
        } catch (Exception e) { return null; }
    }

    private static byte[] gunzip(byte[] d) throws java.io.IOException {
        java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(d));
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > 5_000_000) break; }
        in.close();
        return buf.toByteArray();
    }

    /** texto da legenda: UTF-16 (com BOM), UTF-8, ou (se tiver bytes inválidos) Windows-1252, comum nas legendas antigas */
    private static String decodeText(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return new String(d, 2, d.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return new String(d, 2, d.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(d)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(d, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private static final java.util.HashSet<String> PT_W = words("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma");
    private static final java.util.HashSet<String> EN_W = words("the you and that what this with have are not but your i'm it's don't they there just know");
    private static final java.util.HashSet<String> ES_W = words("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros");
    private static java.util.HashSet<String> words(String s) { return new java.util.HashSet<>(java.util.Arrays.asList(s.split(" "))); }

    /** confere se o texto é mesmo português (conta palavras típicas de português, inglês e espanhol) */
    private static boolean looksPortuguese(String text) {
        int pt = 0, en = 0, es = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}']+").matcher(text.length() > 300000 ? text.substring(0, 300000) : text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (PT_W.contains(w)) pt++;
            if (EN_W.contains(w)) en++;
            if (ES_W.contains(w)) es++;
        }
        return pt >= 3 && pt > 2 * Math.max(en, es);
    }

    private static final java.util.regex.Pattern CUE_END = java.util.regex.Pattern.compile("-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})");

    /** fim da última fala da legenda (ms): serve para ver se ela combina com a duração do filme */
    static long lastCueEnd(String text) {
        long max = 0;
        java.util.regex.Matcher m = CUE_END.matcher(text);
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long t = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            if (t > max) max = t;
        }
        return max;
    }

    /** legendas que já vinham no arquivo (as que forem adicionadas depois são as baixadas, em português) */
    private final java.util.HashSet<Integer> embeddedSpu = new java.util.HashSet<>();
    private boolean embeddedKnown = false;

    /** legenda PT/BR que veio dentro do arquivo (a primeira que não seja "forçada"); NO_TRACK se não houver */
    private int findEmbeddedPt(MediaPlayer.TrackDescription[] t) {
        if (t == null) return NO_TRACK;
        int first = NO_TRACK;
        for (MediaPlayer.TrackDescription d : t) {
            if (d.id == -1 || !isPortuguese(d.name)) continue;
            if (embeddedKnown && !embeddedSpu.contains(d.id)) continue;
            String n = d.name.toLowerCase(Locale.ROOT);
            if (n.contains("forced") || n.contains("forçad") || n.contains("forcad")) { if (first == NO_TRACK) first = d.id; continue; }
            return d.id;
        }
        return first;
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
        embeddedPtId = findEmbeddedPt(t);
        glog("legenda PT embutida: " + (embeddedPtId == NO_TRACK ? "não há" : "faixa " + embeddedPtId));
        if (cands.isEmpty()) return;
        StringBuilder lg = new StringBuilder("ranking das legendas:");
        ArrayList<SubtitleSync.Cand> use = SubtitleSync.rank(cands, len, lg);
        glog(lg.toString());
        final boolean embeddedFirst = embeddedPtId != NO_TRACK;
        for (int i = 0; i < use.size() && i < 6; i++) {
            SubtitleSync.Cand c = use.get(i);
            try {
                player.addSlave(0 /* legenda */, c.uri, i == 0 && !embeddedFirst);
                addedSubs++;
                attached.add(c);
                subLabels.add("Português " + (subLabels.size() + 1) + (c.aligned ? " (sincronizada)" : c.hashMatch ? " (do hash)" : "") + (c.fixed ? " (corrigida)" : ""));
            } catch (Exception ignored) {}
        }
        if (embeddedFirst && !userPickedSub) player.setSpuTrack(embeddedPtId);
        if (!use.isEmpty()) {
            SubtitleSync.Cand f = use.get(0);
            glog("escolhida: " + (embeddedFirst ? "embutida (faixa " + embeddedPtId + ")" : "candidata #" + f.rank)
                    + "; melhor baixada: #" + f.rank + " alinhada=" + f.aligned + " nota=" + String.format(Locale.ROOT, "%.2f", f.alignScore)
                    + " deslocamento=" + f.alignOffsetMs + "ms hash=" + f.hashMatch + " corrigida=" + f.fixed + "; " + addedSubs + " no menu");
        }
        applySubDelay();
        ui.postDelayed(() -> {
            applySubDelay();
            if (embeddedFirst && !userPickedSub && player != null) player.setSpuTrack(embeddedPtId);
        }, 1500);
    }

    private void selectEmbeddedPortugueseLater() {
        // liga a PT/BR que vier dentro do próprio arquivo (vem perfeitamente sincronizada); se não houver nada em português, desliga
        ui.postDelayed(() -> {
            if (player == null) return;
            MediaPlayer.TrackDescription[] t = player.getSpuTracks();
            if (t == null) return;
            int id = findEmbeddedPt(t);
            if (id != NO_TRACK) { embeddedPtId = id; if (!userPickedSub) player.setSpuTrack(id); return; }
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

    private void chooseSubtitles() {
        MediaPlayer.TrackDescription[] all = player.getSpuTracks();
        ArrayList<MediaPlayer.TrackDescription> keep = new ArrayList<>();
        int n = 0;
        if (all != null) for (MediaPlayer.TrackDescription d : all) {
            if (d.id == -1 || isPortuguese(d.name) || isDownloaded(d.id)) keep.add(d);
        }
        int current = player.getSpuTrack();
        String chosen = current == -1 ? "desativada" : null;
        String[] names = new String[keep.size()];
        int[] ids = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            MediaPlayer.TrackDescription d = keep.get(i);
            ids[i] = d.id;
            if (d.id == -1) names[i] = "Desativado";
            else if (isDownloaded(d.id)) {
                n++;
                names[i] = n <= subLabels.size() ? subLabels.get(n - 1) : "Português " + n;
                if (d.id == current && n <= attached.size()) {
                    SubtitleSync.Cand c = attached.get(n - 1);
                    String adj = c.aligned
                            ? "ajuste " + signedSecs(c.alignOffsetMs) + (c.alignScale != 1.0 ? ", velocidade ×" + String.format(Locale.ROOT, "%.4f", c.alignScale).replace('.', ',') : "")
                            : c.fixed ? "velocidade corrigida" : "sem ajuste";
                    chosen = names[i] + " (" + adj + ")";
                }
            } else {
                names[i] = "Embutida: " + d.name;
                if (d.id == current) chosen = names[i];
            }
        }
        StringBuilder diag = new StringBuilder(diagId);
        diag.append(" · ").append(!subsDownloaded ? "baixando legendas…" : cands.size() == 1 ? "1 legenda PT" : cands.size() + " legendas PT");
        if (!diagRef.isEmpty()) diag.append(" · ").append(diagRef);
        if (chosen != null) diag.append(" · escolhida: ").append(chosen);
        if (subDelayMs != 0) diag.append(" · atraso ").append(signedSecs(subDelayMs));
        ui.removeCallbacks(hideControls);
        if (keep.size() <= 1) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Legendas").setMessage("Não há legendas em português para este vídeo.\n\n" + diag)
                    .setPositiveButton("OK", null).show();
            return;
        }
        int checked = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] == current) checked = i;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), dp(16), dp(24), dp(8));
        head.addView(text("Legendas", 20, true));
        TextView dg = text(diag.toString(), 12, false);
        dg.setTextColor(0xFFA0A0A0);
        dg.setPadding(0, dp(4), 0, 0);
        head.addView(dg);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setCustomTitle(head)
                .setSingleChoiceItems(names, checked, (d, which) -> { userPickedSub = true; player.setSpuTrack(ids[which]); d.dismiss(); scheduleHide(); })
                .setNeutralButton("Sincronizar legenda", (d, w) -> showSyncDialog())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- ajuste manual do atraso da legenda ----------
    // libVLC: setSpuDelay(microssegundos); positivo = a legenda aparece MAIS TARDE (funciona na Fire TV)
    private String delayKey() { return "d:" + (metaId != null ? metaId : String.valueOf(url.hashCode())); }

    private int savedSubDelay() {
        try { return getSharedPreferences("sub-delay", MODE_PRIVATE).getInt(delayKey(), 0); } catch (Exception e) { return 0; }
    }

    private void applySubDelay() {
        if (player == null) return;
        try { player.setSpuDelay(subDelayMs * 1000L); } catch (Exception ignored) {}
    }

    private String delayLabel() { return "Atraso da legenda: " + (subDelayMs == 0 ? "0,0 s" : signedSecs(subDelayMs)); }

    private void changeSubDelay(int deltaMs, boolean reset, TextView label) {
        subDelayMs = reset ? 0 : Math.max(-60000, Math.min(60000, subDelayMs + deltaMs));
        try { getSharedPreferences("sub-delay", MODE_PRIVATE).edit().putInt(delayKey(), subDelayMs).apply(); } catch (Exception ignored) {}
        applySubDelay();
        label.setText(delayLabel());
        glog("atraso da legenda: " + subDelayMs + " ms");
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

    private LinearLayout syncRow(TextView... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (TextView b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), dp(4), dp(6), dp(4));
            row.addView(b, lp);
        }
        return row;
    }

    private void showSyncDialog() {
        ui.removeCallbacks(hideControls);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView value = text(delayLabel(), 20, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, 0, 0, dp(10));
        TextView h1 = text("A legenda aparece ANTES da fala:", 14, false);
        TextView h2 = text("A legenda aparece DEPOIS da fala:", 14, false);
        h2.setPadding(0, dp(10), 0, 0);
        TextView later1 = syncButton("Atrasar +0,5 s"), later2 = syncButton("Atrasar +5 s");
        TextView early1 = syncButton("Adiantar −0,5 s"), early2 = syncButton("Adiantar −5 s");
        TextView reset = syncButton("Repor");
        later1.setOnClickListener(v -> changeSubDelay(500, false, value));
        later2.setOnClickListener(v -> changeSubDelay(5000, false, value));
        early1.setOnClickListener(v -> changeSubDelay(-500, false, value));
        early2.setOnClickListener(v -> changeSubDelay(-5000, false, value));
        reset.setOnClickListener(v -> changeSubDelay(0, true, value));
        LinearLayout last = syncRow(reset);
        last.setPadding(0, dp(14), 0, 0);
        box.addView(value);
        box.addView(h1);
        box.addView(syncRow(later1, later2));
        box.addView(h2);
        box.addView(syncRow(early1, early2));
        box.addView(last);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sincronizar legenda")
                .setView(scroll)
                .setPositiveButton("Fechar", null)
                .setOnDismissListener(d -> scheduleHide())
                .create();
        dlg.show();
        later1.requestFocus();
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
        if (!show && controlFocused() && focusSink != null) focusSink.requestFocus();
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideControls);
        if (show) scheduleHide();
        immersive();
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

    private TextView round(String s, int sp) {
        TextView t = text(s, sp, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x80141416);
        g.setStroke(dp(1), 0x33FFFFFF);
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

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) { resumeOnReturn = player.isPlaying(); savePosition(true); player.pause(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && resumeOnReturn) { resumeOnReturn = false; player.play(); }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            savePosition(true);
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (libVLC != null) { libVLC.release(); libVLC = null; }
        super.onDestroy();
    }
}
