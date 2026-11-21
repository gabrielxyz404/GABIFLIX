package io.github.gabrielxyz404.gabiflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atualização do app dentro do próprio app: partes comuns à TV (tv/Updater) e ao celular (MainActivity).
 * Lê a release do GitHub (etiqueta "tv" ou "android"), compara 1.0.N com o versionCode, baixa o FramePlay.apk e abre o instalador do sistema.
 * Só o celular usa a verificação automática e o diálogo de {@link #checkAuto}/{@link #checkManual}; o fluxo da TV continua no Updater.
 */
public final class AppUpdate {
    private AppUpdate() {}

    public static final String TAG_TV = "tv";
    public static final String TAG_PHONE = "android";

    private static final long AUTO_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final String PREFS = "gf_update";
    private static final String KEY_LAST = "last_check";

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "gf-update"); t.setDaemon(true); return t; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** o que a release publicada diz: número da versão (o N de 1.0.N, ou -1) e o endereço do FramePlay.apk (ou null) */
    public static final class Release {
        public final long latest;
        public final String url;
        Release(long latest, String url) { this.latest = latest; this.url = url; }
    }

    /** textos de cada tela (a TV mantém os dela; o celular usa português de Portugal) */
    public static final class Texts {
        final String progressTitle, downloadFail, needPermission, installFail;
        Texts(String progressTitle, String downloadFail, String needPermission, String installFail) {
            this.progressTitle = progressTitle; this.downloadFail = downloadFail; this.needPermission = needPermission; this.installFail = installFail;
        }
        /** exatamente os textos que a TV sempre usou */
        public static Texts tv() {
            return new Texts("Baixando a atualização…", "Não foi possível baixar a atualização.",
                    "Permita que o FramePlay instale apps e carregue em Atualizar de novo.",
                    "Abra o Downloader para instalar: " + Site.tvHint());
        }
        static Texts phone() {
            return new Texts("A transferir a atualização…", "Não foi possível transferir a atualização.",
                    "Permita que o FramePlay instale aplicações e volte para continuar a atualização.",
                    "Não foi possível abrir o instalador. Tente outra vez mais tarde.");
        }
    }

    // ------------------------------------------------------------------ release

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FramePlay");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) buf.write(b, 0, r);
            in.close();
            String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + s.substring(0, Math.min(200, s.length())));
            return s;
        } finally {
            c.disconnect();
        }
    }

    // só para os testes automáticos: lê a release de uma pasta servida pelo runner (a API do GitHub limita os pedidos do mesmo IP)
    private static volatile String testBase;

    public static void testRelease(String base) {
        if (base != null && base.startsWith("http://10.0.2.2")) testBase = base.endsWith("/") ? base : base + "/";
    }

    /** lê a release pelo nome novo do repositório e, se não responder, pelo antigo. Chamar fora da thread principal. */
    public static String releaseJson(String tag) throws Exception {
        if (testBase != null) return get(testBase + tag + ".json");
        Exception last = null;
        for (String r : Site.REPOS) {
            try { return get("https://api.github.com/repos/gabrielxyz404/" + r + "/releases/tags/" + tag); }
            catch (Exception e) { last = e; }
        }
        throw last;
    }

    /** busca e lê a release da etiqueta ("tv" ou "android"). Chamar fora da thread principal. */
    public static Release fetch(String tag) throws Exception {
        JSONObject rel = new JSONObject(releaseJson(tag));
        String text = rel.optString("name") + " " + rel.optString("body");
        Matcher m = Pattern.compile("1\\.0\\.(\\d+)").matcher(text);
        long latest = m.find() ? Long.parseLong(m.group(1)) : -1;
        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject as = assets.getJSONObject(i);
            String n = as.optString("name");
            // escolha fixa: FramePlay.apk; só se não existir, qualquer outro .apk (o GabiFlix.apk é uma cópia igual, para as versões antigas)
            if (n.equals("FramePlay.apk")) { url = as.optString("browser_download_url"); break; }
            if (n.endsWith(".apk") && url == null) url = as.optString("browser_download_url");
        }
        return new Release(latest, url);
    }

    public static long versionCode(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ transferir e instalar

    /** baixa o APK com o progresso numa janela e, quando acaba, abre o instalador. */
    public static void download(Activity a, String url, Texts t, boolean continueAfterPermission) {
        AlertDialog progress = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t.progressTitle).setMessage("0%").setCancelable(false).show();
        File out = new File(a.getCacheDir(), "update/FramePlay.apk");
        IO.execute(() -> {
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
                        if (pct != last) { last = pct; int p = pct; MAIN.post(() -> progress.setMessage(p >= 0 ? p + "%" : "…")); }
                    }
                }
                c.disconnect();
                if (total > 0 && got != total) throw new IOException("download incompleto: " + got + " de " + total);
                MAIN.post(() -> { dismiss(progress); install(a, out, t, continueAfterPermission); });
            } catch (Exception e) {
                out.delete();
                MAIN.post(() -> { dismiss(progress); Toast.makeText(a, t.downloadFail, Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private static void dismiss(AlertDialog d) {
        try { d.dismiss(); } catch (Exception ignored) {} // a tela pode já ter sido fechada
    }

    // APK já baixado à espera de o utilizador dar a permissão "instalar apps desconhecidas" (só o celular)
    private static File waitingApk;
    private static Texts waitingTexts;

    /** abre o instalador do sistema. Sem a permissão (Android 8+), abre a definição do app; com continueAfterPermission, {@link #resumeWaiting} continua depois. */
    public static void install(Activity a, File apk, Texts t, boolean continueAfterPermission) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, t.needPermission, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                if (continueAfterPermission) { waitingApk = apk; waitingTexts = t; }
                return;
            } catch (Exception ignored) {} // alguns Fire TV não têm esta tela: tenta instalar mesmo assim
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); }
        catch (Exception e) { Toast.makeText(a, t.installFail, Toast.LENGTH_LONG).show(); }
    }

    /** ao voltar da definição de permissão: se foi dada, continua a instalação; se não, desiste. */
    public static void resumeWaiting(Activity a) {
        File apk = waitingApk;
        if (apk == null) return;
        Texts t = waitingTexts;
        waitingApk = null; waitingTexts = null;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Sem essa permissão não é possível instalar a atualização.", Toast.LENGTH_LONG).show();
            return;
        }
        if (apk.exists()) install(a, apk, t, false);
    }

    // ------------------------------------------------------------------ celular: verificação automática e manual

    // versão nova encontrada enquanto o ecrã não estava à frente (o diálogo nunca aparece por cima do player)
    // fica guardada até o utilizador responder (Instalar/Depois): se a tela for recriada ou fechada antes, a próxima a mostra de novo
    private static Release offer;
    private static long offerMine;
    private static AlertDialog offerDialog;

    /** ao abrir o app: no máximo de 6 em 6 horas, procura em segundo plano e, se houver versão nova, mostra o diálogo. */
    public static void checkAuto(Activity a, BooleanSupplier inFront) {
        SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), last = sp.getLong(KEY_LAST, 0);
        boolean cedo = last <= now && now - last < AUTO_EVERY_MS; // (relógio atrasado: last > now conta como vencido)
        android.util.Log.i("GFUpdate", "checkAuto: " + (cedo ? "menos de 6 h desde a última, não procura" : "procura"));
        if (cedo) return;
        check(a, inFront, false);
    }

    /** botão "Atualizar" do site (ponte JS): procura já e responde sempre, com diálogo ou aviso. */
    public static void checkManual(Activity a, BooleanSupplier inFront) {
        Toast.makeText(a, "A procurar atualização…", Toast.LENGTH_SHORT).show();
        check(a, inFront, true);
    }

    private static void check(Activity a, BooleanSupplier inFront, boolean manual) {
        Context app = a.getApplicationContext();
        IO.execute(() -> {
            Release rel = null;
            try { rel = fetch(TAG_PHONE); } catch (Exception e) { android.util.Log.w("GFUpdate", "falhou a busca: " + e); }
            final Release r = rel;
            if (r != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
            MAIN.post(() -> {
                long mine = versionCode(a);
                boolean gone = a.isFinishing() || a.isDestroyed();
                android.util.Log.i("GFUpdate", "release=" + (r == null ? "null" : r.latest + " " + r.url) + " minha=" + mine + " manual=" + manual + " frente=" + inFront.getAsBoolean());
                if (r == null || r.url == null) {
                    if (manual && !gone) Toast.makeText(a, "Sem acesso às versões agora. Tente mais tarde.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (r.latest <= mine) {
                    if (manual && !gone) Toast.makeText(a, "O app já está na versão mais recente.", Toast.LENGTH_LONG).show();
                    return;
                }
                offer = r; offerMine = mine;
                if (!gone && inFront.getAsBoolean()) showPendingOffer(a);
            });
        });
    }

    /** a tela vai ser destruída: fecha o diálogo dela (a versão nova continua guardada e a próxima tela a mostra) */
    public static void screenGone() {
        AlertDialog d = offerDialog;
        offerDialog = null;
        if (d != null) { try { d.dismiss(); } catch (Exception ignored) {} }
    }

    /** MainActivity com a janela à frente e com foco: mostra a versão nova ainda por responder (achada com o ecrã atrás, por exemplo durante um filme). */
    public static void showPendingOffer(Activity a) {
        Release r = offer;
        if (r == null || a.isFinishing() || a.isDestroyed()) return;
        if (offerDialog != null && offerDialog.isShowing()) return;
        android.util.Log.i("GFUpdate", "showOffer " + r.latest);
        offerDialog = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nova versão do FramePlay")
                .setMessage("Versão 1.0." + r.latest + " disponível (a sua é 1.0." + offerMine + "). Instalar agora?")
                .setPositiveButton("Instalar", (d, w) -> {
                    offer = null;
                    Toast.makeText(a, "A transferir…", Toast.LENGTH_SHORT).show();
                    download(a, r.url, Texts.phone(), true);
                })
                .setNegativeButton("Depois", (d, w) -> offer = null)
                .setOnCancelListener(d -> offer = null)
                .setOnDismissListener(d -> { android.util.Log.i("GFUpdate", "diálogo fechado (resposta=" + (offer == null) + ")"); offerDialog = null; })
                .show();
    }
}
