package io.github.gabrielxyz404.gabiflix.audiosync;

/**
 * Porta em Java do VAD do WebRTC (GMM de 6 bandas, aritmética de ponto fixo idêntica à do C: common_audio/vad, BSD).
 * Aceita PCM 16 bits mono a 8 kHz ou 16 kHz (16 kHz é rebaixado para 8 kHz como no original) e quadros de 10 ms.
 * Cada atribuição a int16 do C vira um (short) aqui: é isso que mantém os resultados bit a bit iguais (testado contra o py-webrtcvad).
 * Para portar para Swift use Int16/Int32 com operadores &+ &- &* (sem trap de overflow) e >> aritmético.
 */
public final class WebRtcVad implements Vad {
    private static final int NUM_CH = 6, NUM_GAUSS = 2, TABLE = 12, MIN_ENERGY = 10;

    private static final short[] SPECTRUM_WEIGHT = {6, 8, 10, 12, 14, 16};
    private static final int NOISE_UPDATE = 655, SPEECH_UPDATE = 6554, BACK_ETA = 154;
    private static final int[] MIN_DIFF = {544, 544, 576, 576, 576, 576};
    private static final int[] MAX_SPEECH = {11392, 11392, 11520, 11520, 11520, 11520};
    private static final int[] MIN_MEAN = {640, 768};
    private static final int[] MAX_NOISE = {9216, 9088, 8960, 8832, 8704, 8576};
    private static final int[] NOISE_W = {34, 62, 72, 66, 53, 25, 94, 66, 56, 62, 75, 103};
    private static final int[] SPEECH_W = {48, 82, 45, 87, 50, 47, 80, 46, 83, 41, 78, 81};
    private static final short[] NOISE_MEANS0 = {6738, 4892, 7065, 6715, 6771, 3369, 7646, 3863, 7820, 7266, 5020, 4362};
    private static final short[] SPEECH_MEANS0 = {8306, 10085, 10078, 11823, 11843, 6309, 9473, 9571, 10879, 7581, 8180, 7483};
    private static final short[] NOISE_STDS0 = {378, 1064, 493, 582, 688, 593, 474, 697, 475, 688, 421, 455};
    private static final short[] SPEECH_STDS0 = {555, 505, 567, 524, 585, 1231, 509, 828, 492, 1540, 1079, 850};
    private static final int MAX_SPEECH_FRAMES = 6, MIN_STD = 384;

    private static final int[][] OVER1 = {{8, 4, 3}, {8, 4, 3}, {6, 3, 2}, {6, 3, 2}};
    private static final int[][] OVER2 = {{14, 7, 5}, {14, 7, 5}, {9, 5, 3}, {9, 5, 3}};
    private static final int[][] LOCAL = {{24, 21, 24}, {37, 32, 37}, {82, 78, 82}, {94, 94, 94}};
    private static final int[][] GLOBAL = {{57, 48, 57}, {100, 80, 100}, {285, 260, 285}, {1100, 1050, 1100}};

    private static final int LOG_CONST = 24660, LOG_ENERGY_INT = 14336;
    private static final int[] HP_ZERO = {6631, -13262, 6631};
    private static final int[] HP_POLE = {16384, -7756, 5620};
    private static final int[] ALLPASS_Q15 = {20972, 5571};
    private static final int[] OFFSET_VEC = {368, 368, 272, 176, 176, 176};
    private static final int[] ALLPASS_Q13 = {5243, 1392};
    private static final int SMOOTH_DOWN = 6553, SMOOTH_UP = 32439;
    private static final int COMP_VAR = 22005, LOG2_EXP = 5909;

    private final int mode;
    // estado
    private int overHang, numOfSpeech, frameCounter;
    private final int[] down = new int[2];
    private final short[] noiseMeans = new short[TABLE], speechMeans = new short[TABLE], noiseStds = new short[TABLE], speechStds = new short[TABLE];
    private final short[] lowValue = new short[16 * NUM_CH + 1], indexVec = new short[16 * NUM_CH + 1];
    private final short[] meanValue = new short[NUM_CH];
    private final short[] upper = new short[5], lower = new short[5], hpState = new short[4];

    /** mode 0 (qualidade) .. 3 (muito agressivo) */
    public WebRtcVad(int mode) {
        this.mode = mode;
        reset();
    }

