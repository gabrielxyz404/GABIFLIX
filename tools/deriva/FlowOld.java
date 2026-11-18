import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** O laço do app ANTES (origin/main): 3 janelas por densidade e uma só resolução. */
class Flow {
    static Result run(Cue[] cues, long dur, DerivaEval.Src src, String mode, StringBuilder diag) throws Exception {
        AudioAligner.Params P = new AudioAligner.Params();
        AudioSyncSession ses = new AudioSyncSession(cues, dur, P);
        for (long[] w : AudioSyncSession.planDense(cues, dur, 3, 90000)) ses.addWindow(src.get(w[0], w[1]));
        return ses.solve();
    }
}
