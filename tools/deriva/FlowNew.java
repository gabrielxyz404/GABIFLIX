import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** O laço do app (código novo): AudioSyncSession.analyze = 3 janelas espalhadas (ou densas) + janelas extras se há suspeita de deriva. */
class Flow {
    static Result run(Cue[] cues, long dur, DerivaEval.Src src, String mode, StringBuilder diag) throws Exception {
        AudioAligner.Params P = new AudioAligner.Params();
        Window[][] used = new Window[1][];
        Result r = AudioSyncSession.analyze(cues, dur, "spread6".equals(mode) || "uniao".equals(mode) ? 6 : 3, 90000, (st, ln, i, t) -> src.get(st, ln), P, !"dense".equals(mode), "uniao".equals(mode) ? 3 : 0, used);
        StringBuilder sb = new StringBuilder();
        for (Result.Win w : r.wins) sb.append(String.format(Locale.ROOT, "[%ds d=%.0f ncc=%.2f 2o=%.2f z=%.1f %s] ", w.startMs / 1000, w.delayMs, w.ncc, w.second, w.z, w.confident ? "ok" : "-"));
        diag.append(sb).append("fin{valid=").append(r.winsValid).append(" contra=").append(r.shiftContra ? 1 : 0).append(" consenso=").append(r.winConsensus ? 1 : 0).append(" planB=").append(r.planB ? 1 : 0).append(" ").append(r.finDetail).append("}deriva{").append(r.driftNote).append("; confiaveis=").append(r.driftConfident).append(" inclinacao=").append(String.format(Locale.ROOT, "%.6f", r.driftSlope)).append("}");
        return r;
    }
}
