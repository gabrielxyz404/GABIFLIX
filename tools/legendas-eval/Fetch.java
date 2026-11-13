package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Baixa o corpus de legendas reais do addon OpenSubtitles do Stremio para uma pasta (bytes crus, sem decodificar):
 * uma pasta por título com list.json, meta.txt (kind, lang, m, item) e kind.raw. kind = pt-N | en-N | oth-LANG.
 * Uso: Fetch titles.txt outdir [ptMax] [enMax] [titulosComOutrosIdiomas]
 */
public class Fetch {
    static HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(java.time.Duration.ofSeconds(15)).build();

    static byte[] get(String u) {
        for (int a = 0; a < 3; a++) {
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(40)).header("User-Agent", "GabiFlixEval").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return r.body();
                System.err.println("HTTP " + r.statusCode() + " " + u);
                if (r.statusCode() == 404) return null;
            } catch (Exception e) { System.err.println("erro " + e + " " + u); }
            try { Thread.sleep(1500); } catch (InterruptedException ie) { return null; }
        }
        return null;
    }

    static boolean isPt(String l) { return l.equals("pob") || l.equals("por") || l.equals("pt") || l.equals("pt-br") || l.equals("ptb") || l.equals("ptbr") || l.equals("pt-pt"); }
    static boolean isEn(String l) { return l.equals("eng") || l.equals("en"); }
    static final Set<String> OTHER = new LinkedHashSet<>(Arrays.asList("spa", "fre", "ita", "glg", "cat", "rum", "ger", "lat", "esp"));

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        final int ptMax = args.length > 2 ? Integer.parseInt(args[2]) : 14, enMax = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        final int othTitles = args.length > 4 ? Integer.parseInt(args[4]) : 60;
        List<String[]> todo = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            todo.add(line.split("\\|"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> res = new ArrayList<>();
        Set<String> seenIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < todo.size(); i++) {
            final String[] p = todo.get(i);
            final boolean oth = i < othTitles;
            res.add(pool.submit(() -> one(p[0], p[1], p[2], out, ptMax, enMax, oth, seenIds)));
        }
        for (Future<String> f : res) { try { System.out.println(f.get()); } catch (Exception e) { System.out.println("erro: " + e); } }
        pool.shutdown();
    }

    static String one(String type, String id, String label, Path out, int ptMax, int enMax, boolean oth, Set<String> seen) throws Exception {
        if (id.startsWith("search:")) {
            String[] s = id.split(":");
            byte[] b = get("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + s[1] + ".json");
            String js = b == null ? "" : new String(b, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"imdb_id\":\"(tt\\d+)\"[^{}]*?\"name\":\"([^\"]*)\"[^{}]*?\"releaseInfo\":\"(\\d{4})").matcher(js);
            id = null;
            while (m.find()) { if (m.group(3).equals(s[2])) { id = m.group(1); break; } }
            if (id == null) {
                Matcher m2 = Pattern.compile("\"id\":\"(tt\\d+)\"").matcher(js);
                id = m2.find() ? m2.group(1) : null;
            }
            if (id == null) return label + ": cinemeta sem resultado";
        }
        if (!seen.add(type + id)) return label + " (" + id + "): id repetido, ignorado";
        Path td = out.resolve(label.replaceAll("[^A-Za-z0-9]+", "_"));
        Files.createDirectories(td);
        byte[] lb = get("https://opensubtitles-v3.strem.io/subtitles/" + type + "/" + id + ".json");
        if (lb == null) return label + ": lista falhou";
        String js = new String(lb, StandardCharsets.UTF_8);
        Files.writeString(td.resolve("list.json"), js);
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(js);
        int pt = 0, en = 0, total = 0;
        Map<String, Integer> othN = new HashMap<>();
        StringBuilder meta = new StringBuilder("# type=" + type + " id=" + id + " label=" + label + "\n");
        Map<String, Integer> langs = new TreeMap<>();
        while (m.find()) {
            String o = m.group();
            String url = field(o, "url"), lang = field(o, "lang").toLowerCase(), mm = field(o, "m");
            if (url.isEmpty()) continue;
            total++;
            langs.merge(lang, 1, Integer::sum);
            String kind = null;
            if (isPt(lang) && pt < ptMax) kind = "pt-" + pt;
            else if (isEn(lang) && en < enMax) kind = "en-" + en;
            else if (oth && OTHER.contains(lang) && othN.getOrDefault(lang, 0) < 1) kind = "oth-" + lang + "-0";
            if (kind == null) continue;
            byte[] d = get(url);
            if (d == null) continue;
            if (kind.startsWith("pt")) pt++; else if (kind.startsWith("en")) en++; else othN.merge(lang, 1, Integer::sum);
            Files.write(td.resolve(kind + ".raw"), d);
            meta.append(kind).append('\t').append(lang).append('\t').append(mm).append('\t').append(o.replace('\n', ' ').replace('\t', ' ')).append('\n');
        }
        Files.writeString(td.resolve("meta.txt"), meta.toString());
        return label + " (" + id + "): itens=" + total + " PT baixadas=" + pt + " EN baixadas=" + en + " outros=" + othN.values().stream().mapToInt(x -> x).sum();
    }

    static String field(String o, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\d+))").matcher(o);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)).replace("\\/", "/") : "";
    }
}
