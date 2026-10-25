package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Botão Atualizar da TV: busca a versão nova do app (e recarrega os catálogos). */
final class Updater {
    private Updater() {}

    private static final String RELEASE = "https://api.github.com/repos/gabrielxyz404/GABIFLIX/releases/tags/android";

    static void run(Activity a) {
        Toast.makeText(a, "Procurando atualização…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        Net.async(() -> {
            JSONObject rel = new JSONObject(Net.get(RELEASE));
            String text = rel.optString("name") + " " + rel.optString("body");
            Matcher m = Pattern.compile("1\\.0\\.(\\d+)").matcher(text);
            long latest = m.find() ? Long.parseLong(m.group(1)) : -1;
            String url = null;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject as = assets.getJSONObject(i);
                if (as.optString("name").endsWith(".apk")) url = as.optString("browser_download_url");
            }
            return new Object[]{latest, url};
        }, (res, err) -> {
            if (a.isFinishing()) return;
            refreshData(app);
            long mine = versionCode(a);
            long latest = res != null ? (long) res[0] : -1;
            String url = res != null ? (String) res[1] : null;
            if (res == null || url == null) { Toast.makeText(a, "Catálogos atualizados. (Sem acesso às versões agora.)", Toast.LENGTH_LONG).show(); return; }
            if (latest <= mine) { Toast.makeText(a, "Catálogos atualizados. O app já está na versão mais recente.", Toast.LENGTH_LONG).show(); return; }
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Nova versão do GabiFlix")
                    .setMessage("Versão 1.0." + latest + " disponível (a sua é 1.0." + mine + "). Instalar agora?")
                    .setPositiveButton("Instalar", (d, w) -> download(a, url))
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

    private static long versionCode(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    private static void download(Activity a, String url) {
        AlertDialog progress = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Baixando a atualização…").setMessage("0%").setCancelable(false).show();
        File out = new File(a.getCacheDir(), "update/GabiFlix.apk");
        Net.IO.execute(() -> {
            try {
                out.getParentFile().mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) { // o GitHub manda para outro endereço
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = (HttpURLConnection) new URL(loc).openConnection();
                }
                long total = c.getContentLengthLong(), got = 0;
                int last = -1;
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    int r;
                    while ((r = in.read(b)) > 0) {
                        fo.write(b, 0, r);
                        got += r;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != last) { last = pct; int p = pct; Net.MAIN.post(() -> progress.setMessage(p >= 0 ? p + "%" : "…")); }
                    }
                }
                c.disconnect();
                Net.MAIN.post(() -> { progress.dismiss(); install(a, out); });
            } catch (Exception e) {
                Net.MAIN.post(() -> { progress.dismiss(); Toast.makeText(a, "Não foi possível baixar a atualização.", Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private static void install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Permita que o GabiFlix instale apps e carregue em Atualizar de novo.", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                return;
            } catch (Exception ignored) {} // alguns Fire TV não têm esta tela: tenta instalar mesmo assim
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); }
        catch (Exception e) { Toast.makeText(a, "Abra o Downloader para instalar: gabrielxyz404.github.io/GABIFLIX/tv", Toast.LENGTH_LONG).show(); }
    }
}
