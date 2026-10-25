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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

    /** o progresso foi salvo: as telas atualizam o Continuar assistindo */
    protected void onProgressSaved() {}

    protected void focusLater(View v) {
        if (v != null) v.post(v::requestFocus);
    }
}
