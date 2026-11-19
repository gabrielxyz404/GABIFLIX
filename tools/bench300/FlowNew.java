import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** O laco do app no ramo da deriva (AudioSyncCore.run em run-deriva): analyze() = 3 janelas densas + 6 espalhadas (DRIFT_WIN) + ate 2 rodadas de janelas extras se ha suspeita de deriva. */
class Flow {
    static Result run(Cue[] cues, long dur, Bench300.Src src, StringBuilder diag) throws Exception {
        AudioAligner.Params P = new AudioAligner.Params();
        Window[][] used = new Window[1][];
        Result r = AudioSyncSession.analyze(cues, dur, 6, 90000, (st, ln, i, total) -> src.get(st, ln), P, true, 3, used);
        StringBuilder sb = new StringBuilder();
        for (Result.Win w : r.wins) sb.append(String.format(Locale.ROOT, "[%ds d=%.0f ncc=%.2f 2o=%.2f z=%.1f %s] ", w.startMs / 1000, w.delayMs, w.ncc, w.second, w.z, w.confident ? "ok" : "-"));
        diag.append(sb).append("deriva{").append(r.driftNote).append("; confiaveis=").append(r.driftConfident).append(" inclinacao=").append(String.format(Locale.ROOT, "%.6f", r.driftSlope)).append(" provada=").append(r.driftScale ? 1 : 0).append("}");
        return r;
    }
}
