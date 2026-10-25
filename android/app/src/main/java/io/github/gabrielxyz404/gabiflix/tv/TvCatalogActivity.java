package io.github.gabrielxyz404.gabiflix.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Catálogo de um serviço: abas em cima e fileiras de pôsteres (ou uma grade). */
public class TvCatalogActivity extends TvBase {

    private Services.Service service;
    private int color;
    private final List<TextView> tabViews = new ArrayList<>();
    private int tab = 0;
    private FrameLayout content;
    private RecyclerView.RecycledViewPool pool = new RecycledViewPool();
    private RowsAdapter rows;
    private Row contRow;
    private int generation = 0;

    static final class RecycledViewPool extends RecyclerView.RecycledViewPool {
        RecycledViewPool() { setMaxRecycledViews(0, 40); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        service = Services.find(getIntent().getStringExtra("service"));
        color = Ui.parseColor(service.color, 0xFFFFFFFF);
        getWindow().getDecorView().setBackgroundColor(Ui.tint(color, 0.07f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 6));
        TextView name = Ui.text(this, service.name, 24, true, color);
        header.addView(name);
        header.addView(Ui.space(this, 24, 1));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFocusable(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < service.tabs.length; i++) {
            final int idx = i;
            TextView t = Ui.button(this, service.tabs[i], 0x00000000, Ui.TEXT);
            t.setTextSize(15);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
            t.setOnClickListener(v -> selectTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            tabs.addView(t, lp);
            tabViews.add(t);
        }
        hs.addView(tabs);
        header.addView(hs);
        root.addView(header);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        selectTab(0);
        focusLater(tabViews.get(0));
    }

    private void paintTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == tab;
            int bg = on ? Ui.tint(color, 0.55f) : 0x00000000;
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.GradientDrawable f = Ui.round(on ? bg : 0xFF2A2A2E, Ui.dp(this, 20));
            f.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
            s.addState(new int[]{android.R.attr.state_focused}, f);
            s.addState(new int[]{}, Ui.round(bg, Ui.dp(this, 20)));
            t.setBackground(s);
            t.setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
        }
    }

    private void selectTab(int idx) {
        tab = idx;
        paintTabs();
        generation++;
        content.removeAllViews();
        contRow = null;
        String name = service.tabs[idx];
        List<Services.Section> secs = Services.sections(service, name);
        if (secs.size() == 1 && secs.get(0).grid) showGrid(secs.get(0));
        else showRows(secs);
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        if (f != null && !tabViews.contains(f)) { tabViews.get(tab).requestFocus(); return; }
        if (tab != 0) { selectTab(0); tabViews.get(0).requestFocus(); return; }
        super.onBackPressed();
    }

    // ---------- grade (abas Filmes / Séries) ----------

    private void showGrid(Services.Section sec) {
        final int gen = generation;
        RecyclerView grid = PosterAdapter.list(this);
        int pad = 22;
        grid.setPadding(Ui.dp(this, pad), Ui.dp(this, 6), Ui.dp(this, pad), Ui.dp(this, 30));
        grid.setLayoutManager(new PosterAdapter.Grid(this, PosterAdapter.span(this, Ui.POSTER_W, pad)));
        PosterAdapter a = new PosterAdapter(Ui.POSTER_W, Ui.POSTER_H);
        grid.setAdapter(a);
        content.addView(grid);
        TextView loading = loading();
        content.addView(loading);
        Net.async(() -> load(sec), (list, err) -> {
            if (gen != generation) return;
            content.removeView(loading);
            if (list == null || list.isEmpty()) { content.addView(message(err != null ? "Não foi possível carregar. Verifique a internet." : "Nada por aqui.")); return; }
            a.set(list);
        });
    }

    // ---------- fileiras ----------

    static final class Row {
        String title;
        List<JSONObject> metas;
        boolean numbered, cont, loaded;
    }

    private void showRows(List<Services.Section> secs) {
        final int gen = generation;
        RecyclerView list = PosterAdapter.list(this);
        list.setPadding(0, 0, 0, Ui.dp(this, 40));
        list.setLayoutManager(new PosterAdapter.Linear(this, RecyclerView.VERTICAL));
        list.setItemViewCacheSize(4);
        rows = new RowsAdapter();
        list.setAdapter(rows);
        content.addView(list);

        for (Services.Section sec : secs) {
            Row r = new Row();
            r.title = sec.title;
            r.numbered = sec.numbered || sec.top10;
            r.cont = sec.cont;
            rows.slots.add(r);
            if (sec.cont) { contRow = r; loadContinue(r, gen); continue; }
            if (sec.genres) {
                r.loaded = true; // o próprio espaço fica vazio; os gêneros entram logo a seguir
                Net.async(() -> Stremio.data(sec.home + "-home"), (home, err) -> {
                    if (gen != generation || home == null) return;
                    JSONArray gs = home.optJSONArray("genres");
                    if (gs == null) return;
                    int at = rows.slots.indexOf(r) + 1;
                    for (int i = 0; i < gs.length() && i < 6; i++) {
                        JSONObject g = gs.optJSONObject(i);
                        if (g == null) continue;
                        Row gr = new Row();
                        gr.title = g.optString("title");
                        gr.metas = cap(Stremio.metas(g.optJSONArray("metas")), 15);
                        gr.loaded = true;
                        rows.slots.add(at++, gr);
                    }
                    rows.sync();
                });
                continue;
            }
            Net.async(() -> load(sec), (metas, err) -> {
                if (gen != generation) return;
                r.metas = metas == null ? null : cap(metas, r.numbered ? (sec.top10 ? 10 : 30) : 15);
                r.loaded = true;
                rows.sync();
            });
        }
    }

