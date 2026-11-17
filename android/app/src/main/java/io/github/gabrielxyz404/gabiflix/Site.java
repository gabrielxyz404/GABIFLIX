package io.github.gabrielxyz404.gabiflix;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Endereço do site do app. O site mora no GitHub Pages, que NÃO redireciona quando o repositório muda de nome:
 * por isso o app conhece os dois endereços (o novo, FramePlay, e o antigo) e usa o que responder.
 * Funciona antes e depois de renomear o repositório. Ambos têm a mesma origem (gabrielxyz404.github.io),
 * então o login e os dados guardados no WebView continuam os mesmos.
 */
public final class Site {
    private Site() {}

    public static final String HOST = "gabrielxyz404.github.io";
    public static final String NOVO = "https://" + HOST + "/FramePlay/";
    public static final String ANTIGO = "https://" + HOST + "/GABIFLIX/";
    /** nomes do repositório, na ordem em que a API de releases é tentada */
    public static final String[] REPOS = {"FramePlay", "GABIFLIX"};

    private static volatile String resolved;
    private static volatile long resolvedAt;

    /** o último endereço que respondeu (ou o antigo, se ainda não houve resposta); nunca bloqueia */
    public static String last() { return resolved != null ? resolved : ANTIGO; }

    /** "gabrielxyz404.github.io/FramePlay/tv": o texto para digitar no Downloader */
    public static String tvHint() { return last().replace("https://", "") + "tv"; }

    /** Descobre qual endereço responde (os dois são testados ao mesmo tempo; o novo tem preferência). Chamar fora da thread principal. */
    public static String resolve() {
        String r = resolved;
        if (r != null && System.currentTimeMillis() - resolvedAt < 10 * 60 * 1000L) return r;
        final boolean[] ok = new boolean[2];
        final String[] urls = {NOVO, ANTIGO};
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int k = i;
            ts[i] = new Thread(() -> ok[k] = alive(urls[k]));
            ts[i].start();
        }
        for (Thread t : ts) { try { t.join(5000); } catch (InterruptedException ignored) {} }
        String pick = ok[0] ? NOVO : ok[1] ? ANTIGO : null;
        if (pick == null) return r != null ? r : ANTIGO; // sem internet: usa o último que funcionou, senão o antigo
        resolved = pick;
        resolvedAt = System.currentTimeMillis();
        return pick;
    }

    private static boolean alive(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "manifest.json").openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "FramePlay");
            return c.getResponseCode() == 200;
        } catch (Exception e) { return false; }
        finally { if (c != null) c.disconnect(); }
    }
}
