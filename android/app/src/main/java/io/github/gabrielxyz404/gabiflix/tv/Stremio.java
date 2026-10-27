package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Conta do Stremio e dados do GabiFlix:
 * login, addons da conta, biblioteca (Mais Tarde / Continuar assistindo), títulos, fontes e os catálogos do GabiFlix.
 */
final class Stremio {
    private Stremio() {}

    static final String API = "https://api.strem.io/api/";
    static final String SITE = "https://gabrielxyz404.github.io/GABIFLIX/";
    static final String CINEMETA = "https://v3-cinemeta.strem.io";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gf-tv", Context.MODE_PRIVATE); }
    static String authKey(Context c) { return prefs(c).getString("authKey", null); }
    static String email(Context c) { return prefs(c).getString("email", null); }
    static boolean loggedIn(Context c) { return authKey(c) != null; }

    /** esquece addons e biblioteca guardados (vêm de novo no próximo pedido) */
    static synchronized void forget() { addons = null; library = null; }

    static void logout(Context c) {
        prefs(c).edit().clear().apply();
        addons = null;
        library = null;
    }

    // ---------- conta ----------
    static void login(Context c, String email, String password) throws Exception {
        JSONObject req = new JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false);
        JSONObject res = new JSONObject(Net.post(API + "login", req.toString()));
        if (res.has("error")) throw new Exception(errorText(res.opt("error")));
        JSONObject r = res.getJSONObject("result");
        prefs(c).edit().putString("authKey", r.getString("authKey")).putString("email", email).apply();
        addons = null;
        library = null;
    }

    private static String errorText(Object e) {
        String s = e instanceof JSONObject ? ((JSONObject) e).optString("message", e.toString()) : String.valueOf(e);
        if (s.toLowerCase(Locale.ROOT).contains("password") || s.toLowerCase(Locale.ROOT).contains("user")) return "E-mail ou senha incorretos.";
        return s;
    }

    private static JSONObject api(Context c, String method, JSONObject body) throws Exception {
        body.put("authKey", authKey(c));
        JSONObject res = new JSONObject(Net.post(API + method, body.toString()));
        if (res.has("error")) throw new Exception(errorText(res.opt("error")));
        return res;
    }

    // ---------- addons da conta ----------
    private static List<JSONObject> addons;

    static synchronized List<JSONObject> addons(Context c) throws Exception {
        if (addons != null) return addons;
        List<JSONObject> out = new ArrayList<>();
        if (loggedIn(c)) {
            JSONArray arr = api(c, "addonCollectionGet", new JSONObject().put("type", "AddonCollectionGet").put("update", true))
                    .getJSONObject("result").optJSONArray("addons");
            if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        }
        addons = out;
        return out;
    }

    /** o addon dá fontes para este tipo/código? */
    static boolean servesStreams(JSONObject addon, String type, String id) {
        JSONObject m = addon.optJSONObject("manifest");
        if (m == null) return false;
        JSONArray res = m.optJSONArray("resources");
        if (res == null) return false;
        for (int i = 0; i < res.length(); i++) {
            Object r = res.opt(i);
            JSONArray types = m.optJSONArray("types"), prefixes = m.optJSONArray("idPrefixes");
            if (r instanceof String) { if (!"stream".equals(r)) continue; }
            else if (r instanceof JSONObject) {
                JSONObject o = (JSONObject) r;
                if (!"stream".equals(o.optString("name"))) continue;
                if (o.has("types")) types = o.optJSONArray("types");
                if (o.has("idPrefixes")) prefixes = o.optJSONArray("idPrefixes");
            } else continue;
            if (types != null && !contains(types, type)) continue;
            if (prefixes != null && prefixes.length() > 0) {
                boolean ok = false;
                for (int k = 0; k < prefixes.length(); k++) if (id.startsWith(prefixes.optString(k))) { ok = true; break; }
                if (!ok) continue;
            }
            return true;
        }
        return false;
    }

    private static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static String base(JSONObject addon) {
        return addon.optString("transportUrl").replaceAll("/manifest\\.json.*$", "");
    }

    /** uma fonte de vídeo */
    static final class Source {
        String addon, name, title, url;
        boolean torrent;
        String langs; // bandeiras dos áudios (calculado uma vez em SourcePicker.parse)
    }

    interface SourcesCb { void update(List<Source> all, boolean finished); }

    /** pergunta a todos os addons ao mesmo tempo; avisa a cada resposta (links diretos primeiro) */
    static void streams(Context c, String type, String videoId, SourcesCb cb) {
        Net.async(() -> addons(c), (list, err) -> {
            if (list == null) { cb.update(new ArrayList<>(), true); return; }
            List<JSONObject> serving = new ArrayList<>();
            for (JSONObject a : list) if (servesStreams(a, type, videoId)) serving.add(a);
            if (serving.isEmpty()) { cb.update(new ArrayList<>(), true); return; }
            final List<Source> all = new ArrayList<>();
            final int[] left = {serving.size()};
            for (JSONObject a : serving) {
                String name = a.optJSONObject("manifest") != null ? a.optJSONObject("manifest").optString("name", "Addon") : "Addon";
                String url = base(a) + "/stream/" + Uri.encode(type) + "/" + Uri.encode(videoId) + ".json";
                Net.async(() -> parseStreams(Net.get(url), name), (got, e) -> {
                    if (got != null) all.addAll(got);
                    left[0]--;
                    List<Source> sorted = new ArrayList<>(all);
                    sorted.sort((x, y) -> Boolean.compare(x.torrent, y.torrent));
                    cb.update(sorted, left[0] == 0);
                });
            }
        });
    }

    private static List<Source> parseStreams(String json, String addon) throws JSONException {
        List<Source> out = new ArrayList<>();
        JSONArray arr = new JSONObject(json).optJSONArray("streams");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            Source src = new Source();
            src.addon = addon;
            src.name = s.optString("name", addon).replace('\n', ' ').trim();
            src.title = s.optString("title", s.optString("description", "")).trim();
            src.url = s.optString("url", null);
            if (src.url != null && !src.url.startsWith("http")) src.url = null;
            src.torrent = src.url == null;
            if (src.url == null && !s.has("infoHash")) continue; // YouTube e outros: não servem aqui
            out.add(src);
        }
        return out;
    }

    // ---------- títulos ----------
    static JSONObject meta(String type, String id) throws Exception {
        return new JSONObject(Net.getCached(CINEMETA + "/meta/" + Uri.encode(type) + "/" + Uri.encode(id) + ".json")).getJSONObject("meta");
    }

    /** sinopse em português (gerada todo dia pelo GabiFlix) */
    static String ptDescription(String id) {
        try {
            String shard = id.length() >= 2 ? id.substring(id.length() - 2) : "00";
            if (!shard.matches("\\d\\d")) return null;
            JSONObject d = new JSONObject(Net.getCached(SITE + "data/desc/" + shard + ".json?v=" + (System.currentTimeMillis() / 86400000L)));
            String s = d.optString(id, null);
            return s == null || s.isEmpty() ? null : s;
        } catch (Exception e) { return null; }
    }

    static List<JSONObject> search(String q) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String type : new String[]{"movie", "series"}) {
            try {
                JSONArray arr = new JSONObject(Net.get(CINEMETA + "/catalog/" + type + "/top/search=" + Uri.encode(q) + ".json")).optJSONArray("metas");
                if (arr != null) for (int i = 0; i < arr.length() && i < 40; i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m != null && seen.add(m.optString("id"))) { if (!m.has("type")) m.put("type", type); out.add(m); }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ---------- catálogos do GabiFlix (os mesmos do celular) ----------
    private static String hour() { return String.valueOf(System.currentTimeMillis() / 3600000L); }

    static JSONObject data(String file) throws Exception {
        return new JSONObject(Net.getCached(SITE + "data/" + file + ".json?v=" + hour()));
    }

    static List<JSONObject> metas(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject m = arr.optJSONObject(i); if (m != null) out.add(m); }
        return out;
    }

    static Set<String> ids(String service) {
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(Net.getCached(SITE + "data/" + service + "-ids.json?v=" + hour()));
            for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        } catch (Exception ignored) {}
        return s;
    }

    // ---------- biblioteca (Mais Tarde e Continuar assistindo) ----------
    private static Map<String, JSONObject> library;

    private static boolean fakeLib; // só nos testes automáticos

    /** biblioteca de mentira para os testes (não usa internet) */
    static synchronized void fakeLibrary(Context c, Map<String, JSONObject> items) {
        prefs(c).edit().putString("authKey", "teste").putString("email", "teste@gabiflix").apply();
        library = items;
        fakeLib = true;
    }

    static synchronized Map<String, JSONObject> library(Context c, boolean refresh) throws Exception {
        if (fakeLib) return library;
        if (library != null && !refresh) return library;
        Map<String, JSONObject> map = new HashMap<>();
        if (loggedIn(c)) {
            Object r = api(c, "datastoreGet", new JSONObject().put("collection", "libraryItem").put("ids", new JSONArray()).put("all", true)).opt("result");
            JSONArray arr = r instanceof JSONArray ? (JSONArray) r : null;
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it != null) map.put(it.optString("_id"), it);
            }
        }
        library = map;
        return map;
    }

    /** Continuar assistindo: itens com tempo parado, do mais recente para o mais antigo */
    static List<JSONObject> continueWatching(Context c) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject it : library(c, true).values()) {
            JSONObject st = it.optJSONObject("state");
            if (st == null || "other".equals(it.optString("type"))) continue;
            boolean visible = !it.optBoolean("removed") || it.optBoolean("temp");
            if (visible && st.optLong("timeOffset") > 0) out.add(it);
        }
        out.sort((a, b) -> b.optJSONObject("state").optString("lastWatched").compareTo(a.optJSONObject("state").optString("lastWatched")));
        return out;
    }

    /** item da biblioteca já guardado (sem ir à internet; null se ainda não carregou) */
    static JSONObject cachedItem(String id) {
        Map<String, JSONObject> l = library;
        return l != null ? l.get(id) : null;
    }

    static boolean inLibrary(Context c, String id) {
        JSONObject it = library != null ? library.get(id) : null;
        return it != null && !it.optBoolean("removed") && !it.optBoolean("temp");
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static JSONObject itemFor(Context c, JSONObject meta) throws Exception {
        Map<String, JSONObject> lib = library(c, false);
        String id = meta.optString("id");
        JSONObject it = lib.get(id);
        if (it == null) {
            it = new JSONObject();
            it.put("_id", id).put("removed", true).put("temp", true).put("_ctime", now());
            JSONObject st = new JSONObject();
            st.put("lastWatched", now()).put("timeWatched", 0).put("timeOffset", 0).put("overallTimeWatched", 0)
                    .put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0).put("video_id", JSONObject.NULL)
                    .put("watched", JSONObject.NULL).put("noNotif", false);
            it.put("state", st);
            it.put("behaviorHints", new JSONObject().put("defaultVideoId", "movie".equals(meta.optString("type")) ? id : JSONObject.NULL)
                    .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false));
            lib.put(id, it);
        }
        it.put("name", meta.optString("name"));
        it.put("type", meta.optString("type"));
        it.put("poster", meta.optString("poster", ""));
        it.put("posterShape", "poster");
        return it;
    }

    private static void put(Context c, JSONObject it) throws Exception {
        it.put("_mtime", now());
        api(c, "datastorePut", new JSONObject().put("collection", "libraryItem").put("changes", new JSONArray().put(it)));
    }

    /** guarda onde o vídeo parou (vindo do VLC) */
    static void saveProgress(Context c, JSONObject meta, String videoId, long time, long length, boolean ended) throws Exception {
        if (!loggedIn(c)) return;
        JSONObject it = itemFor(c, meta);
        JSONObject st = it.getJSONObject("state");
        long before = st.optLong("timeOffset");
        st.put("lastWatched", now());
        st.put("video_id", videoId);
        st.put("duration", length);
        long watched = Math.max(0, Math.min(time - before, length));
        st.put("timeWatched", st.optLong("timeWatched") + watched);
        st.put("overallTimeWatched", st.optLong("overallTimeWatched") + watched);
        if (ended || (length > 0 && time > length * 0.92)) {
            st.put("timeOffset", 0);
            st.put("timesWatched", st.optInt("timesWatched") + 1);
            if ("movie".equals(meta.optString("type"))) st.put("flaggedWatched", 1);
        } else {
            st.put("timeOffset", time);
        }
        if (it.optBoolean("removed")) it.put("temp", true);
        put(c, it);
    }

    static void setInLibrary(Context c, JSONObject meta, boolean add) throws Exception {
        JSONObject it = itemFor(c, meta);
        it.put("removed", !add);
        it.put("temp", false);
        put(c, it);
    }

    /** "Remover" do Continuar assistindo: a linha do tempo volta ao início */
    static void rewind(Context c, String id) throws Exception {
        JSONObject it = library(c, false).get(id);
        if (it == null) return;
        it.getJSONObject("state").put("timeOffset", 0);
        put(c, it);
    }

    // ---------- fonte escolhida: o Continuar assistindo abre direto no player ----------

    /** guarda a fonte usada neste vídeo (filme ou episódio), com limite para não crescer sem fim */
    static void rememberSource(Context c, String videoId, Source s) {
        if (videoId == null || videoId.isEmpty() || s == null || s.url == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences("gf-tv-src", Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("all", "{}"));
            all.put(videoId, new JSONObject().put("url", s.url).put("addon", s.addon == null ? "" : s.addon)
                    .put("name", s.name == null ? "" : s.name).put("title", s.title == null ? "" : s.title)
                    .put("t", System.currentTimeMillis()));
            while (all.length() > 80) {
                String oldest = null;
                long ot = Long.MAX_VALUE;
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    long t = all.optJSONObject(k) != null ? all.optJSONObject(k).optLong("t") : 0;
                    if (t < ot) { ot = t; oldest = k; }
                }
                if (oldest == null) break;
                all.remove(oldest);
            }
            p.edit().putString("all", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** a fonte guardada deste vídeo, ou null */
    static Source savedSource(Context c, String videoId) {
        try {
            JSONObject all = new JSONObject(c.getSharedPreferences("gf-tv-src", Context.MODE_PRIVATE).getString("all", "{}"));
            JSONObject e = all.optJSONObject(videoId);
            if (e == null || e.optString("url").isEmpty()) return null;
            Source s = new Source();
            s.url = e.optString("url");
            s.addon = e.optString("addon");
            s.name = e.optString("name");
            s.title = e.optString("title");
            return s;
        } catch (Exception ex) { return null; }
    }

    static long resumeFor(Context c, String id, String videoId) {
        JSONObject it = library != null ? library.get(id) : null;
        JSONObject st = it != null ? it.optJSONObject("state") : null;
        if (st == null) return 0;
        String v = st.optString("video_id", "");
        if (videoId != null && !v.isEmpty() && !"null".equals(v) && !v.equals(videoId)) return 0;
        long off = st.optLong("timeOffset"), len = st.optLong("duration");
        if (len > 0 && off > len * 0.95) return 0;
        return off;
    }

    static String resumeVideo(Context c, String id) {
        JSONObject it = library != null ? library.get(id) : null;
        JSONObject st = it != null ? it.optJSONObject("state") : null;
        String v = st != null ? st.optString("video_id", null) : null;
        return v == null || v.isEmpty() || "null".equals(v) ? null : v;
    }

}
