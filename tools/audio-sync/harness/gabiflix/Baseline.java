package io.github.gabrielxyz404.gabiflix;

import java.util.List;

/** Ponte pública para o consenso SEM referência do app (SubtitleAlign é package-private): base de comparação do ranking por áudio. */
public final class Baseline {
    private Baseline() {}

    public static long[] starts(String srt) { return SubtitleAlign.alignStarts(srt); }

    public static int[] groups(List<long[]> starts) { return SubtitleAlign.consensusGroups(starts); }

    /** grupo escolhido pelo app (ou -1); nomes/hash desconhecidos no corpus: sem bônus */
    public static int pick(int[] comp, long[] lastMs, long videoLenMs) {
        boolean[] hash = new boolean[comp.length];
        double[] name = new double[comp.length];
        return SubtitleAlign.pickGroup(comp, hash, name, lastMs, videoLenMs, null);
    }
}