    public void reset() {
        overHang = 0; numOfSpeech = 0; frameCounter = 0;
        down[0] = 0; down[1] = 0;
        for (int i = 0; i < TABLE; i++) {
            noiseMeans[i] = NOISE_MEANS0[i]; speechMeans[i] = SPEECH_MEANS0[i];
            noiseStds[i] = NOISE_STDS0[i]; speechStds[i] = SPEECH_STDS0[i];
        }
        for (int i = 0; i < 16 * NUM_CH; i++) { lowValue[i] = 10000; indexVec[i] = 0; }
        java.util.Arrays.fill(upper, (short) 0);
        java.util.Arrays.fill(lower, (short) 0);
        java.util.Arrays.fill(hpState, (short) 0);
        for (int i = 0; i < NUM_CH; i++) meanValue[i] = 1600;
    }

    // ------------------------------------------------------------------ Vad
    /** probabilidade (0/1) a cada 10 ms; a taxa de entrada deve ser 8000 ou 16000 */
    @Override
    public float[] speechProb(short[] pcm, int n, int rate) {
        int frame = rate / 100;
        int frames = n / frame;
        float[] out = new float[frames];
        short[] buf = new short[frame];
        for (int f = 0; f < frames; f++) {
            System.arraycopy(pcm, f * frame, buf, 0, frame);
            out[f] = process(buf, rate) ? 1f : 0f;
        }
        return out;
    }

    @Override public String name() { return "webrtc-m" + mode; }

    /** um quadro de 10 ms (80 amostras a 8 kHz ou 160 a 16 kHz) */
    public boolean process(short[] frame, int rate) {
        short[] nb;
        int len;
        if (rate == 16000) {
            nb = new short[80];
            downsampling(frame, nb, 160);
            len = 80;
        } else {
            nb = frame;
            len = 80;
        }
        short[] feat = new short[NUM_CH];
        int total = calculateFeatures(nb, len, feat);
        int v = gmmProbability(feat, total, len);
        return v > 0;
    }

    // ------------------------------------------------------------------ vad_sp
    private void downsampling(short[] in, short[] out, int inLen) {
        int t1 = down[0], t2 = down[1];
        int half = inLen >> 1;
        int ip = 0, op = 0;
        for (int n = 0; n < half; n++) {
            int tmp16a = (short) ((t1 >> 1) + ((ALLPASS_Q13[0] * (int) (short) in[ip]) >> 14));
            out[op] = (short) tmp16a;
            t1 = (int) in[ip++] - ((ALLPASS_Q13[0] * tmp16a) >> 12);
            int tmp16b = (short) ((t2 >> 1) + ((ALLPASS_Q13[1] * (int) in[ip]) >> 14));
            out[op] = (short) (out[op] + tmp16b);
            op++;
            t2 = (int) in[ip++] - ((ALLPASS_Q13[1] * tmp16b) >> 12);
        }
        down[0] = t1; down[1] = t2;
    }

    private short findMinimum(int featureValue, int channel) {
        int position = -1;
        int offset = channel << 4;
        int currentMedian = 1600;
        int alpha = 0;
        for (int i = 0; i < 16; i++) {
            if (indexVec[offset + i] != 100) {
                indexVec[offset + i]++;
            } else {
                for (int j = i; j < 15; j++) {
                    lowValue[offset + j] = lowValue[offset + j + 1];
                    indexVec[offset + j] = indexVec[offset + j + 1];
                }
                indexVec[offset + 15] = 101;
                lowValue[offset + 15] = 10000;
            }
        }
        short[] sv = lowValue;
        int o = offset;
        if (featureValue < sv[o + 7]) {
            if (featureValue < sv[o + 3]) {
                if (featureValue < sv[o + 1]) {
                    position = featureValue < sv[o] ? 0 : 1;
                } else if (featureValue < sv[o + 2]) position = 2;
                else position = 3;
            } else if (featureValue < sv[o + 5]) {
                position = featureValue < sv[o + 4] ? 4 : 5;
            } else if (featureValue < sv[o + 6]) position = 6;
            else position = 7;
        } else if (featureValue < sv[o + 15]) {
            if (featureValue < sv[o + 11]) {
                if (featureValue < sv[o + 9]) {
                    position = featureValue < sv[o + 8] ? 8 : 9;
                } else if (featureValue < sv[o + 10]) position = 10;
                else position = 11;
            } else if (featureValue < sv[o + 13]) {
                position = featureValue < sv[o + 12] ? 12 : 13;
            } else if (featureValue < sv[o + 14]) position = 14;
            else position = 15;
        }
        if (position > -1) {
            for (int i = 15; i > position; i--) {
                lowValue[o + i] = lowValue[o + i - 1];
                indexVec[o + i] = indexVec[o + i - 1];
            }
            lowValue[o + position] = (short) featureValue;
            indexVec[o + position] = 1;
        }
        if (frameCounter > 2) currentMedian = lowValue[o + 2];
        else if (frameCounter > 0) currentMedian = lowValue[o];
        if (frameCounter > 0) {
            alpha = currentMedian < meanValue[channel] ? SMOOTH_DOWN : SMOOTH_UP;
        }
        int tmp32 = (alpha + 1) * meanValue[channel];
        tmp32 += (32767 - alpha) * currentMedian;
        tmp32 += 16384;
        meanValue[channel] = (short) (tmp32 >> 15);
        return meanValue[channel];
    }

