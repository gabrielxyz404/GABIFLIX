package io.github.gabrielxyz404.gabiflix;
/** só para compilar SubtitleSync na JVM (teste de paridade): httpGet devolve o corpo preparado pelo teste */
final class PlayerActivity {
    static byte[] mock = null;
    static long lastCueEnd(String text) { return SubtitleText.lastCueEnd(text); }
    static byte[] httpGet(String u) throws java.io.IOException { return mock; }
}

/** só para compilar SubtitleSync na JVM: o registro de migalhas do app (CrashReporter) não existe aqui */
final class CrashReporter {
    static void crumb(String s) {}
}
