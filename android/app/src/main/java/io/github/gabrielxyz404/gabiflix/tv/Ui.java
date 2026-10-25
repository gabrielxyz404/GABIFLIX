package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.DecodeFormat;

import org.json.JSONObject;

/** Visual da TV: simples e leve. O item selecionado ganha só uma borda branca. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF000000;
    static final int CARD = 0xFF17171A;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xB3FFFFFF;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** borda branca só quando selecionado (desenhada por cima do conteúdo) */
    static Drawable focusRing(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.TRANSPARENT);
        on.setCornerRadius(dp(c, radiusDp));
        on.setStroke(dp(c, 3), Color.WHITE);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new GradientDrawable());
        return s;
    }

    /** botão de texto com fundo sólido (fica mais claro e com borda branca quando selecionado) */
    static TextView button(Context c, String label, int bg, int fg) {
        TextView b = text(c, label, 17, true, fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = round(bg, dp(c, 10));
        on.setStroke(dp(c, 3), Color.WHITE);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, round(bg, dp(c, 10)));
        b.setBackground(s);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    static final int POSTER_W = 106, POSTER_H = 159;

    /** pôster: imagem com cantos arredondados (feita uma vez e guardada) e nome por baixo se não houver imagem */
    static FrameLayout poster(Context c, int wDp, int hDp) {
        FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, wDp), dp(c, hDp)));
        f.setBackground(round(CARD, dp(c, 8)));
        f.setFocusable(true);
        f.setClickable(true);
        f.setForeground(focusRing(c, 8));
        f.setClipToOutline(true); // cantos arredondados pela placa de vídeo (sem recortar cada imagem)
        TextView name = text(c, "", 13, true, MUTED);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        f.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    static void bindPoster(FrameLayout f, JSONObject meta) {
        TextView name = (TextView) f.getChildAt(0);
        ImageView img = (ImageView) f.getChildAt(1);
        name.setText(meta.optString("name"));
        String url = meta.optString("poster", "");
        if (url.isEmpty()) url = "https://images.metahub.space/poster/medium/" + meta.optString("id") + "/img";
        Context c = f.getContext();
        Glide.with(c).load(url).diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565).dontAnimate().centerCrop()
                .into(img);
    }

    static void image(ImageView v, String url) {
        if (url == null || url.isEmpty()) { v.setImageDrawable(null); return; }
        Glide.with(v.getContext()).load(url).diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565).dontAnimate().into(v);
    }

    static int parseColor(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    /** mistura a cor do serviço com o fundo escuro (para as barras e o fundo do catálogo) */
    static int tint(int color, float amount) {
        int r = Math.round(Color.red(color) * amount + 11 * (1 - amount));
        int g = Math.round(Color.green(color) * amount + 11 * (1 - amount));
        int b = Math.round(Color.blue(color) * amount + 13 * (1 - amount));
        return Color.rgb(r, g, b);
    }

    static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }
}