    private static List<JSONObject> cap(List<JSONObject> l, int n) {
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    private static List<JSONObject> load(Services.Section sec) throws Exception {
        if (sec.file != null) return Stremio.metas(Stremio.data(sec.file).optJSONArray("metas"));
        JSONObject home = Stremio.data(sec.home + "-home");
        return Stremio.metas(home.optJSONArray(sec.key));
    }

    // ---------- Continuar assistindo (só os títulos deste serviço) ----------

    private void loadContinue(Row r, int gen) {
        if (!Stremio.loggedIn(this)) { r.loaded = true; r.metas = null; if (rows != null) rows.sync(); return; }
        Context c = getApplicationContext();
        Net.async(() -> {
            List<JSONObject> all = Stremio.continueWatching(c);
            List<JSONObject> out = new ArrayList<>();
            if (all.isEmpty()) return out;
            if (service.id.equals("cinemateca")) {
                Set<String> any = new HashSet<>();
                for (String s : Services.SERVICE_IDS) any.addAll(Stremio.ids(s));
                for (JSONObject it : all) if (!any.contains(it.optString("_id"))) out.add(it);
            } else {
                Set<String> mine = Stremio.ids(service.id);
                if (service.id.equals("disney")) for (String f : new String[]{"disney-disney", "disney-pixar", "disney-marvel", "disney-starwars", "disney-natgeo"}) {
                    try { for (JSONObject m : Stremio.metas(Stremio.data(f).optJSONArray("metas"))) mine.add(m.optString("id")); } catch (Exception ignored) {}
                }
                for (JSONObject it : all) if (mine.contains(it.optString("_id"))) out.add(it);
            }
            return out;
        }, (list, err) -> {
            if (gen != generation) return;
            r.metas = list;
            r.loaded = true;
            rows.sync();
            rows.changed(r);
        });
    }

    @Override
    protected void onProgressSaved() {
        if (contRow != null) loadContinue(contRow, generation);
    }

    private boolean resumedOnce;

    @Override
    protected void onResume() {
        super.onResume();
        // ao voltar de um título (pode ter mudado o Continuar assistindo)
        if (resumedOnce && contRow != null) loadContinue(contRow, generation);
        resumedOnce = true;
    }

    private void removeDialog(JSONObject item, Row r) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item.optString("name"))
                .setItems(new String[]{"Remover do Continuar assistindo"}, (d, w) -> {
                    r.metas.remove(item);
                    rows.sync();
                    rows.changed(r);
                    Context c = getApplicationContext();
                    Net.async(() -> { Stremio.rewind(c, item.optString("_id")); return true; }, (ok, e) -> {});
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- adaptador das fileiras ----------

    private final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.RH> {
        final List<Row> slots = new ArrayList<>();
        final List<Row> shown = new ArrayList<>();

        /** mostra só as fileiras já carregadas e com títulos, sem perder o item selecionado */
        void sync() {
            List<Row> next = new ArrayList<>();
            for (Row r : slots) if (r.loaded && r.metas != null && !r.metas.isEmpty()) next.add(r);
            for (int i = shown.size() - 1; i >= 0; i--) if (!next.contains(shown.get(i))) { shown.remove(i); notifyItemRemoved(i); }
            for (int i = 0; i < next.size(); i++) if (i >= shown.size() || shown.get(i) != next.get(i)) {
                if (!shown.contains(next.get(i))) { shown.add(i, next.get(i)); notifyItemInserted(i); }
            }
        }

        void changed(Row r) {
            int i = shown.indexOf(r);
            if (i >= 0) notifyItemChanged(i);
        }

        final class RH extends RecyclerView.ViewHolder {
            final TextView title;
            final RecyclerView list;
            final PosterAdapter adapter;
            RH(LinearLayout v, TextView t, RecyclerView l, PosterAdapter a) { super(v); title = t; list = l; adapter = a; }
        }

        @NonNull @Override
        public RH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = Ui.text(c, "", 17, true, Ui.TEXT);
            t.setPadding(Ui.dp(c, 30), Ui.dp(c, 12), Ui.dp(c, 30), Ui.dp(c, 2));
            v.addView(t);
            RecyclerView l = PosterAdapter.list(c);
            l.setPadding(Ui.dp(c, 21), 0, Ui.dp(c, 21), 0);
            l.setLayoutManager(new PosterAdapter.Linear(c, RecyclerView.HORIZONTAL));
            l.setRecycledViewPool(pool);
            PosterAdapter a = new PosterAdapter(Ui.POSTER_W, Ui.POSTER_H);
            l.setAdapter(a);
            v.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, Ui.POSTER_H + 14)));
            return new RH(v, t, l, a);
        }

        @Override
        public void onBindViewHolder(@NonNull RH h, int position) {
            Row r = shown.get(position);
            h.title.setText(r.title);
            h.adapter.numbered = r.numbered;
            h.adapter.progress = r.cont;
            h.adapter.longPress = r.cont ? (item, pos) -> removeDialog(item, r) : null;
            h.adapter.set(r.metas);
            h.list.scrollToPosition(0);
        }

        @Override public int getItemCount() { return shown.size(); }
    }

    // ---------- textos de estado ----------

    private TextView loading() { return message("Carregando…"); }

    private TextView message(String s) {
        TextView t = Ui.text(this, s, 18, false, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }
}