    // ------------------------------------------------------------------ vad_filterbank
    private void highPassFilter(short[] in, int inOff, int len, short[] state, short[] out, int outOff) {
        for (int i = 0; i < len; i++) {
            int x = in[inOff + i];
            int tmp32 = HP_ZERO[0] * x;
            tmp32 += HP_ZERO[1] * state[0];
            tmp32 += HP_ZERO[2] * state[1];
            state[1] = state[0];
            state[0] = (short) x;
            tmp32 -= HP_POLE[1] * state[2];
            tmp32 -= HP_POLE[2] * state[3];
            state[3] = state[2];
            state[2] = (short) (tmp32 >> 14);
            out[outOff + i] = state[2];
        }
    }

    private static void allPassFilter(short[] in, int inOff, int len, int coef, short[] state, int stateIdx, short[] out, int outOff) {
        int state32 = ((int) state[stateIdx]) << 16;
        int ip = inOff;
        for (int i = 0; i < len; i++) {
            int tmp32 = state32 + coef * (int) in[ip];
            int tmp16 = (short) (tmp32 >> 16);
            out[outOff + i] = (short) tmp16;
            state32 = ((int) in[ip]) << 14;
            state32 -= coef * tmp16;
            state32 <<= 1;
            ip += 2;
        }
        state[stateIdx] = (short) (state32 >> 16);
    }

    private void splitFilter(short[] in, int len, int band, short[] hpOut, short[] lpOut) {
        int half = len >> 1;
        allPassFilter(in, 0, half, ALLPASS_Q15[0], upper, band, hpOut, 0);
        allPassFilter(in, 1, half, ALLPASS_Q15[1], lower, band, lpOut, 0);
        for (int i = 0; i < half; i++) {
            short t = hpOut[i];
            hpOut[i] = (short) (hpOut[i] - lpOut[i]);
            lpOut[i] = (short) (lpOut[i] + t);
        }
    }

    private static int getSizeInBits(int n) {
        int bits = ((0xFFFF0000 & n) != 0) ? 16 : 0;
        if ((0x0000FF00 & (n >>> bits)) != 0) bits += 8;
        if ((0x000000F0 & (n >>> bits)) != 0) bits += 4;
        if ((0x0000000C & (n >>> bits)) != 0) bits += 2;
        if ((0x00000002 & (n >>> bits)) != 0) bits += 1;
        if ((0x00000001 & (n >>> bits)) != 0) bits += 1;
        return bits;
    }

    private static int normW32(int a) {
        if (a == 0) return 0;
        if (a < 0) a = ~a;
        int zeros = (0xFFFF8000 & a) == 0 ? 16 : 0;
        if ((0xFF800000 & (a << zeros)) == 0) zeros += 8;
        if ((0xF8000000 & (a << zeros)) == 0) zeros += 4;
        if ((0xE0000000 & (a << zeros)) == 0) zeros += 2;
        if ((0xC0000000 & (a << zeros)) == 0) zeros += 1;
        return zeros;
    }

