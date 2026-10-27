package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private RecyclerView outer, gridView;
    private int pending;         // seções que ainda não responderam
    private View heroLoadingView;

    static final class RecycledViewPool extends RecyclerView.RecycledViewPool {
        RecycledViewPool() { setMaxRecycledViews(0, 40); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        service = Services.find(getIntent().getStringExtra("service"));
        color = Ui.parseColor(service.color, 0xFFFFFFFF);
        // fundo com a cor do serviço mais forte em cima, a escurecer para baixo
        getWindow().getDecorView().setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.tint(color, 0.34f), Ui.tint(color, 0.16f), Ui.tint(color, 0.07f)}));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 6));
        TextView name = Ui.text(this, service.name, 25, false, color);
        TvHomeActivity.outfit(name, 600);
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
            TvHomeActivity.outfit(t, 500);
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
        // troca de aba com um esmaecer rápido
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(150).start();
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        if (f != null && !tabViews.contains(f)) { tabViews.get(tab).requestFocus(); return; }
        if (tab != 0) { selectTab(0); tabViews.get(0).requestFocus(); return; }
        super.onBackPressed();
    }

    // ---------- controle: cima/baixo vai fileira a fileira, sem procurar pela tela ----------

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && outer != null
                && (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || k == android.view.KeyEvent.KEYCODE_DPAD_LEFT)) {
            View f = getCurrentFocus();
            if (f != null && !tabViews.contains(f) && edgeOfRow(f, k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && (k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
            View f = getCurrentFocus();
            boolean up = k == android.view.KeyEvent.KEYCODE_DPAD_UP;
            if (f != null && outer != null && !tabViews.contains(f) && moveRow(f, up)) return true;
            if (f != null && gridView != null && up && !tabViews.contains(f)) {
                View item = gridView.findContainingItemView(f);
                int pos = item != null ? gridView.getChildAdapterPosition(item) : -1;
                int span = ((PosterAdapter.Grid) gridView.getLayoutManager()).getSpanCount();
                if (pos >= 0 && pos < span) { tabViews.get(tab).requestFocus(); return true; }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** fim da fileira: à direita volta ao primeiro da mesma fileira; à esquerda do primeiro fica onde está */
    private boolean edgeOfRow(View f, boolean right) {
        View rowView = outer.findContainingItemView(f);
        if (rowView == null) return false;
        if (!(outer.getChildViewHolder(rowView) instanceof RowsAdapter.RH)) return false; // destaque: botões normais
        RowsAdapter.RH h = (RowsAdapter.RH) outer.getChildViewHolder(rowView);
        View cell = h.list.findContainingItemView(f);
        if (cell == null) return false;
        int pos = h.list.getChildAdapterPosition(cell), n = h.adapter.getItemCount();
        if (right && pos == n - 1) {
            // a própria fileira segura o foco enquanto volta ao início (senão ele salta para as abas)
            h.list.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            h.list.setFocusable(true);
            h.list.requestFocus();
            h.list.scrollToPosition(0);
            h.list.post(() -> {
                h.list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                RecyclerView.ViewHolder c = h.list.findViewHolderForAdapterPosition(0);
                if (c != null) c.itemView.requestFocus();
                h.list.setFocusable(false);
            });
            return true;
        }
        return !right && pos == 0;
    }

    private boolean moveRow(View f, boolean up) {
        View rowView = outer.findContainingItemView(f);
        if (rowView == null) return false;
        int rowPos = outer.getChildAdapterPosition(rowView);
        if (rowPos < 0 || rowPos >= rows.shown.size()) return false;
        Row cur = rows.shown.get(rowPos);
        RecyclerView.ViewHolder curVh = outer.getChildViewHolder(rowView);
        if (curVh instanceof RowsAdapter.RH) {
            RowsAdapter.RH h = (RowsAdapter.RH) curVh;
            View cell = h.list.findContainingItemView(f);
            if (cell != null) cur.focus = h.list.getChildAdapterPosition(cell);
        }
        int target = rowPos + (up ? -1 : 1);
        if (target < 0) { tabViews.get(tab).requestFocus(); return true; }
        if (target >= rows.shown.size()) return true;
        int[] loc = new int[2];
        f.getLocationOnScreen(loc);
        final int x = loc[0];
        ((PosterAdapter.Linear) outer.getLayoutManager()).glideToTop(outer, target);
        outer.post(() -> focusRow(target, x, 0));
        return true;
    }

    private void focusRow(int target, int x, int tries) {
        RecyclerView.ViewHolder vh = outer.findViewHolderForAdapterPosition(target);
        if (vh instanceof RowsAdapter.HeroH) { ((RowsAdapter.HeroH) vh).play.requestFocus(); return; }
        if (!(vh instanceof RowsAdapter.RH)) { if (tries < 14) outer.postDelayed(() -> focusRow(target, x, tries + 1), 20); return; }
        RowsAdapter.RH h = (RowsAdapter.RH) vh;
        Row r = rows.shown.get(target);
        if (r.focus >= 0) {
            RecyclerView.ViewHolder c = h.list.findViewHolderForAdapterPosition(r.focus);
            if (c != null) { c.itemView.requestFocus(); return; }
            h.list.scrollToPosition(r.focus);
            int want = r.focus;
            h.list.post(() -> {
                RecyclerView.ViewHolder c2 = h.list.findViewHolderForAdapterPosition(want);
                if (c2 != null) c2.itemView.requestFocus(); else h.list.requestFocus();
            });
            return;
        }
        View best = null;
        int bestD = Integer.MAX_VALUE;
        int[] loc = new int[2];
        for (int i = 0; i < h.list.getChildCount(); i++) {
            View c = h.list.getChildAt(i);
            c.getLocationOnScreen(loc);
            int d = Math.abs(loc[0] - x);
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best != null) best.requestFocus();
        else if (tries < 14) outer.postDelayed(() -> focusRow(target, x, tries + 1), 20);
    }

    // ---------- grade (abas Filmes / Séries) ----------

    private void showGrid(Services.Section sec) {
        final int gen = generation;
        RecyclerView grid = PosterAdapter.list(this);
        gridView = grid;
        outer = null;
        int pad = 22;
        grid.setPadding(Ui.dp(this, pad), Ui.dp(this, 6), Ui.dp(this, pad), Ui.dp(this, 30));
        grid.setLayoutManager(new PosterAdapter.Grid(this, PosterAdapter.span(this, Ui.POSTER_W, pad)));
        PosterAdapter a = new PosterAdapter(Ui.POSTER_W, Ui.POSTER_H);
        grid.setAdapter(a);
        content.addView(grid);
        TextView loading = loading();
        content.addView(loading);
        Net.async(() -> load(sec), (list, err) -> {
            if (gen != generation || isFinishing()) return;
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
        int focus = -1;   // último pôster selecionado nesta fileira
        JSONObject hero;  // destaque grande no topo (estilo Netflix)
    }

    private void showRows(List<Services.Section> secs) {
        final int gen = generation;
        RecyclerView list = PosterAdapter.list(this);
        outer = list;
        gridView = null;
        list.setPadding(0, 0, 0, Ui.dp(this, 40));
        PosterAdapter.Linear lm = new PosterAdapter.Linear(this, RecyclerView.VERTICAL);
        lm.suppress = true; // a rolagem vertical é feita por moveRow, deslizando
        list.setLayoutManager(lm);
        list.setItemViewCacheSize(4);
        rows = new RowsAdapter();
        list.setAdapter(rows);
        content.addView(list);
        final TextView loading = loading();
        content.addView(loading);
        rows.onFirstRow = () -> content.removeView(loading);
        heroLoadingView = loading;
        pending = secs.size();

        if (service.tabs[tab].equals("Início")) { pending++; addHero(gen); }
        for (Services.Section sec : secs) {
            Row r = new Row();
            r.title = sec.title;
            r.numbered = sec.numbered || sec.top10;
            r.cont = sec.cont;
            rows.slots.add(r);
            if (sec.cont) { contRow = r; loadContinue(r, gen); continue; }
            if (sec.genres) {
                r.loaded = true;
                // o espaço fica vazio; os gêneros entram logo a seguir
                Net.async(() -> Stremio.data(sec.home + "-home"), (home, err) -> {
                    if (gen != generation || isFinishing()) return;
                    done(loading);
                    JSONArray gs = home != null ? home.optJSONArray("genres") : null;
                    if (gs == null) return;
                    int at = rows.slots.indexOf(r) + 1;
                    for (int i = 0; i < gs.length(); i++) {
                        JSONObject g = gs.optJSONObject(i);
                        if (g == null) continue;
                        Row gr = new Row();
                        gr.title = g.optString("title");
                        gr.metas = cap(Stremio.metas(g.optJSONArray("metas")), 30);
                        gr.loaded = true;
                        rows.slots.add(at++, gr);
                    }
                    rows.sync();
                });
                continue;
            }
            Net.async(() -> load(sec), (metas, err) -> {
                if (gen != generation || isFinishing()) return;
                r.metas = metas == null ? null : cap(metas, r.numbered ? (sec.top10 ? 10 : 100) : 30);
                r.loaded = true;
                done(loading);
                rows.sync();
            });
        }
    }

    // ---------- destaque no topo do Início ----------

    private void addHero(int gen) {
        final View heroLoading = heroLoadingView;
        Row h = new Row();
        h.title = "";
        rows.slots.add(h);
        // 1) aparece já com o que está guardado (imagem, nome, ano)
        Net.async(() -> {
            List<JSONObject> pool = service.id.equals("cinemateca")
                    ? Stremio.metas(Stremio.data("cine-cinemas").optJSONArray("metas"))
                    : Stremio.metas(Stremio.data(service.id + "-home").optJSONArray("top10"));
            List<JSONObject> withBg = new ArrayList<>();
            for (JSONObject m : pool) if (!m.optString("background").isEmpty() && withBg.size() < 6) withBg.add(m);
            if (withBg.isEmpty()) return null;
            return new JSONObject(withBg.get((int) (Math.random() * withBg.size())).toString());
        }, (pick, err) -> {
            if (gen != generation || isFinishing()) return;
            done(heroLoading);
            if (pick == null) return;
            h.hero = pick;
            h.metas = new ArrayList<>();
            h.metas.add(pick);
            h.loaded = true;
            rows.sync();
            // 2) depois completa: logo, gênero e sinopse em português
            final String base = pick.toString();
            Net.async(() -> {
                JSONObject pick2 = new JSONObject(base); // cópia: a original continua na tela enquanto isto corre
                try {
                    JSONObject full = Stremio.meta(pick2.optString("type"), pick2.optString("id"));
                    pick2.put("logo", full.optString("logo", ""));
                    JSONArray g = full.optJSONArray("genres");
                    if (g != null && g.length() > 0) pick2.put("genre", g.optString(0));
                    pick2.put("description", full.optString("description", ""));
                } catch (Exception ignored) {}
                String pt = Stremio.ptDescription(pick2.optString("id"));
                if (pt != null) pick2.put("description", pt);
                return pick2;
            }, (done, e2) -> {
                if (gen != generation || isFinishing() || done == null) return;
                h.hero = done;
                h.metas.set(0, done);
                rows.changed(h);
            });
        });
    }

    private void openTitle(JSONObject m, boolean autoplay) {
        try {
            JSONObject meta = new JSONObject().put("id", m.optString("id")).put("type", m.optString("type"))
                    .put("name", m.optString("name")).put("poster", m.optString("poster", "")).put("background", m.optString("background", ""));
            Intent i = new Intent(this, TvDetailActivity.class);
            i.putExtra("meta", meta.toString());
            if (autoplay) i.putExtra("autoplay", true);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    /** uma seção respondeu: quando todas responderem e não houver nada, avisa */
    private void done(View loading) {
        if (pending <= 0 || --pending > 0) return;
        if (rows.shown.isEmpty()) {
            content.removeView(loading);
            content.addView(message("Não foi possível carregar. Verifique a internet."));
        }
    }

    private static List<JSONObject> cap(List<JSONObject> l, int n) {
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    private static List<JSONObject> load(Services.Section sec) throws Exception {
        if (sec.file != null) {
            List<JSONObject> metas = Stremio.metas(Stremio.data(sec.file).optJSONArray("metas"));
            // abas Filmes/Séries dos serviços: junta o arquivo -extra (até 2000 títulos, como no celular)
            if (sec.grid && sec.file.matches("(netflix|disney|prime|hbo|appletv|paramount|globoplay|clarovideo)-(movie|series)")) {
                try {
                    Set<String> seen = new HashSet<>();
                    for (JSONObject m : metas) seen.add(m.optString("id"));
                    for (JSONObject m : Stremio.metas(Stremio.data(sec.file + "-extra").optJSONArray("metas")))
                        if (seen.add(m.optString("id"))) metas.add(m);
                } catch (Exception ignored) {} // sem o arquivo extra, fica a lista de sempre
            }
            return metas;
        }
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
            if (gen != generation || isFinishing() || list == null) { if (!r.loaded) { r.loaded = true; rows.sync(); } return; }
            boolean same = r.loaded && sig(r.metas).equals(sig(list));
            r.metas = list;
            r.loaded = true;
            if (same) return; // nada mudou: não redesenha a fileira (sem piscar)
            rows.sync();
            rows.changed(r);
        });
    }

    /** resumo do Continuar assistindo (títulos e onde parou) para saber se mudou */
    private static String sig(List<JSONObject> l) {
        if (l == null) return "";
        StringBuilder b = new StringBuilder();
        for (JSONObject it : l) {
            JSONObject st = it.optJSONObject("state");
            b.append(it.optString("_id")).append(':').append(st != null ? st.optLong("timeOffset") / 30000 : 0).append(',');
        }
        return b.toString();
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
        JSONObject st = item.optJSONObject("state");
        String sub = "";
        if (st != null && st.optLong("duration") > 0) {
            long off = st.optLong("timeOffset") / 60000, dur = st.optLong("duration") / 60000;
            sub = "Parou em " + off + " min de " + dur + " min";
        }
        new TvSheet(this, item, sub)
                .action("✕   Remover do Continuar assistindo", () -> {
                    int pos = r.metas.indexOf(item), at = rows.shown.indexOf(r);
                    r.metas.remove(item);
                    r.focus = Math.min(Math.max(pos, 0), r.metas.size() - 1);
                    rows.sync();
                    rows.changed(r);
                    // a seleção fica no título ao lado (ou na fileira de baixo, se esvaziou)
                    int target = Math.min(Math.max(at, 0), rows.shown.size() - 1);
                    if (target >= 0) outer.postDelayed(() -> { if (!isFinishing()) focusRow(target, 0, 0); }, 60);
                    Context c = getApplicationContext();
                    Net.async(() -> { Stremio.rewind(c, item.optString("_id")); return true; }, (ok, e) -> {});
                })
                .action("Cancelar", null)
                .show();
    }

    // ---------- adaptador das fileiras ----------

    private final class RowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Row> slots = new ArrayList<>();
        final List<Row> shown = new ArrayList<>();
        Runnable onFirstRow;

        /** mostra só as fileiras já carregadas e com títulos, sem perder o item selecionado */
        void sync() {
            List<Row> next = new ArrayList<>();
            for (Row r : slots) if (r.loaded && r.metas != null && !r.metas.isEmpty()) next.add(r);
            for (int i = shown.size() - 1; i >= 0; i--) if (!next.contains(shown.get(i))) { shown.remove(i); notifyItemRemoved(i); }
            for (int i = 0; i < next.size(); i++) if (i >= shown.size() || shown.get(i) != next.get(i)) {
                if (!shown.contains(next.get(i))) { shown.add(i, next.get(i)); notifyItemInserted(i); }
            }
            if (!shown.isEmpty() && onFirstRow != null) { onFirstRow.run(); onFirstRow = null; }
            // ainda não entrou nas fileiras: fica sempre no topo (o que carrega depois não empurra a tela)
            View f = getCurrentFocus();
            if (outer != null && (f == null || tabViews.contains(f))) outer.scrollToPosition(0);
        }

        void changed(Row r) {
            int i = shown.indexOf(r);
            if (i >= 0) notifyItemChanged(i);
        }

        final class RH extends RecyclerView.ViewHolder {
            final TextView title;
            final RecyclerView list;
            final PosterAdapter adapter;
            LinearLayout panel;       // sinopse do título selecionado (só aparece na fileira que tem o foco)
            TextView pInfo, pDesc;
            int token;                // para ignorar sinopses que chegam depois de o foco já ter mudado
            RH(LinearLayout v, TextView t, RecyclerView l, PosterAdapter a) { super(v); title = t; list = l; adapter = a; }
        }

        final class HeroH extends RecyclerView.ViewHolder {
            final ImageView bg, logo;
            final TextView title, info, desc, play, more;
            HeroH(View v, ImageView bg, ImageView logo, TextView title, TextView info, TextView desc, TextView play, TextView more) {
                super(v); this.bg = bg; this.logo = logo; this.title = title; this.info = info; this.desc = desc; this.play = play; this.more = more;
            }
        }

        @Override public int getItemViewType(int position) { return shown.get(position).hero != null ? 1 : 0; }

        private HeroH hero(Context c) {
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 290));
            lp.setMargins(Ui.dp(c, 28), Ui.dp(c, 6), Ui.dp(c, 28), Ui.dp(c, 8));
            f.setLayoutParams(lp);
            f.setBackground(Ui.round(Ui.CARD, Ui.dp(c, 16)));
            f.setClipToOutline(true);
            ImageView bg = new ImageView(c);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View shade = new View(c);
            shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x8C000000, 0x00000000}));
            f.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM);
            col.setPadding(Ui.dp(c, 30), Ui.dp(c, 20), Ui.dp(c, 30), Ui.dp(c, 24));
            ImageView logo = new ImageView(c);
            logo.setAdjustViewBounds(true);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 340), Ui.dp(c, 78)));
            TextView title = Ui.text(c, "", 31, false, Ui.TEXT);
            TvHomeActivity.outfit(title, 600);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, new LinearLayout.LayoutParams(Ui.dp(c, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(Ui.space(c, 1, 8));
            TextView info = Ui.text(c, "", 14, true, 0xD9FFFFFF);
            col.addView(info);
            col.addView(Ui.space(c, 1, 8));
            TextView desc = Ui.text(c, "", 14, false, 0xE6FFFFFF);
            desc.setMaxLines(3);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setLineSpacing(0, 1.2f);
            col.addView(desc, new LinearLayout.LayoutParams(Ui.dp(c, 460), ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(Ui.space(c, 1, 14));
            LinearLayout btns = new LinearLayout(c);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView play = Ui.pill(c, "▶  Assistir");
            btns.addView(play);
            btns.addView(Ui.space(c, 12, 1));
            TextView more = Ui.pill(c, "ⓘ  Mais informações");
            btns.addView(more);
            col.addView(btns);
            f.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            HeroH h = new HeroH(f, bg, logo, title, info, desc, play, more);
            play.setOnClickListener(v -> { int p = h.getBindingAdapterPosition(); if (p >= 0 && shown.get(p).hero != null) openTitle(shown.get(p).hero, true); });
            more.setOnClickListener(v -> { int p = h.getBindingAdapterPosition(); if (p >= 0 && shown.get(p).hero != null) openTitle(shown.get(p).hero, false); });
            return h;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            if (viewType == 1) return hero(c);
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = Ui.text(c, "", 18, false, Ui.TEXT);
            TvHomeActivity.outfit(t, 600);
            t.setPadding(Ui.dp(c, 30), Ui.dp(c, 12), Ui.dp(c, 30), Ui.dp(c, 2));
            v.addView(t);
            RecyclerView l = PosterAdapter.list(c);
            l.setPadding(Ui.dp(c, 21), 0, Ui.dp(c, 21), 0);
            PosterAdapter.Linear llm = new PosterAdapter.Linear(c, RecyclerView.HORIZONTAL);
            llm.smooth = true; // o pôster selecionado desliza em vez de saltar
            l.setLayoutManager(llm);
            l.setRecycledViewPool(pool);
            llm.expandPx = Ui.dp(c, PosterAdapter.BANNER_W + 14);
            PosterAdapter a = new PosterAdapter(Ui.POSTER_W, Ui.POSTER_H);
            a.expandable = true;
            l.setAdapter(a);
            v.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, Ui.POSTER_H + 14)));
            // sinopse por baixo da fileira
            LinearLayout panel = new LinearLayout(c);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(Ui.dp(c, 30), Ui.dp(c, 4), Ui.dp(c, 30), Ui.dp(c, 12));
            panel.setVisibility(View.GONE);
            TextView pInfo = Ui.text(c, "", 14, true, 0xD9FFFFFF);
            panel.addView(pInfo);
            TextView pDesc = Ui.text(c, "", 14, false, 0xE6FFFFFF);
            pDesc.setMaxLines(3);
            pDesc.setEllipsize(TextUtils.TruncateAt.END);
            pDesc.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(c, 620), ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.topMargin = Ui.dp(c, 4);
            panel.addView(pDesc, dl);
            v.addView(panel);
            RH rh = new RH(v, t, l, a);
            rh.panel = panel; rh.pInfo = pInfo; rh.pDesc = pDesc;
            a.onFocusItem = (item, pos, has) -> rowFocus(rh, item, has);
            return rh;
        }

        private final Map<String, String> descCache = new HashMap<>();

        private void setListHeight(RH h, int dp) {
            ViewGroup.LayoutParams lp = h.list.getLayoutParams();
            int px = Ui.dp(h.list.getContext(), dp);
            if (lp.height != px) { lp.height = px; h.list.setLayoutParams(lp); }
        }

        /** o foco entrou ou saiu de um título da fileira: mostra ou esconde a sinopse embaixo */
        void rowFocus(RH h, JSONObject item, boolean has) {
            if (!has) {
                // só esconde se o foco saiu mesmo da fileira (e não só passou para o título do lado)
                h.list.post(() -> {
                    if (h.list.hasFocus()) return;
                    h.panel.setVisibility(View.GONE);
                    setListHeight(h, Ui.POSTER_H + 14);
                });
                return;
            }
            setListHeight(h, PosterAdapter.BANNER_H + 14); // a fileira ganha altura para o banner maior
            final int tok = ++h.token;
            List<String> parts = new ArrayList<>();
            parts.add("series".equals(item.optString("type")) ? "Série" : "Filme");
            if (!item.optString("releaseInfo").isEmpty()) parts.add(item.optString("releaseInfo"));
            h.pInfo.setText(TextUtils.join("  •  ", parts));
            String id = item.optString("id");
            String known = descCache.get(id);
            h.pDesc.setText(known != null ? known : "");
            h.panel.setVisibility(View.VISIBLE);
            if (known != null) return;
            // espera um instante: passando rápido pelos títulos não baixa nada
            h.panel.postDelayed(() -> {
                if (tok != h.token) return;
                Net.async(() -> {
                    String d = Stremio.ptDescription(id);
                    if (d == null || d.isEmpty()) {
                        try { d = Stremio.meta(item.optString("type"), id).optString("description", ""); } catch (Exception e) { d = ""; }
                    }
                    return d;
                }, (d, e) -> {
                    if (d == null) return;
                    descCache.put(id, d);
                    if (tok == h.token) h.pDesc.setText(d);
                });
            }, 250);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Row r = shown.get(position);
            if (vh instanceof HeroH) {
                HeroH hh = (HeroH) vh;
                JSONObject m = r.hero;
                Ui.image(hh.bg, m.optString("background", ""));
                hh.title.setText(m.optString("name"));
                Ui.logo(hh.logo, hh.title, m.optString("logo", ""));
                List<String> parts = new ArrayList<>();
                if (!m.optString("genre").isEmpty()) parts.add(m.optString("genre"));
                if (!m.optString("releaseInfo").isEmpty()) parts.add(m.optString("releaseInfo"));
                parts.add("series".equals(m.optString("type")) ? "Série" : "Filme");
                hh.info.setText(TextUtils.join("  •  ", parts));
                hh.desc.setText(m.optString("description", ""));
                return;
            }
            RH h = (RH) vh;
            h.title.setText(r.title);
            h.adapter.numbered = r.numbered;
            h.adapter.progress = r.cont;
            h.adapter.longPress = r.cont ? (item, pos) -> removeDialog(item, r) : null;
            if (!h.list.hasFocus()) { setListHeight(h, Ui.POSTER_H + 14); h.panel.setVisibility(View.GONE); }
            h.adapter.set(r.metas);
            h.list.scrollToPosition(Math.max(0, Math.min(r.focus, r.metas.size() - 1)));
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
