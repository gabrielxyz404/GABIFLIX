package io.github.gabrielxyz404.gabiflix.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lista de pôsteres (fileira ou grade). Clique abre o título; segurar OK num "Continuar" mostra Remover. */
class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.H> {

    interface LongPress { void on(JSONObject item, int position); }
    /** o foco entrou (has = true) ou saiu de um título */
    interface FocusItem { void on(JSONObject item, int position, boolean has); }

    /** tamanho (dp) do título selecionado quando abre em banner 16:9: maior que os pôsteres vizinhos */
    static final int BANNER_W = 340, BANNER_H = 191;

    final List<JSONObject> items = new ArrayList<>();
    boolean numbered, progress;
    boolean expandable;      // fileiras: o título selecionado abre em banner
    FocusItem onFocusItem;
    LongPress longPress;
    private final int w, h;

    PosterAdapter(int wDp, int hDp) { w = wDp; h = hDp; setHasStableIds(false); }

    void set(List<JSONObject> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    static final class H extends RecyclerView.ViewHolder {
        final FrameLayout poster;
        final TextView num;
        final View bar, fill;
        FrameLayout banner;
        ImageView bannerImg, logoImg;
        TextView bannerTitle, name;
        ImageView img;
        ValueAnimator anim;
        boolean expanded;
        H(FrameLayout p, TextView n, View b, View f) { super(p); poster = p; num = n; bar = b; fill = f; }
    }

    @NonNull @Override
    public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        FrameLayout p = Ui.poster(c, w, h);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, w), Ui.dp(c, h));
        int m = Ui.dp(c, 7);
        lp.setMargins(m, m, m, m);
        p.setLayoutParams(lp);
        TextView num = Ui.text(c, "", 15, true, Ui.TEXT);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.round(0xE6000000, Ui.dp(c, 6)));
        num.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        nl.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
        p.addView(num, nl);
        View bar = new View(c);
        bar.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 5), Gravity.BOTTOM);
        bl.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 8));
        p.addView(bar, bl);
        View fill = new View(c);
        fill.setBackgroundColor(0xFFE50914);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(0, Ui.dp(c, 5), Gravity.BOTTOM | Gravity.START);
        fl.setMargins(Ui.dp(c, 8), 0, 0, Ui.dp(c, 8));
        p.addView(fill, fl);
        H holder = new H(p, num, bar, fill);
        holder.name = (TextView) p.getChildAt(0);
        holder.img = (ImageView) p.getChildAt(1);
        // banner (só nas fileiras): imagem horizontal com o logo ou o nome do título embaixo
        FrameLayout banner = new FrameLayout(c);
        banner.setVisibility(View.GONE);
        ImageView bimg = new ImageView(c);
        bimg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(bimg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x00000000}));
        banner.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 90), Gravity.BOTTOM));
        TextView bt = Ui.text(c, "", 15, false, Ui.TEXT);
        TvHomeActivity.outfit(bt, 600);
        bt.setMaxLines(2);
        bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.dp(c, BANNER_W - 24), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(Ui.dp(c, 12), 0, 0, Ui.dp(c, 12));
        banner.addView(bt, tl);
        ImageView logo = new ImageView(c);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(Ui.dp(c, 150), Ui.dp(c, 46), Gravity.BOTTOM | Gravity.START);
        ll.setMargins(Ui.dp(c, 12), 0, 0, Ui.dp(c, 12));
        banner.addView(logo, ll);
        p.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder.banner = banner; holder.bannerImg = bimg; holder.logoImg = logo; holder.bannerTitle = bt;
        p.setOnFocusChangeListener((x, has) -> {
            float sc = has ? (expandable ? 1.04f : 1.07f) : 1f;
            x.animate().scaleX(sc).scaleY(sc).setDuration(110).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size()) return;
            if (expandable) setExpanded(holder, items.get(pos), has);
            if (onFocusItem != null) onFocusItem.on(items.get(pos), pos, has);
        });
        p.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < items.size()) open((Activity) c, items.get(pos));
        });
        // segurar OK (meio segundo) (ou o botão ☰) num "Continuar assistindo": opção de remover
        p.setLongClickable(false);
        final boolean[] held = {false};
        final Runnable[] timer = {null};
        p.setOnKeyListener((v, code, ev) -> {
            int pos = holder.getBindingAdapterPosition();
            boolean can = longPress != null && pos >= 0 && pos < items.size();
            if (code == android.view.KeyEvent.KEYCODE_MENU) {
                if (can && ev.getAction() == android.view.KeyEvent.ACTION_UP) longPress.on(items.get(pos), pos);
                return can;
            }
            if (code != android.view.KeyEvent.KEYCODE_DPAD_CENTER && code != android.view.KeyEvent.KEYCODE_ENTER) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                // alguns controles avisam sozinhos que o botão ficou apertado
                if (can && ev.isLongPress() && !held[0]) {
                    held[0] = true;
                    if (timer[0] != null) { v.removeCallbacks(timer[0]); timer[0] = null; }
                    v.setPressed(false);
                    longPress.on(items.get(pos), pos);
                    return true;
                }
                if (ev.getRepeatCount() > 0) {
                    // a primeira repetição do controle chega ~0,4 s depois de apertar: já conta como "segurar"
                    if (can && !held[0]) {
                        held[0] = true;
                        if (timer[0] != null) { v.removeCallbacks(timer[0]); timer[0] = null; }
                        v.setPressed(false);
                        longPress.on(items.get(pos), pos);
                    }
                    return true;
                }
                held[0] = false;
                if (can) {
                    // relógio próprio: exatamente 2 s depois de apertar, se ainda estiver apertado
                    timer[0] = () -> {
                        int p2 = holder.getBindingAdapterPosition();
                        if (longPress == null || p2 < 0 || p2 >= items.size()) return;
                        held[0] = true;
                        v.setPressed(false);
                        longPress.on(items.get(p2), p2);
                    };
                    v.postDelayed(timer[0], 450);
                }
                return false;
            }
            if (timer[0] != null) { v.removeCallbacks(timer[0]); timer[0] = null; }
            if (held[0]) { held[0] = false; v.setPressed(false); return true; } // não abre o título
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull H holder, int position) {
        JSONObject m = items.get(position);
        resetBanner(holder);
        float sc = holder.poster.hasFocus() ? (expandable ? 1.04f : 1.07f) : 1f; // reaproveitado: tira o realce que ficou
        holder.poster.setScaleX(sc);
        holder.poster.setScaleY(sc);
        Ui.bindPoster(holder.poster, m);
        if (numbered) { holder.num.setVisibility(View.VISIBLE); holder.num.setText(String.valueOf(position + 1)); }
        else holder.num.setVisibility(View.GONE);
        bindBar(holder, m);
        if (expandable && holder.poster.hasFocus()) setExpanded(holder, m, true);
    }

    @Override public int getItemCount() { return items.size(); }

    // ---------- título selecionado abre em banner (estilo Netflix) ----------

    /** volta o título ao formato de pôster, sem animação */
    private void resetBanner(H h) {
        if (h.anim != null) { h.anim.cancel(); h.anim = null; }
        h.expanded = false;
        ViewGroup.LayoutParams lp = h.poster.getLayoutParams();
        int base = Ui.dp(h.poster.getContext(), w), baseH = Ui.dp(h.poster.getContext(), this.h);
        if (lp.width != base || lp.height != baseH) { lp.width = base; lp.height = baseH; h.poster.setLayoutParams(lp); }
        h.banner.setVisibility(View.GONE);
        h.img.setVisibility(View.VISIBLE);
        h.name.setVisibility(View.VISIBLE);
    }

    private void setExpanded(H h, JSONObject m, boolean on) {
        if (h.expanded == on) return;
        if (!on) {
            resetBanner(h);
            if (numbered) h.num.setVisibility(View.VISIBLE);
            bindBar(h, m);
            return;
        }
        h.expanded = true;
        Context c = h.poster.getContext();
        h.img.setVisibility(View.INVISIBLE);
        h.name.setVisibility(View.INVISIBLE);
        h.num.setVisibility(View.GONE);
        h.bar.setVisibility(View.GONE);
        h.fill.setVisibility(View.GONE);
        h.banner.setVisibility(View.VISIBLE);
        String id = m.optString("id");
        boolean imdb = id.startsWith("tt");
        String bg = m.optString("background", "");
        if (bg.isEmpty() && imdb) bg = "https://images.metahub.space/background/medium/" + id + "/img";
        String lg = m.optString("logo", "");
        if (lg.isEmpty() && imdb) lg = "https://images.metahub.space/logo/medium/" + id + "/img";
        h.bannerTitle.setText(m.optString("name"));
        Ui.image(h.bannerImg, bg);
        Ui.logo(h.logoImg, h.bannerTitle, lg);
        // abre devagar o bastante para ver, rápido o bastante para não pesar na TV
        final ViewGroup.LayoutParams lp = h.poster.getLayoutParams();
        final int w0 = lp.width, h0 = lp.height, w1 = Ui.dp(c, BANNER_W), h1 = Ui.dp(c, BANNER_H);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(130);
        a.addUpdateListener(v -> {
            float t = (Float) v.getAnimatedValue();
            lp.width = w0 + Math.round((w1 - w0) * t);
            lp.height = h0 + Math.round((h1 - h0) * t);
            h.poster.setLayoutParams(lp);
        });
        h.anim = a;
        a.start();
    }

    private void bindBar(H holder, JSONObject m) {
        JSONObject st = progress ? m.optJSONObject("state") : null;
        long off = st != null ? st.optLong("timeOffset") : 0, dur = st != null ? st.optLong("duration") : 0;
        if (off > 0 && dur > 0) {
            holder.bar.setVisibility(View.VISIBLE);
            holder.fill.setVisibility(View.VISIBLE);
            int full = Ui.dp(holder.poster.getContext(), w - 16);
            ViewGroup.LayoutParams lp = holder.fill.getLayoutParams();
            lp.width = Math.max(Ui.dp(holder.poster.getContext(), 4), (int) (full * Math.min(1.0, off / (double) dur)));
            holder.fill.setLayoutParams(lp);
        } else {
            holder.bar.setVisibility(View.GONE);
            holder.fill.setVisibility(View.GONE);
        }
    }

    /** abre a página do título; um item do Continuar assistindo leva o episódio onde parou */
    static void open(Activity a, JSONObject m) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("id", m.has("_id") ? m.optString("_id") : m.optString("id"));
            meta.put("type", m.optString("type"));
            meta.put("name", m.optString("name"));
            meta.put("poster", m.optString("poster", ""));
            if (m.has("background")) meta.put("background", m.optString("background"));
            Intent i = new Intent(a, TvDetailActivity.class);
            i.putExtra("meta", meta.toString());
            a.startActivity(i);
        } catch (Exception ignored) {}
    }

    // ---------- listas: rolagem suave ao mudar o foco (leve no Fire TV) ----------

    static final class Linear extends LinearLayoutManager {
        boolean smooth;      // o foco arrasta a lista suavemente em vez de saltar
        boolean suppress;    // a tela está a rolar por conta própria: ignora o pedido do foco
        int expandPx;        // fileira com banner: o título selecionado vai ficar desta largura, então a vista já o conta inteiro

        Linear(Context c, int orientation) { super(c, orientation, false); setInitialPrefetchItemCount(8); }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            if (suppress) return false;
            if (expandPx > 0 && getOrientation() == HORIZONTAL && rect.right - rect.left < expandPx) {
                rect = new Rect(rect.left, rect.top, rect.left + expandPx, rect.bottom);
            }
            return super.requestChildRectangleOnScreen(parent, child, rect, !smooth, focusedChildVisible);
        }

        /** leva a posição para o topo da lista, deslizando */
        void glideToTop(RecyclerView rv, int position) {
            androidx.recyclerview.widget.LinearSmoothScroller s = new androidx.recyclerview.widget.LinearSmoothScroller(rv.getContext()) {
                @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
                @Override protected int getHorizontalSnapPreference() { return SNAP_TO_START; }
                @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) { return 42f / dm.densityDpi; }
            };
            s.setTargetPosition(position);
            startSmoothScroll(s);
        }
    }

    static final class Grid extends GridLayoutManager {
        Grid(Context c, int span) { super(c, span); }
        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            return super.requestChildRectangleOnScreen(parent, child, rect, false, focusedChildVisible);
        }
    }

    static RecyclerView list(Context c) {
        RecyclerView r = new RecyclerView(c);
        r.setItemAnimator(null);
        r.setHasFixedSize(true);
        r.setClipToPadding(false);
        r.setOverScrollMode(View.OVER_SCROLL_NEVER);
        r.setFocusable(false);
        r.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        return r;
    }

    /** quantas colunas cabem na largura da tela */
    static int span(Context c, int wDp, int sidePaddingDp) {
        float widthDp = c.getResources().getDisplayMetrics().widthPixels / c.getResources().getDisplayMetrics().density;
        return Math.max(3, (int) ((widthDp - sidePaddingDp * 2) / (wDp + 14)));
    }
}