    private static int normU32(int a) {
        if (a == 0) return 0;
        int zeros = (0xFFFF0000 & a) == 0 ? 16 : 0;
        if ((0xFF000000 & (a << zeros)) == 0) zeros += 8;
        if ((0xF0000000 & (a << zeros)) == 0) zeros += 4;
        if ((0xC0000000 & (a << zeros)) == 0) zeros += 2;
        if ((0x80000000 & (a << zeros)) == 0) zeros += 1;
        return zeros;
    }

    private int totalEnergy;

    private short logOfEnergy(short[] data, int len, int offset) {
        // WebRtcSpl_Energy com fator de escala
        int smax = -1;
        for (int i = 0; i < len; i++) {
            int v = data[i];
            int sabs = v > 0 ? v : -v;
            if (sabs > smax) smax = sabs;
        }
        int nbits = getSizeInBits(len);
        int scaling;
        if (smax == 0) scaling = 0;
        else {
            int t = normW32(smax * smax);
            scaling = t > nbits ? 0 : nbits - t;
        }
        int en = 0;
        for (int i = 0; i < len; i++) en += (((int) data[i]) * ((int) data[i])) >> scaling;
        int totRshifts = scaling;
        int energy = en; // uint32
        int logEnergy;
        if (energy != 0) {
            int normRshifts = 17 - normU32(energy);
            int log2Energy = LOG_ENERGY_INT;
            totRshifts += normRshifts;
            if (normRshifts < 0) energy <<= -normRshifts;
            else energy >>>= normRshifts;
            log2Energy = (short) (log2Energy + (short) ((energy & 0x00003FFF) >> 4));
            logEnergy = (short) ((((LOG_CONST * log2Energy) >> 19)) + ((((int) (short) totRshifts) * LOG_CONST) >> 9));
            if (logEnergy < 0) logEnergy = 0;
        } else {
            return (short) offset;
        }
        logEnergy = (short) (logEnergy + offset);
        if (totalEnergy <= MIN_ENERGY) {
            if (totRshifts >= 0) totalEnergy = (short) (totalEnergy + MIN_ENERGY + 1);
            else totalEnergy = (short) (totalEnergy + (short) (energy >>> -totRshifts));
        }
        return (short) logEnergy;
    }

    private int calculateFeatures(short[] in, int dataLength, short[] features) {
        totalEnergy = 0;
        short[] hp120 = new short[120], lp120 = new short[120], hp60 = new short[60], lp60 = new short[60];
        int half = dataLength >> 1;
        int length = half;
        splitFilter(in, dataLength, 0, hp120, lp120);
        splitFilter(hp120, length, 1, hp60, lp60);
        length >>= 1;
        features[5] = logOfEnergy(hp60, length, OFFSET_VEC[5]);
        features[4] = logOfEnergy(lp60, length, OFFSET_VEC[4]);
        length = half;
        splitFilter(lp120, length, 2, hp60, lp60);
        length >>= 1;
        features[3] = logOfEnergy(hp60, length, OFFSET_VEC[3]);
        splitFilter(lp60, length, 3, hp120, lp120);
        length >>= 1;
        features[2] = logOfEnergy(hp120, length, OFFSET_VEC[2]);
        splitFilter(lp120, length, 4, hp60, lp60);
        length >>= 1;
        features[1] = logOfEnergy(hp60, length, OFFSET_VEC[1]);
        highPassFilter(lp60, 0, length, hpState, hp120, 0);
        features[0] = logOfEnergy(hp120, length, OFFSET_VEC[0]);
        return totalEnergy;
    }

    // ------------------------------------------------------------------ vad_gmm
    private int deltaOut;

    private int gaussianProbability(int input, int mean, int std) {
        int tmp32 = 131072 + (std >> 1);
        int invStd = (short) (tmp32 / std);
        int tmp16 = invStd >> 2;
        int invStd2 = (short) ((tmp16 * tmp16) >> 2);
        tmp16 = (short) (input << 3);
        tmp16 = (short) (tmp16 - mean);
        deltaOut = (short) ((invStd2 * tmp16) >> 10);
        tmp32 = (deltaOut * tmp16) >> 9;
        int expValue = 0;
        if (tmp32 < COMP_VAR) {
            tmp16 = (short) ((LOG2_EXP * (int) (short) tmp32) >> 12);
            tmp16 = (short) -tmp16;
            expValue = (short) (0x0400 | (tmp16 & 0x03FF));
            tmp16 = (short) (tmp16 ^ 0xFFFF);
            tmp16 >>= 10;
            tmp16 += 1;
            expValue >>= tmp16;
        }
        return invStd * expValue;
    }

