package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Avaliação com legendas reais: fase 1 (SubtitleAlignV1) x fase 2 (SubtitleAlign). Uso: Eval fetch.log [maxTitulos] */
public class Eval {
    static final class Sub {
        String label, kind, lang, m, rel; long fps, lastMs; long[] starts; int idx;
        String name() { return rel; }
    }
    static long[] decode(String b64) throws IOException {
        byte[] z = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(b64))).readAllBytes();
        ArrayList<Long> l = new ArrayList<>();
        long prev = 0; int i = 0;
        while (i < z.length) { long d = 0; int sh = 0; while (true) { int b = z[i++] & 0xFF; d |= (long) (b & 127) << sh; sh += 7; if (b < 128) break; } prev += d; l.add(prev); }
        long[] a = new long[l.size()]; for (int k = 0; k < a.length; k++) a[k] = l.get(k); return a;
    }
    static String f1(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    static String sec(long ms) { return String.format(Locale.ROOT, "%+.1fs", ms / 1000.0); }

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String, ArrayList<Sub>> pts = new LinkedHashMap<>(), ens = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            int p = line.indexOf("CORPUS|");
            if (p < 0) continue;
            String[] f = line.substring(p).split("\\|", 10);
            Sub s = new Sub();
            s.label = f[1]; s.kind = f[2]; s.lang = f[3]; s.m = f[4];
            s.fps = f[5].isEmpty() ? 0 : Long.parseLong(f[5]);
            s.rel = f[6]; s.lastMs = Long.parseLong(f[7]);
            s.starts = decode(f[9]);
            s.idx = Integer.parseInt(s.kind.substring(3));
            (s.kind.startsWith("pt") ? pts : ens).computeIfAbsent(s.label, k -> new ArrayList<>()).add(s);
        }
        boolean verbose = args.length > 1 && args[1].equals("v");
        boolean consOnly = System.getProperty("cons.only") != null;
        System.out.println("## Avaliação com legendas reais: fase 1 x fase 2");
        System.out.println("Títulos com PT e EN: " + pts.keySet().stream().filter(ens::containsKey).count());
        System.out.println();
        System.out.println("| título | PT | EN | alinham v1 | alinham v2 | só v2 | perdidas | por partes | escala livre | rejeitadas v2 (versão dif.) | maior grupo PT | parciais (CD1/CD2) |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
        int tPT = 0, tV1 = 0, tV2 = 0, tNew = 0, tLost = 0, tPiece = 0, tFree = 0, tRej = 0;
        StringBuilder pieceLog = new StringBuilder(), freeLog = new StringBuilder(), lostLog = new StringBuilder(), consLog = new StringBuilder();
        long msV1 = 0, msV2 = 0;
        int consTests = 0, consRight = 0, consOld = 0, consApplied = 0, consAppliedRight = 0, consAppliedOld = 0;
        for (String label : pts.keySet()) {
            ArrayList<Sub> P = pts.get(label), E = ens.getOrDefault(label, new ArrayList<>());
            if (E.isEmpty()) { System.out.println("| " + label + " | " + P.size() + " | 0 | - | - | - | - | - | - | - | - |"); continue; }
            int a1 = 0, a2 = 0, nw = 0, lost = 0, pc = 0, fr = 0, rej = 0, pa = 0;
            for (Sub p : consOnly ? new ArrayList<Sub>() : P) {
                boolean ok1 = false, ok2 = false, ver = false, piece = false, free = false, par = false;
                String best2 = "";
                for (Sub e : E) {
                    long t0 = System.nanoTime();
                    SubtitleAlignV1.Fit f1 = SubtitleAlignV1.align(e.starts, p.starts);
                    long t1 = System.nanoTime();
                    SubtitleAlign.Fit f2 = SubtitleAlign.align(e.starts, p.starts);
                    long t2 = System.nanoTime();
                    msV1 += (t1 - t0) / 1000000; msV2 += (t2 - t1) / 1000000;
                    if (f1.ok()) ok1 = true;
                    if (f2.ok()) { ok2 = true; if (f2.coverage < 0.75) par = true; if (f2.piecewise()) { piece = true;
                        StringBuilder sb = new StringBuilder("- " + label + " PT" + p.idx + " x EN" + e.idx + ": nota " + f1(f2.score) + " (v1 " + f1(f1.score) + (f1.ok() ? " ok" : " rejeitada") + "), escala " + String.format(Locale.ROOT, "%.4f", f2.scale) + ", cortes em");
                        for (long c : f2.cuts) sb.append(' ').append(c / 60000).append("min").append(String.format("%02d", (c / 1000) % 60)); sb.append(", deslocamentos");
                        for (int q = 0; q < f2.offs.length; q++) sb.append(' ').append(sec(f2.offs[q])).append("(").append(f2.segCues[q]).append(" falas, nota ").append(f1(f2.segScores[q])).append(")");
                        pieceLog.append(sb).append('\n'); }
                        if (f2.freeScale) { free = true; freeLog.append("- " + label + " PT" + p.idx + " x EN" + e.idx + ": escala " + String.format(Locale.ROOT, "%.4f", f2.scale) + " off " + sec(f2.offsetMs) + " nota " + f1(f2.score) + " (v1 " + f1(f1.score) + (f1.ok() ? " ok" : " rejeitada") + ")\n"); } }
                    if (f2.differentVersion()) ver = true;
                    if (f1.ok() && !f2.ok()) lostLog.append("- PERDIDA " + label + " PT" + p.idx + " x EN" + e.idx + " v1 " + f1(f1.score) + " v2 " + f1(f2.score) + "\n");
                    if (verbose) System.out.println("  " + label + " PT" + p.idx + " x EN" + e.idx + " v1=" + f1.ok() + "/" + f1(f1.score) + " v2=" + f2.ok() + "/" + f1(f2.score) + " esc=" + String.format(Locale.ROOT, "%.4f", f2.scale) + " off=" + f2.offsetMs + (f2.piecewise() ? " PARTES" : ""));
                }
                if (ok1) a1++;
                if (ok2) a2++;
                if (ok2 && !ok1) nw++;
                if (ok1 && !ok2) lost++;
                if (piece) pc++;
                if (par) pa++;
                if (free) fr++;
                if (!ok2 && ver) rej++;
            }
            // consenso: grupos por concordância bruta
            ArrayList<long[]> st = new ArrayList<>();
            for (Sub p : P) st.add(p.starts);
            int[] comp = SubtitleAlign.consensusGroups(st);
            int ng = 0; for (int c : comp) ng = Math.max(ng, c + 1);
            int[] sizes = new int[ng]; for (int c : comp) sizes[c]++;
            int maxG = 0; for (int s : sizes) maxG = Math.max(maxG, s);
            consLog.append("- " + label + ": " + P.size() + " PT em grupos " + Arrays.toString(sizes) + "\n");
            // simulação: o vídeo tem o tempo de EN j e o nome da release dela; a PT certa é a que bate com ela sem ajuste (>= 45% a 300 ms)
            for (Sub e : E) {
                boolean[] truth = new boolean[P.size()]; boolean any = false;
                for (int i = 0; i < P.size(); i++) { truth[i] = SubtitleAlign.matchesTol(e.starts, P.get(i).starts, 0, 300) / (double) Math.min(e.starts.length, P.get(i).starts.length) >= 0.45; any |= truth[i]; }
                if (!any || P.size() < 3) continue;
                boolean[] hash = new boolean[P.size()]; double[] nm = new double[P.size()]; long[] last = new long[P.size()];
                for (int i = 0; i < P.size(); i++) { nm[i] = SubtitleAlign.nameSimilarity(e.rel.split(" ## ")[0], P.get(i).rel); last[i] = P.get(i).lastMs; }
                long len = Math.round(e.lastMs / 0.95);
                // antigo: maior grupo de fins parecidos (1,5%) e, nele, o de menor diferença + 0,01 x posição
                int oldPick = -1; { long ref = 0; int bn = -1;
                    for (int i = 0; i < P.size(); i++) { if (last[i] < len * 0.6 || last[i] > len * 1.05) continue; int n = 0; for (int j = 0; j < P.size(); j++) if (j != i && Math.abs((double) last[j] / last[i] - 1) <= 0.015) n++; if (n > bn) { bn = n; ref = last[i]; } }
                    double bs = 1e9; for (int i = 0; i < P.size(); i++) { if (last[i] < len * 0.5 || last[i] > len * 1.12) continue; double sc = Math.min(0.3, Math.max(0, (ref > 0 ? Math.abs((double) last[i] / ref - 1) : 0.3) - 0.005)) + 0.01 * i; if (sc < bs) { bs = sc; oldPick = i; } } }
                int g = SubtitleAlign.pickGroup(comp, hash, nm, last, len, null);
                int newPick = oldPick; boolean applied = false;
                if (g >= 0 && sizes[g] >= 2) { applied = true; double bs = -1; for (int i = 0; i < P.size(); i++) if (comp[i] == g) { double sc = nm[i] - 0.001 * i; if (bs < -0.5 || sc > bs) { bs = sc; newPick = i; } } }
                consTests++;
                if (oldPick >= 0 && truth[oldPick]) consOld++;
                if (newPick >= 0 && truth[newPick]) consRight++;
                if (applied) { consApplied++; if (truth[newPick]) consAppliedRight++; if (oldPick >= 0 && truth[oldPick]) consAppliedOld++; }
            }
            tPT += P.size(); tV1 += a1; tV2 += a2; tNew += nw; tLost += lost; tPiece += pc; tFree += fr; tRej += rej;
            System.out.println("| " + label + " | " + P.size() + " | " + E.size() + " | " + a1 + " | " + a2 + " | " + nw + " | " + lost + " | " + pc + " | " + fr + " | " + rej + " | " + maxG + " | " + pa + " |");
        }
        System.out.println("| **total** | " + tPT + " | | " + tV1 + " | " + tV2 + " | " + tNew + " | " + tLost + " | " + tPiece + " | " + tFree + " | " + tRej + " | | |");
        System.out.println();
        System.out.println("Tempo de CPU (desktop): v1 " + msV1 + " ms, v2 " + msV2 + " ms no total.");
        // controle negativo: PT de um título contra EN de outro
        int negPairs = 0, neg1 = 0, neg2 = 0, sPairs = 0, s1 = 0, s2 = 0;
        StringBuilder negLog = new StringBuilder();
        ArrayList<String> labels = new ArrayList<>(pts.keySet());
        for (int a = 0; a < labels.size(); a++) for (int b = 0; b < labels.size(); b++) {
            if (consOnly || a == b || !ens.containsKey(labels.get(b))) continue;
            ArrayList<Sub> P = pts.get(labels.get(a)), E = ens.get(labels.get(b));
            for (int i = 0; i < Math.min(3, P.size()); i++) for (int j = 0; j < Math.min(2, E.size()); j++) {
                boolean sameShow = labels.get(a).replaceAll(" S\\d+E\\d+$", "").equals(labels.get(b).replaceAll(" S\\d+E\\d+$", "")) && labels.get(a).matches(".* S\\d+E\\d+$");
                if (sameShow) sPairs++; else negPairs++;
                SubtitleAlignV1.Fit f1 = SubtitleAlignV1.align(E.get(j).starts, P.get(i).starts);
                SubtitleAlign.Fit f2 = SubtitleAlign.align(E.get(j).starts, P.get(i).starts);
                if (f1.ok()) { if (sameShow) s1++; else neg1++; }
                if (f2.ok()) { if (sameShow) s2++; else neg2++; negLog.append("- FALSO POSITIVO: PT de " + labels.get(a) + " x EN de " + labels.get(b) + " nota " + f1(f2.score) + (f2.piecewise() ? " por partes" : "") + "\n"); }
            }
        }
        System.out.println();
        System.out.println("### Controle negativo (PT de um título contra EN de outro; tem de ser 0)");
        System.out.println("Pares (outro título): " + negPairs + " | aceitos v1: " + neg1 + " | aceitos v2: " + neg2);
        System.out.println("Pares (mesma série, outro episódio): " + sPairs + " | aceitos v1: " + s1 + " | aceitos v2: " + s2);
        System.out.print(negLog);
        System.out.println();
        System.out.println("### Consenso (sem referência): o grupo escolhido contém a PT certa?");
        System.out.println("Simulação: o vídeo tem o tempo de uma EN e o nome da release dela; certa = PT que bate com ela sem ajuste (>= 45% a 300 ms). Testes: " + consTests
                + " | fase 1 (fim da legenda): " + consOld + " certas | fase 2 (consenso onde há grupo >= 2, senão a regra antiga): " + consRight
                + " | o consenso entrou em " + consApplied + " testes: acertou " + consAppliedRight + " (a regra antiga acertaria " + consAppliedOld + ")");
        System.out.print(consLog);
        System.out.println();
        System.out.println("### Ajustes por partes aceitos (cortes)");
        System.out.print(pieceLog.length() == 0 ? "(nenhum)\n" : pieceLog);
        System.out.println();
        System.out.println("### Escala livre (fora das razões fixas de fps)");
        System.out.print(freeLog.length() == 0 ? "(nenhuma)\n" : freeLog);
        System.out.println();
        System.out.println("### Perdidas (aceitas na fase 1 e rejeitadas na fase 2; tem de ser 0)");
        System.out.print(lostLog.length() == 0 ? "(nenhuma)\n" : lostLog);
    }
}
