import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** O laco do app ANTES (origin/main, AudioSyncCore.run): 3 janelas de 90 s por densidade de fala e UMA resolucao (nao pede janelas extras). */
class Flow {
    static Result run(Cue[] cues, long dur, Bench300.Src src, StringBuilder diag) throws Exception {
        AudioAligner.Params P = new AudioAligner.Params();
        AudioSyncSession ses = new AudioSyncSession(cues, dur, P);
        for (long[] w : AudioSyncSession.planDense(cues, dur, 3, 90000)) ses.addWindow(src.get(w[0], w[1]));
        Result r = ses.solve();
        diag.append("janelas:").append(ses.windows());
        return r;
    }
}
