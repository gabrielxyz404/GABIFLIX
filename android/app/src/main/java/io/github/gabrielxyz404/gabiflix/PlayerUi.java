package io.github.gabrielxyz404.gabiflix;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.EnumMap;

/**
 * Peças visuais do player nativo (celular e TV): ícones vetoriais, botões, linha do tempo vermelha,
 * indicador de carregamento e o efeito do toque duplo. Só desenho: nada aqui toca em legendas ou no VLC.
 */
final class PlayerUi {
    private PlayerUi() {}

    /** vermelho FramePlay */
    static final int RED = 0xFFE50914;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Outfit (a fonte do app) no peso pedido; cai para a do sistema se algo falhar */
    static Typeface font(Context c, int weight) {
        try {
            Typeface t = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.outfit);
            if (t == null) return weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
            if (android.os.Build.VERSION.SDK_INT >= 28) return Typeface.create(t, weight, false);
            return Typeface.create(t, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        } catch (Exception e) { return weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT; }
    }

    static int lerpColor(int a, int b, float t) {
        int ar = (a >>> 24), rr = (a >> 16) & 255, gr = (a >> 8) & 255, br = a & 255;
        int ar2 = (b >>> 24), rr2 = (b >> 16) & 255, gr2 = (b >> 8) & 255, br2 = b & 255;
        return ((int) (ar + (ar2 - ar) * t) << 24) | ((int) (rr + (rr2 - rr) * t) << 16) | ((int) (gr + (gr2 - gr) * t) << 8) | (int) (br + (br2 - br) * t);
    }

    // ---------- ícones (caixa 24 x 24, estilo Material) ----------
    enum Icon { PLAY, PAUSE, BACK, REPLAY, FORWARD, SUBS, AUDIO, SCALE, SYNC }

    private static final EnumMap<Icon, Path> PATHS = new EnumMap<>(Icon.class);

    private static Path path(Icon i) {
        Path p = PATHS.get(i);
        if (p != null) return p;
        String d;
        switch (i) {
            case PLAY: d = "M8 5v14l11-7z"; break;
            case PAUSE: d = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"; break;
            case BACK: d = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"; break;
            case REPLAY: case FORWARD:
                d = "M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"; break;
            case AUDIO:
                d = "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"; break;
            case SCALE:
                d = "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"; break;
            case SYNC:
                d = "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"; break;
            default: return null; // SUBS é desenhado à mão
        }
        try { p = androidx.core.graphics.PathParser.createPathFromPathData(d); } catch (Throwable t) { p = null; }
        if (p == null) { p = new Path(); p.addCircle(12, 12, 8, Path.Direction.CW); }
        PATHS.put(i, p);
        return p;
    }

    private static final RectF TMP = new RectF();

