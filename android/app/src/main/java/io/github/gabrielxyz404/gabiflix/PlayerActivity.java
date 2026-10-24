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
    private final ArrayList<Uri> readySubs = new ArrayList<>();
    private int addedSubs = 0;
    private static final java.util.regex.Pattern PT = java.util.regex.Pattern.compile("portug|brazil|brasil|\\bpt\\b|\\bpor\\b|\\bpob\\b|pt-br|pt_br", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int HIDE_DELAY_MS = 3500;

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
        loadSubtitlesInBackground();
        if (url == null) { finish(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
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
        player.attachViews(videoLayout, null, true, false);
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
                if (!playing) { playing = true; attachReadySubs(); selectEmbeddedPortugueseLater(); }
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
                .putLong("time", time).putLong("length", length).putBoolean("ended", finished)
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
        TextView scale = pill("Tela: " + SCALE_NAMES[0]);
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

    // legendas: só as em português (as do Stremio e as do próprio arquivo que forem PT/BR)
    // ---------- legendas em português ----------
    // Baixa as legendas (dos addons, ou direto do OpenSubtitles do Stremio) para arquivos .srt
    // no celular e liga no VLC. Arquivo local com extensão é o jeito mais confiável para o VLC.
    private void loadSubtitlesInBackground() {
        new Thread(() -> {
            ArrayList<String> urls = new ArrayList<>(java.util.Arrays.asList(subs));
            if (urls.isEmpty() && metaId != null && metaType != null) urls.addAll(searchOpenSubtitles(metaType, metaId));
            java.io.File dir = new java.io.File(getCacheDir(), "legendas");
            if (!dir.exists()) dir.mkdirs();
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            int n = 0;
            for (String u : urls) {
                if (n >= 5) break;
                try {
                    byte[] data = httpGet(u);
                    if (data == null || data.length < 20) continue;
                    java.io.File f = new java.io.File(dir, (++n) + (looksLikeVtt(data) ? ".vtt" : ".srt"));
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    out.write(data);
                    out.close();
                    Uri uri = Uri.fromFile(f);
                    ui.post(() -> { readySubs.add(uri); attachReadySubs(); });
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void attachReadySubs() {
        if (player == null || !playing) return;
        while (addedSubs < readySubs.size()) {
            Uri u = readySubs.get(addedSubs);
            try { player.addSlave(0 /* legenda */, u, addedSubs == 0); } catch (Exception ignored) {}
            addedSubs++;
        }
    }

    private void selectEmbeddedPortugueseLater() {
        // sem legenda baixada: liga a PT/BR que vier dentro do próprio arquivo, se houver
        ui.postDelayed(() -> {
            if (player == null || addedSubs > 0) return;
            MediaPlayer.TrackDescription[] t = player.getSpuTracks();
            if (t == null) return;
            for (MediaPlayer.TrackDescription d : t) if (d.id != -1 && isPortuguese(d.name)) { player.setSpuTrack(d.id); break; }
        }, 2500);
    }

    private static boolean looksLikeVtt(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 16), java.nio.charset.StandardCharsets.UTF_8);
        return head.replace("\uFEFF", "").startsWith("WEBVTT");
    }

    private static byte[] httpGet(String u) throws java.io.IOException {
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

    /** legendas PT/BR do OpenSubtitles do Stremio (Brasil primeiro) */
    private static ArrayList<String> searchOpenSubtitles(String type, String id) {
        ArrayList<String> br = new ArrayList<>(), pt = new ArrayList<>();
        try {
            byte[] data = httpGet("https://opensubtitles-v3.strem.io/subtitles/" + Uri.encode(type) + "/" + Uri.encode(id) + ".json");
            if (data == null) return br;
            org.json.JSONArray arr = new org.json.JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("subtitles");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String lang = o.optString("lang", "").toLowerCase(Locale.ROOT), url = o.optString("url", "");
                if (!url.startsWith("http")) continue;
                if (lang.equals("pob") || lang.equals("pt-br") || lang.equals("ptb")) br.add(url);
                else if (lang.equals("por") || lang.equals("pt") || lang.equals("pt-pt")) pt.add(url);
            }
        } catch (Exception ignored) {}
        br.addAll(pt);
        return br;
    }

    private static boolean isPortuguese(String name) { return name != null && PT.matcher(name).find(); }
    /** legendas do próprio arquivo vêm com o idioma entre colchetes ("... - [Russian]"); as que adicionamos, não */
    private static boolean isExternal(String name) { return name == null || !name.contains("["); }

    private void chooseSubtitles() {
        MediaPlayer.TrackDescription[] all = player.getSpuTracks();
        ArrayList<MediaPlayer.TrackDescription> keep = new ArrayList<>();
        int n = 0;
        if (all != null) for (MediaPlayer.TrackDescription d : all) {
            if (d.id == -1 || isPortuguese(d.name) || (addedSubs > 0 && isExternal(d.name))) keep.add(d);
        }
        String[] names = new String[keep.size()];
        int[] ids = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            MediaPlayer.TrackDescription d = keep.get(i);
            ids[i] = d.id;
            if (d.id == -1) names[i] = "Desativado";
            else if (isExternal(d.name) && !isPortuguese(d.name)) names[i] = "Português " + (++n);
            else names[i] = d.name;
        }
        if (keep.size() <= 1) {
            ui.removeCallbacks(hideControls);
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Legendas").setMessage("Não há legendas em português para este vídeo.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        int current = player.getSpuTrack(), checked = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] == current) checked = i;
        ui.removeCallbacks(hideControls);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Legendas")
                .setSingleChoiceItems(names, checked, (d, which) -> { player.setSpuTrack(ids[which]); d.dismiss(); scheduleHide(); })
                .setNegativeButton("Cancelar", null)
                .show();
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