    // ------------------------------------------------------------------ vad_core
    private static int weightedAverage(short[] data, int base, int offset, int[] weights) {
        int avg = 0;
        for (int k = 0; k < NUM_GAUSS; k++) {
            int idx = base + k * NUM_CH;
            data[idx] = (short) (data[idx] + offset);
            avg += data[idx] * weights[idx];
        }
        return avg;
    }

    private int gmmProbability(short[] features, int totalPower, int frameLength) {
        int vadflag = 0;
        int[] deltaN = new int[TABLE], deltaS = new int[TABLE], ngprvec = new int[TABLE], sgprvec = new int[TABLE];
        int[] noiseProb = new int[NUM_GAUSS], speechProb = new int[NUM_GAUSS];
        int sumLlr = 0;
        int idx = frameLength == 80 ? 0 : (frameLength == 160 ? 1 : 2);
        int overhead1 = OVER1[mode][idx], overhead2 = OVER2[mode][idx], individualTest = LOCAL[mode][idx], totalTest = GLOBAL[mode][idx];

        if (totalPower > MIN_ENERGY) {
            for (int channel = 0; channel < NUM_CH; channel++) {
                int h0Test = 0, h1Test = 0;
                for (int k = 0; k < NUM_GAUSS; k++) {
                    int g = channel + k * NUM_CH;
                    int t = gaussianProbability(features[channel], noiseMeans[g], noiseStds[g]);
                    deltaN[g] = deltaOut;
                    noiseProb[k] = NOISE_W[g] * t;
                    h0Test += noiseProb[k];
                    t = gaussianProbability(features[channel], speechMeans[g], speechStds[g]);
                    deltaS[g] = deltaOut;
                    speechProb[k] = SPEECH_W[g] * t;
                    h1Test += speechProb[k];
                }
                int shiftsH0 = normW32(h0Test), shiftsH1 = normW32(h1Test);
                if (h0Test == 0) shiftsH0 = 31;
                if (h1Test == 0) shiftsH1 = 31;
                int llr = (short) (shiftsH0 - shiftsH1);
                sumLlr += llr * SPECTRUM_WEIGHT[channel];
                if ((llr << 2) > individualTest) vadflag = 1;
                int h0 = (short) (h0Test >> 12);
                if (h0 > 0) {
                    int t = (noiseProb[0] & 0xFFFFF000) << 2;
                    ngprvec[channel] = (short) (t / h0);
                    ngprvec[channel + NUM_CH] = 16384 - ngprvec[channel];
                } else {
                    ngprvec[channel] = 16384;
                }
                int h1 = (short) (h1Test >> 12);
                if (h1 > 0) {
                    int t = (speechProb[0] & 0xFFFFF000) << 2;
                    sgprvec[channel] = (short) (t / h1);
                    sgprvec[channel + NUM_CH] = 16384 - sgprvec[channel];
                }
            }
            if (sumLlr >= totalTest) vadflag = 1;

            int maxspe = 12800;
            for (int channel = 0; channel < NUM_CH; channel++) {
                int featureMinimum = findMinimum(features[channel], channel);
                int noiseGlobalMean = weightedAverage(noiseMeans, channel, 0, NOISE_W);
                int tmp1s16 = (short) (noiseGlobalMean >> 6);
                for (int k = 0; k < NUM_GAUSS; k++) {
                    int g = channel + k * NUM_CH;
                    int nmk = noiseMeans[g], smk = speechMeans[g], nsk = noiseStds[g], ssk = speechStds[g];
                    int nmk2 = nmk;
                    if (vadflag == 0) {
                        int delt = (short) ((ngprvec[g] * deltaN[g]) >> 11);
                        nmk2 = (short) (nmk + (short) ((delt * NOISE_UPDATE) >> 22));
                    }
                    int ndelt = (short) ((featureMinimum << 4) - tmp1s16);
                    int nmk3 = (short) (nmk2 + (short) ((ndelt * BACK_ETA) >> 9));
                    int tmps = (short) ((k + 5) << 7);
                    if (nmk3 < tmps) nmk3 = tmps;
                    tmps = (short) ((72 + k - channel) << 7);
                    if (nmk3 > tmps) nmk3 = tmps;
                    noiseMeans[g] = (short) nmk3;
                    if (vadflag != 0) {
                        int delt = (short) ((sgprvec[g] * deltaS[g]) >> 11);
                        int t16 = (short) ((delt * SPEECH_UPDATE) >> 21);
                        int smk2 = (short) (smk + ((t16 + 1) >> 1));
                        int maxmu = (short) (maxspe + 640);
                        if (smk2 < MIN_MEAN[k]) smk2 = MIN_MEAN[k];
                        if (smk2 > maxmu) smk2 = maxmu;
                        speechMeans[g] = (short) smk2;
                        t16 = (short) ((smk + 4) >> 3);
                        t16 = (short) (features[channel] - t16);
                        int t1 = (deltaS[g] * t16) >> 3;
                        int t2 = t1 - 4096;
                        t16 = sgprvec[g] >> 2;
                        t1 = t16 * t2;
                        t2 = t1 >> 4;
                        if (t2 > 0) {
                            t16 = (short) (t2 / (short) (ssk * 10));
                        } else {
                            t16 = (short) ((-t2) / (short) (ssk * 10));
                            t16 = (short) -t16;
                        }
                        t16 = (short) (t16 + 128);
                        ssk = (short) (ssk + (t16 >> 8));
                        if (ssk < MIN_STD) ssk = MIN_STD;
                        speechStds[g] = (short) ssk;
                    } else {
                        int t16 = (short) (features[channel] - (nmk >> 3));
                        int t1 = (deltaN[g] * t16) >> 3;
                        t1 -= 4096;
                        t16 = (short) ((ngprvec[g] + 2) >> 2);
                        int t2 = t16 * t1;
                        t1 = t2 >> 14;
                        if (t1 > 0) {
                            t16 = (short) (t1 / nsk);
                        } else {
                            t16 = (short) ((-t1) / nsk);
                            t16 = (short) -t16;
                        }
                        t16 = (short) (t16 + 32);
                        nsk = (short) (nsk + (t16 >> 6));
                        if (nsk < MIN_STD) nsk = MIN_STD;
                        noiseStds[g] = (short) nsk;
                    }
                }
                noiseGlobalMean = weightedAverage(noiseMeans, channel, 0, NOISE_W);
                int speechGlobalMean = weightedAverage(speechMeans, channel, 0, SPEECH_W);
                int diff = (short) ((short) (speechGlobalMean >> 9) - (short) (noiseGlobalMean >> 9));
                if (diff < MIN_DIFF[channel]) {
                    int tmps = (short) (MIN_DIFF[channel] - diff);
                    int t1 = (short) ((13 * tmps) >> 2);
                    int t2 = (short) ((3 * tmps) >> 2);
                    speechGlobalMean = weightedAverage(speechMeans, channel, t1, SPEECH_W);
                    noiseGlobalMean = weightedAverage(noiseMeans, channel, -t2, NOISE_W);
                }
                maxspe = MAX_SPEECH[channel];
                int tmp2 = (short) (speechGlobalMean >> 7);
                if (tmp2 > maxspe) {
                    tmp2 -= maxspe;
                    for (int k = 0; k < NUM_GAUSS; k++) speechMeans[channel + k * NUM_CH] = (short) (speechMeans[channel + k * NUM_CH] - tmp2);
                }
                tmp2 = (short) (noiseGlobalMean >> 7);
                if (tmp2 > MAX_NOISE[channel]) {
                    tmp2 -= MAX_NOISE[channel];
                    for (int k = 0; k < NUM_GAUSS; k++) noiseMeans[channel + k * NUM_CH] = (short) (noiseMeans[channel + k * NUM_CH] - tmp2);
                }
            }
            frameCounter++;
        }
        if (vadflag == 0) {
            if (overHang > 0) {
                vadflag = 2 + overHang;
                overHang--;
            }
            numOfSpeech = 0;
        } else {
            numOfSpeech++;
            if (numOfSpeech > MAX_SPEECH_FRAMES) {
                numOfSpeech = MAX_SPEECH_FRAMES;
                overHang = overhead2;
            } else {
                overHang = overhead1;
            }
        }
        return vadflag;
    }
}
