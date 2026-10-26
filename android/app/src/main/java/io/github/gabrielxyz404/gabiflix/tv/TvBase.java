package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import io.github.gabrielxyz404.gabiflix.PlayerActivity;

/** Base das telas da TV: tela cheia preta e o caminho até o player (VLC) com o progresso salvo no Stremio. */
public abstract class TvBase extends Activity {

    private static final String PLAY_PREFS = "gf-tv-play";

    private static boolean crashHook;

    /** se o app fechar com erro, guarda o motivo para mostrar na próxima vez que abrir */
    static void installCrashHook(android.content.Context c) {
        if (crashHook) return;
        crashHook = true;
        final java.io.File f = new java.io.File(c.getFilesDir(), "gf-crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f))) {
                w.println(new java.util.Date() + " · " + t.getName());
                e.printStackTrace(w);
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** o erro guardado da última vez (e apaga) */
    static String takeCrash(android.content.Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), "gf-crash.txt");
        if (!f.exists()) return null;
        StringBuilder b = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line; int n = 0;
            while ((line = r.readLine()) != null && n++ < 18) b.append(line.trim()).append('\n');
        } catch (Exception ignored) {}
        f.delete();
        return b.toString();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashHook(getApplicationContext());
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** abre o vídeo no player VLC do app; ao voltar, o progresso vai para o Continuar assistindo */
    protected void play(JSONObject meta, String videoId, Stremio.Source src) {
        if (src == null || src.url == null) {
            Toast.makeText(this, "Esta fonte é torrent: só toca com Real-Debrid no addon.", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PLAY_PREFS, MODE_PRIVATE).edit()
                .putString("meta", meta.toString()).putString("videoId", videoId).putString("url", src.url).apply();
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", src.url);
        i.putExtra("type", meta.optString("type"));
        i.putExtra("id", videoId);
        i.putExtra("start", Stremio.resumeFor(this, meta.optString("id"), videoId));
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences last = getSharedPreferences("gf-last-session", MODE_PRIVATE);
        SharedPreferences mine = getSharedPreferences(PLAY_PREFS, MODE_PRIVATE);
        if (!last.contains("time") || !mine.contains("meta")) return;
        long time = last.getLong("time", 0), length = last.getLong("length", 0);
        boolean ended = last.getBoolean("ended", false);
        String url = last.getString("url", "");
        String metaJson = mine.getString("meta", null), videoId = mine.getString("videoId", null), myUrl = mine.getString("url", "");
        last.edit().clear().apply();
        mine.edit().clear().apply();
        if (metaJson == null || time <= 0 || (!url.isEmpty() && !url.equals(myUrl))) return;
        Net.async(() -> {
            Stremio.saveProgress(this, new JSONObject(metaJson), videoId, time, length, ended);
            return true;
        }, (ok, err) -> onProgressSaved());
    }

    // ---------- efeito leve ao trocar de tela ----------
    @Override
    public void startActivity(Intent i) {
        super.startActivity(i);
        overridePendingTransition(io.github.gabrielxyz404.gabiflix.R.anim.gf_in, io.github.gabrielxyz404.gabiflix.R.anim.gf_stay);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(io.github.gabrielxyz404.gabiflix.R.anim.gf_stay, io.github.gabrielxyz404.gabiflix.R.anim.gf_out);
    }

    /** realce leve ao selecionar (aumenta um pouco), igual em todas as telas */
    static void focusGrow(View v, float scale) {
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .setDuration(110).setInterpolator(new android.view.animation.DecelerateInterpolator()).start());
    }

    /** o progresso foi salvo: as telas atualizam o Continuar assistindo */
    protected void onProgressSaved() {}

    protected void focusLater(View v) {
        if (v != null) v.post(v::requestFocus);
    }
}
