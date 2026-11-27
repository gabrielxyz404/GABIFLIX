package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import io.github.gabrielxyz404.gabiflix.AppUpdate;

/** Botão Atualizar da TV: busca a versão nova do app (e recarrega os catálogos). A busca, o download e a instalação são do AppUpdate (partilhado com o celular). */
final class Updater {
    private Updater() {}

    static void run(Activity a) {
        Toast.makeText(a, "Procurando atualização…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        Net.async(() -> AppUpdate.fetch(AppUpdate.TAG_TV), (res, err) -> {
            if (a.isFinishing()) return;
            refreshData(app);
            long mine = AppUpdate.versionCode(a);
            long latest = res != null ? res.latest : -1;
            String url = res != null ? res.url : null;
            if (res == null || url == null) { Toast.makeText(a, "Catálogos atualizados. (Sem acesso às versões agora.)", Toast.LENGTH_LONG).show(); return; }
            if (latest <= mine) { Toast.makeText(a, "Catálogos atualizados. O app já está na versão mais recente.", Toast.LENGTH_LONG).show(); return; }
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Nova versão do FramePlay")
                    .setMessage(res.offerText() + " Instalar agora?")
                    .setPositiveButton("Instalar", (d, w) -> AppUpdate.download(a, url, AppUpdate.Texts.tv(), false))
                    .setNegativeButton("Depois", null)
                    .show();
        });
    }

    /** esquece o que estava guardado: catálogos, addons e biblioteca vêm de novo */
    static void refreshData(Context c) {
        Net.clearCache();
        Stremio.forget();
        Glide.get(c).clearMemory();
    }
}
