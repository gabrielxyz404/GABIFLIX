package io.github.gabrielxyz404.gabiflix.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Os serviços e as abas de cada catálogo (iguais aos do celular). */
final class Services {
    private Services() {}

    static final class Service {
        final String id, name, color;
        final String[] tabs;
        Service(String id, String name, String color, String... tabs) { this.id = id; this.name = name; this.color = color; this.tabs = tabs; }
    }

    /** uma fileira (ou grade) do catálogo */
    static final class Section {
        String title;
        String file;          // data/<file>.json com { metas: [...] }
        String home, key;     // data/<home>-home.json, campo top10/movie/series
        boolean top10, numbered, grid, cont, genres;
        Section(String title) { this.title = title; }
        Section file(String f) { file = f; return this; }
        Section home(String h, String k) { home = h; key = k; return this; }
        Section top10() { top10 = true; return this; }
        Section numbered() { numbered = true; return this; }
        Section grid() { grid = true; return this; }
    }

    static final String[] SERVICE_IDS = {"netflix", "disney", "prime", "hbo", "appletv", "paramount", "globoplay", "clarovideo"};

    static final List<Service> ALL = Arrays.asList(
            new Service("netflix", "Netflix", "#e50914", "Início", "Filmes", "Séries"),
            new Service("disney", "Disney+", "#3aa3ff", "Início", "Filmes", "Séries", "Disney", "Pixar", "Marvel", "Star Wars", "National Geographic"),
            new Service("prime", "Prime Video", "#1ba8e0", "Início", "Filmes", "Séries"),
            new Service("hbo", "HBO Max", "#a36bff", "Início", "Filmes", "Séries"),
            new Service("appletv", "Apple TV+", "#f5f5f7", "Início", "Filmes", "Séries"),
            new Service("paramount", "Paramount+", "#2f7dff", "Início", "Filmes", "Séries"),
            new Service("globoplay", "Globoplay", "#f5c518", "Início", "Filmes", "Séries"),
            new Service("clarovideo", "Claro Video", "#ff2a2a", "Início", "Filmes", "Séries"),
            new Service("cinemateca", "CineTime", "#e9c46a", "Início", "Nos cinemas", "Clássicos", "Filmes", "Séries")
    );

    static Service find(String id) {
        for (Service s : ALL) if (s.id.equals(id)) return s;
        return ALL.get(0);
    }

    private static final String[][] DISNEY_BRANDS = {
            {"Disney", "disney"}, {"Pixar", "pixar"}, {"Marvel", "marvel"}, {"Star Wars", "starwars"}, {"National Geographic", "natgeo"}};

    static List<Section> sections(Service s, String tab) {
        List<Section> out = new ArrayList<>();
        if (s.id.equals("cinemateca")) {
            switch (tab) {
                case "Início":
                    out.add(cont());
                    out.add(new Section("Nos cinemas e lançamentos").file("cine-cinemas"));
                    out.add(new Section("Clássicos").file("cine-classicos"));
                    out.add(new Section("Filmes fora dos streamings").file("cine-movie"));
                    out.add(new Section("Séries fora dos streamings").file("cine-series"));
                    break;
                case "Nos cinemas": out.add(new Section("Nos cinemas e lançamentos").file("cine-cinemas").grid()); break;
                case "Clássicos": out.add(new Section("Clássicos").file("cine-classicos").grid()); break;
                case "Filmes": out.add(new Section("Filmes fora dos streamings").file("cine-movie").grid()); break;
                case "Séries": out.add(new Section("Séries fora dos streamings").file("cine-series").grid()); break;
            }
            return out;
        }
        if (s.id.equals("disney")) {
            for (String[] b : DISNEY_BRANDS) if (b[0].equals(tab)) {
                if (tab.equals("Marvel")) out.add(new Section("Ordem cronológica").file("disney-marvel-cronologia").numbered());
                out.add(new Section("Filmes").file("disney-" + b[1] + "-movie"));
                out.add(new Section("Séries").file("disney-" + b[1] + "-series"));
                return out;
            }
        }
        switch (tab) {
            case "Início":
                out.add(cont());
                if (s.id.equals("netflix")) {
                    out.add(new Section("Top 10 filmes hoje").file("netflix-top10-br-movie").top10());
                    out.add(new Section("Top 10 séries hoje").file("netflix-top10-br-series").top10());
                } else out.add(new Section("Top 10 em alta").home(s.id, "top10").top10());
                out.add(new Section("Filmes em alta").home(s.id, "movie"));
                out.add(new Section("Séries em alta").home(s.id, "series"));
                out.add(new Section("Filmes brasileiros").home(s.id, "brMovie"));
                out.add(new Section("Séries brasileiras").home(s.id, "brSeries"));
                if (s.id.equals("netflix")) { // o que era a aba Bombando
                    out.add(new Section("Bombando em Portugal: filmes").file("netflix-top10-pt-movie").numbered());
                    out.add(new Section("Bombando em Portugal: séries").file("netflix-top10-pt-series").numbered());
                }
                if (s.id.equals("disney")) for (String[] b : DISNEY_BRANDS) out.add(new Section(b[0]).file("disney-" + b[1]));
                Section g = new Section("");
                g.genres = true;
                g.home = s.id;
                out.add(g);
                break;
            case "Filmes": out.add(new Section("Todos os filmes").file(s.id + "-movie").grid()); break;
            case "Séries": out.add(new Section("Todas as séries").file(s.id + "-series").grid()); break;
            case "Bombando":
                out.add(new Section("Top 10 filmes hoje no Brasil").file("netflix-top10-br-movie").numbered());
                out.add(new Section("Top 10 séries hoje no Brasil").file("netflix-top10-br-series").numbered());
                out.add(new Section("Top 10 filmes hoje em Portugal").file("netflix-top10-pt-movie").numbered());
                out.add(new Section("Top 10 séries hoje em Portugal").file("netflix-top10-pt-series").numbered());
                break;
        }
        return out;
    }

    private static Section cont() {
        Section c = new Section("Continuar assistindo");
        c.cont = true;
        return c;
    }
}
