package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    final List<JSONObject> items = new ArrayList<>();
    boolean numbered, progress;
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
        p.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < items.size()) open((Activity) c, items.get(pos));
        });
        p.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (longPress == null || pos < 0 || pos >= items.size()) return false;
            longPress.on(items.get(pos), pos);
            return true;
        });
        p.setOnKeyListener((v, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_MENU && ev.getAction() == android.view.KeyEvent.ACTION_UP) return v.performLongClick();
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull H holder, int position) {
        JSONObject m = items.get(position);
        Ui.bindPoster(holder.poster, m);
        if (numbered) { holder.num.setVisibility(View.VISIBLE); holder.num.setText(String.valueOf(position + 1)); }
        else holder.num.setVisibility(View.GONE);
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

    @Override public int getItemCount() { return items.size(); }

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

    // ---------- listas que rolam sem animação (mais rápido no Fire TV) ----------

    static final class Linear extends LinearLayoutManager {
        Linear(Context c, int orientation) { super(c, orientation, false); setInitialPrefetchItemCount(8); }
        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            return super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible);
        }
    }

    static final class Grid extends GridLayoutManager {
        Grid(Context c, int span) { super(c, span); }
        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            return super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible);
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
