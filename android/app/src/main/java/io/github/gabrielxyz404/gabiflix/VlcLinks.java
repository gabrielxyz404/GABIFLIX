package io.github.gabrielxyz404.gabiflix;

import android.net.Uri;

/**
 * Reconhece os links que o GabiFlix gera para abrir um vídeo no VLC e extrai o endereço do vídeo.
 * Formatos: intent://HOST/CAMINHO#Intent;package=org.videolan.vlc;...;scheme=https;end
 *           vlc://https://...      vlc-x-callback://x-callback-url/stream?url=...
 */
final class VlcLinks {
    private VlcLinks() {}

    static String mediaUrl(String link) {
        if (link == null) return null;
        String lower = link.toLowerCase();

        if (lower.startsWith("vlc-x-callback://")) {
            String u = Uri.parse(link).getQueryParameter("url");
            return isMedia(u) ? u : null;
        }
        if (lower.startsWith("vlc://")) {
            String u = link.substring("vlc://".length());
            if (!u.startsWith("http")) u = "http://" + u;
            return isMedia(u) ? u : null;
        }
        if (lower.startsWith("intent:")) {
            int hash = link.indexOf("#Intent;");
            if (hash < 0) return null;
            String tail = link.substring(hash + "#Intent;".length());
            // só intents de vídeo (VLC, ou "abrir com" de vídeo)
            boolean video = tail.contains("package=org.videolan.vlc") || tail.contains("type=video");
            if (!video) return null;
            String scheme = "https";
            for (String part : tail.split(";")) {
                if (part.startsWith("scheme=")) scheme = part.substring("scheme=".length());
            }
            String body = link.substring(0, hash);
            if (body.startsWith("intent://")) body = body.substring("intent://".length());
            else if (body.startsWith("intent:")) body = body.substring("intent:".length());
            if (body.startsWith("//")) body = body.substring(2);
            String u = scheme + "://" + body;
            return isMedia(u) ? u : null;
        }
        return null;
    }

    private static boolean isMedia(String u) {
        return u != null && (u.startsWith("http://") || u.startsWith("https://"));
    }
}
