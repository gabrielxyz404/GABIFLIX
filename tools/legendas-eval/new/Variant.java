package io.github.gabrielxyz404.gabiflix;

/** Versão DEPOIS (branch legendas-revisao): usa SubtitleText e as falas de diálogo (sem [descrições]/♪) */
final class Variant {
    static final String NAME = "depois";
    static String decode(byte[] data) { return SubtitleText.decode(data); }
    static boolean looksPt(String text) { return SubtitleText.looksPortuguese(text); }
    static long lastEnd(String text) { return SubtitleText.lastCueEnd(text); }
    static long[] starts(String text) { return SubtitleAlign.alignStarts(text); }
    static double nullScore(SubtitleAlign.Fit f) { return f.nullScore; }
}