    /** desenha o ícone centrado em (cx, cy) com o tamanho dado (px) */
    static void drawIcon(Canvas c, Icon ic, float cx, float cy, float size, int color, Paint p) {
        int sv = c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        float s = size / 24f;
        c.scale(s, s);
        p.setColor(color);
        p.setTypeface(null);
        p.setStyle(Paint.Style.FILL);
        switch (ic) {
            case REPLAY:
            case FORWARD: {
                if (ic == Icon.FORWARD) { c.save(); c.translate(24, 0); c.scale(-1, 1); c.drawPath(path(Icon.REPLAY), p); c.restore(); }
                else c.drawPath(path(Icon.REPLAY), p);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(7.4f);
                c.drawText("10", 12, 15.6f, p);
                break;
            }
            case SUBS: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(1.9f);
                TMP.set(3f, 5f, 21f, 19f);
                c.drawRoundRect(TMP, 2.6f, 2.6f, p);
                p.setStrokeWidth(1.7f);
                TMP.set(6.2f, 9.4f, 10.6f, 14.6f);
                c.drawArc(TMP, 40, 280, false, p);
                TMP.set(13.4f, 9.4f, 17.8f, 14.6f);
                c.drawArc(TMP, 40, 280, false, p);
                break;
            }
            default:
                c.drawPath(path(ic), p);
        }
        c.restoreToCount(sv);
    }

    // ---------- botão (redondo só com ícone, ou pílula com ícone + texto) ----------
    static final class Btn extends View {
        private Icon icon;
        private String label;
        private final int hPx;
        private final boolean tv, big;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ip = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float f;          // 0 = normal, 1 = selecionado (TV)
        private ValueAnimator fa;
        private final float strokePx;

        Btn(Context c, Icon icon, String label, int sizeDp, boolean tv) {
            super(c);
            this.icon = icon;
            this.label = label;
            this.hPx = dp(c, sizeDp);
            this.tv = tv;
            this.big = label == null && sizeDp >= 64;
            this.strokePx = dp(c, 1);
            tx.setTypeface(font(c, 600));
            tx.setTextSize(hPx * 0.36f);
            setClickable(true);
            setFocusable(tv);
            setContentDescription(label != null ? label : "");
        }

        void setIcon(Icon i) { if (i != icon) { icon = i; invalidate(); } }

        void setLabel(String s) {
            label = s;
            setContentDescription(s);
            requestLayout();
            invalidate();
        }

        private float iconPx() { return hPx * (label == null ? (big ? 0.5f : 0.56f) : 0.56f); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = hPx;
            if (label != null) w = (int) (hPx * 0.42f + iconPx() + hPx * 0.22f + tx.measureText(label) + hPx * 0.46f);
            setMeasuredDimension(w, hPx);
        }

        @Override
        protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            if (!tv) return;
            if (fa != null) fa.cancel();
            fa = ValueAnimator.ofFloat(f, gain ? 1f : 0f);
            fa.setDuration(130);
            fa.setInterpolator(new DecelerateInterpolator());
            fa.addUpdateListener(a -> {
                f = (float) a.getAnimatedValue();
                float s = 1f + 0.1f * f;
                setScaleX(s);
                setScaleY(s);
                invalidate();
            });
            fa.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            int base = isPressed() ? 0x73FFFFFF : 0x4D000000;
            bg.setStyle(Paint.Style.FILL);
            bg.setColor(lerpColor(base, 0xFFFFFFFF, f));
            c.drawRoundRect(0, 0, w, h, r, r, bg);
            if (f < 1f) {
                bg.setStyle(Paint.Style.STROKE);
                bg.setStrokeWidth(strokePx);
                bg.setColor(lerpColor(0x33FFFFFF, 0x00FFFFFF, f));
                c.drawRoundRect(strokePx / 2, strokePx / 2, w - strokePx / 2, h - strokePx / 2, r, r, bg);
            }
            int fg = lerpColor(0xFFFFFFFF, 0xFF000000, f);
            float is = iconPx();
            if (label == null) {
                drawIcon(c, icon, w / 2f + (icon == Icon.PLAY ? is * 0.04f : 0), h / 2f, is, fg, ip);
            } else {
                float x = h * 0.42f;
                drawIcon(c, icon, x + is / 2f, h / 2f, is, fg, ip);
                tx.setColor(fg);
                Paint.FontMetrics fm = tx.getFontMetrics();
                c.drawText(label, x + is + h * 0.22f, h / 2f - (fm.ascent + fm.descent) / 2f, tx);
            }
        }
    }

    // ---------- linha do tempo vermelha ----------
    static final class Timeline extends View {
        interface Listener {
            void onStart();
            /** o dedo mexeu: devolve o texto do balão (o tempo daquele ponto) */
            String onMove(float frac);
            /** soltou; frac < 0 se foi cancelado */
            void onStop(float frac);
        }

        Listener listener;
        private final boolean tv;
        private float progress, shown, buffered, amt;
        private boolean dragging, scrubbing, seekable;
        private String bubble = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator pa, aa;
        private final float padX, trackBottom;
        private final int d4, d3, d9, d26, d10;

        Timeline(Context c, boolean tv) {
            super(c);
            this.tv = tv;
            setFocusable(tv);
            setContentDescription("Linha do tempo");
            padX = dp(c, 10);
            trackBottom = dp(c, 22);
            d3 = dp(c, 3); d4 = dp(c, 4); d9 = dp(c, 9); d26 = dp(c, 26); d10 = dp(c, 10);
            tp.setTypeface(font(c, 600));
            tp.setTextSize(dp(c, 13.5f));
            tp.setTextAlign(Paint.Align.CENTER);
        }

        static int heightDp() { return 64; }

        void setSeekable(boolean s) { seekable = s; }

        void setBuffered(float f) { buffered = Math.max(0f, Math.min(1f, f)); invalidate(); }

        float getProgress() { return progress; }

        boolean isUserActive() { return dragging || scrubbing; }

        /** posição normal da reprodução (ignorada enquanto o usuário arrasta ou corre a barra) */
        void setProgress(float f) {
            f = Math.max(0f, Math.min(1f, f));
            progress = f;
            if (dragging || scrubbing) return;
            if (pa != null) pa.cancel();
            if (isShown() && Math.abs(f - shown) < 0.03f && Math.abs(f - shown) > 0.0002f) {
                pa = ValueAnimator.ofFloat(shown, f);
                pa.setDuration(300);
                pa.setInterpolator(new LinearInterpolator());
                pa.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
                pa.start();
            } else { shown = f; invalidate(); }
        }

        /** corrida com o controle remoto (TV): mostra o ponto e o balão até soltar */
        void setScrub(boolean on, float frac, String text) {
            scrubbing = on;
            if (on) { progress = Math.max(0f, Math.min(1f, frac)); shown = progress; bubble = text == null ? "" : text; if (pa != null) pa.cancel(); }
            emphasize();
            invalidate();
        }

        private void emphasize() {
            float target = (dragging || scrubbing || hasFocus()) ? 1f : 0f;
            if (aa != null) aa.cancel();
            aa = ValueAnimator.ofFloat(amt, target);
            aa.setDuration(140);
            aa.setInterpolator(new DecelerateInterpolator());
            aa.addUpdateListener(a -> { amt = (float) a.getAnimatedValue(); invalidate(); });
            aa.start();
        }

        @Override
        protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            emphasize();
        }

        private float fracOf(float x) {
            float x0 = padX, x1 = getWidth() - padX;
            return Math.max(0f, Math.min(1f, (x - x0) / Math.max(1f, x1 - x0)));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!seekable) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    progress = shown = fracOf(e.getX());
                    if (pa != null) pa.cancel();
                    if (listener != null) { listener.onStart(); bubble = listener.onMove(progress); }
                    emphasize();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    progress = shown = fracOf(e.getX());
                    if (listener != null) bubble = listener.onMove(progress);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    dragging = false;
                    if (listener != null) listener.onStop(e.getActionMasked() == MotionEvent.ACTION_UP ? progress : -1f);
                    emphasize();
                    invalidate();
                    return true;
            }
            return false;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(resolveSize(dp(getContext(), 200), wSpec), dp(getContext(), heightDp()));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float x0 = padX, x1 = w - padX, y = h - trackBottom;
            float th = d4 + d3 * amt;
            float rad = th / 2f;
            // trilho escuro
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x47FFFFFF);
            c.drawRoundRect(x0, y - rad, x1, y + rad, rad, rad, p);
            float cur = (dragging || scrubbing) ? progress : shown;
            // parte já carregada (cinza claro)
            if (buffered > cur) {
                p.setColor(0x85FFFFFF);
                c.drawRoundRect(x0, y - rad, x0 + (x1 - x0) * buffered, y + rad, rad, rad, p);
            }
            float px = x0 + (x1 - x0) * cur;
            // progresso vermelho
            p.setColor(RED);
            if (px - x0 > 0.5f) c.drawRoundRect(x0, y - rad, Math.max(px, x0 + th), y + rad, rad, rad, p);
            // bolinha
            float tr = (tv ? d3 : d4) + ((d9) - (tv ? d3 : d4)) * amt;
            if (amt > 0.01f) {
                p.setColor((int) (0x4D * amt) << 24 | 0xE50914);
                c.drawCircle(px, y, tr + d4 * amt + d3, p);
            }
            p.setColor(RED);
            c.drawCircle(px, y, tr, p);
            // balão com o tempo
            if (amt > 0.01f && (dragging || scrubbing) && !bubble.isEmpty()) {
                float tw = tp.measureText(bubble);
                float bw = tw + dp(getContext(), 22), bh = dp(getContext(), 24);
                float bx = Math.max(bw / 2f, Math.min(w - bw / 2f, px));
                float bb = y - tr - dp(getContext(), 6);
                int a = (int) (255 * amt);
                p.setColor(a << 24 | 0x1C1C1E);
                c.drawRoundRect(bx - bw / 2f, bb - bh, bx + bw / 2f, bb, bh / 2f, bh / 2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(getContext(), 1));
                p.setColor((int) (0x40 * amt) << 24 | 0xFFFFFF);
                c.drawRoundRect(bx - bw / 2f, bb - bh, bx + bw / 2f, bb, bh / 2f, bh / 2f, p);
                p.setStyle(Paint.Style.FILL);
                tp.setColor(a << 24 | 0xFFFFFF);
                Paint.FontMetrics fm = tp.getFontMetrics();
                c.drawText(bubble, bx, bb - bh / 2f - (fm.ascent + fm.descent) / 2f, tp);
            }
        }
    }

    // ---------- indicador de carregamento (arco vermelho) ----------
    static final class Spinner extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator a;
        private float t;

        Spinner(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(c, 4));
        }

        private void run() {
            if (a != null || !isShown()) return;
            a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(1500);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        private void halt() { if (a != null) { a.cancel(); a = null; } }

        @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); if (vis == VISIBLE) run(); else halt(); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); run(); }
        @Override protected void onDetachedFromWindow() { halt(); super.onDetachedFromWindow(); }

        @Override
        protected void onDraw(Canvas c) {
            float s = p.getStrokeWidth() / 2f + 1;
            r.set(s, s, getWidth() - s, getHeight() - s);
            p.setColor(0x26FFFFFF);
            c.drawArc(r, 0, 360, false, p);
            p.setColor(RED);
            float sweep = 40f + 230f * (0.5f - 0.5f * (float) Math.cos(t * 2 * Math.PI));
            c.drawArc(r, -90f + t * 720f, sweep, false, p);
        }
    }

    // ---------- efeito do toque duplo (celular): círculo que se abre com o ícone de ±10 s ----------
    static final class TapFx extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator a;
        private float t = 1f, x, y;
        private boolean right;

        TapFx(Context c) { super(c); setClickable(false); setFocusable(false); }

        void play(float px, float py, boolean fwd) {
            x = px; y = py; right = fwd;
            if (a != null) a.cancel();
            a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(520);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (t >= 1f) return;
            float alpha = t < 0.2f ? t / 0.2f : 1f - (t - 0.2f) / 0.8f;
            float maxR = dp(getContext(), 130);
            float rad = dp(getContext(), 36) + (maxR - dp(getContext(), 36)) * t;
            p.setStyle(Paint.Style.FILL);
            p.setColor((int) (0x40 * alpha) << 24 | 0xFFFFFF);
            c.drawCircle(x, y, rad, p);
            float is = dp(getContext(), 44);
            drawIcon(c, right ? Icon.FORWARD : Icon.REPLAY, x, y, is, (int) (255 * alpha) << 24 | 0xFFFFFF, ip);
        }
    }
}
