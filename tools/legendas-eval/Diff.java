package io.github.gabrielxyz404.gabiflix;

import java.nio.file.*;
import java.util.*;

/** Compara duas execuções do Eval (antes x depois): tabela de estatísticas e pares que mudaram de aceito/rejeitado. Uso: Diff stats_antes stats_depois cases_antes cases_depois */
public class Diff {
    static Map<String, String> read(String p) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : Files.readAllLines(Paths.get(p))) { int i = l.indexOf('\t'); if (i > 0) m.put(l.substring(0, i), l.substring(i + 1)); }
        return m;
    }
    public static void main(String[] a) throws Exception {
        Map<String, String> s1 = read(a[0]), s2 = read(a[1]);
        System.out.println("## Antes x depois");
        System.out.println("| medida | antes | depois |");
        System.out.println("|---|---|---|");
        for (String k : s1.keySet()) System.out.println("| " + k + " | " + s1.get(k) + " | " + s2.getOrDefault(k, "-") + " |");
        Map<String, String> c1 = read(a[2]), c2 = read(a[3]);
        int lost = 0, gained = 0, same = 0, moved = 0;
        StringBuilder sl = new StringBuilder(), sg = new StringBuilder(), sm = new StringBuilder();
        for (String k : c1.keySet()) {
            String[] x = c1.get(k).split("\t"), y = c2.containsKey(k) ? c2.get(k).split("\t") : null;
            if (y == null) continue;
            boolean o1 = x[0].equals("true"), o2 = y[0].equals("true");
            if (o1 && !o2) { lost++; if (sl.length() < 3500) sl.append("- ").append(k).append(": antes ").append(String.join(" ", x)).append(" | depois ").append(String.join(" ", y)).append("\n"); }
            else if (!o1 && o2) { gained++; if (sg.length() < 2000) sg.append("- ").append(k).append(": antes ").append(String.join(" ", x)).append(" | depois ").append(String.join(" ", y)).append("\n"); }
            else {
                same++;
                if (o1 && o2) {
                    long off1 = Long.parseLong(x[3]), off2 = Long.parseLong(y[3]);
                    double e1 = Double.parseDouble(x[2]), e2 = Double.parseDouble(y[2]);
                    if (Math.abs(off1 - off2) > 400 || Math.abs(e1 - e2) > 0.0006 || !x[4].equals(y[4])) { moved++; if (sm.length() < 2500) sm.append("- ").append(k).append(": antes ").append(String.join(" ", x)).append(" | depois ").append(String.join(" ", y)).append("\n"); }
                }
            }
        }
        System.out.println();
        System.out.println("Pares PT x EN: aceitos antes e agora rejeitados: **" + lost + "**; rejeitados antes e agora aceitos: " + gained + "; sem mudança de decisão: " + same + " (dos quais com transformação diferente: " + moved + ").");
        if (sl.length() > 0) System.out.println("Aceitos antes e rejeitados agora (colunas: aceito, nota, escala, deslocamento, cortes, cobertura, fração de falas):\n" + sl);
        if (sg.length() > 0) System.out.println("Ganhos:\n" + sg);
        if (sm.length() > 0) System.out.println("Aceitos nos dois, transformação mudou:\n" + sm);
    }
}
