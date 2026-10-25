package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Página do título: fundo, sinopse em português, Assistir, Fontes, Mais Tarde e, nas séries, temporadas e episódios. */
public class TvDetailActivity extends TvBase {

    private JSONObject meta;
    private TextView title, info, desc, playBtn, sourcesBtn, listBtn, status;
    private ImageView backdrop;
    private LinearLayout seasonsBar;
    private RecyclerView episodes;
    private EpisodeAdapter epAdapter;
    private final TreeMap<Integer, List<JSONObject>> seasons = new TreeMap<>();
    private int season = -1;
    private boolean full;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { meta = new JSONObject(getIntent().getStringExtra("meta")); } catch (Exception e) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2000000, 0xC0000000, 0x40000000}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 260), Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 40), Ui.dp(this, 36), Ui.dp(this, 40), Ui.dp(this, 16));

        title = Ui.text(this, meta.optString("name"), 32, true, Ui.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.space(this, 1, 10));
        info = Ui.text(this, "", 15, false, Ui.MUTED);
        col.addView(info);
        col.addView(Ui.space(this, 1, 12));
        desc = Ui.text(this, "", 15, false, 0xE6FFFFFF);
        desc.setLineSpacing(0, 1.25f);
        desc.setMaxLines(5);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(desc, new LinearLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.space(this, 1, 20));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        playBtn = Ui.button(this, "▶  Assistir", 0xFFFFFFFF, 0xFF000000);
        playBtn.setOnClickListener(v -> playMain(true));
        btns.addView(playBtn);
        btns.addView(Ui.space(this, 12, 1));
        sourcesBtn = Ui.button(this, "Escolher fonte", 0xFF2A2A2E, Ui.TEXT);
        sourcesBtn.setOnClickListener(v -> playMain(false));
        btns.addView(sourcesBtn);
        btns.addView(Ui.space(this, 12, 1));
        listBtn = Ui.button(this, "+  Mais Tarde", 0xFF2A2A2E, Ui.TEXT);
        listBtn.setOnClickListener(v -> toggleList());
        btns.addView(listBtn);
        col.addView(btns);
        col.addView(Ui.space(this, 1, 10));
        status = Ui.text(this, "", 14, false, Ui.MUTED);
        col.addView(status);

        // séries: temporadas e episódios
        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        seasonsBar = new LinearLayout(this);
        seasonsBar.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(seasonsBar);
        col.addView(hs);
        episodes = PosterAdapter.list(this);
        episodes.setLayoutManager(new PosterAdapter.Linear(this, RecyclerView.HORIZONTAL));
        epAdapter = new EpisodeAdapter();
        episodes.setAdapter(epAdapter);
        episodes.setVisibility(View.GONE);
        col.addView(episodes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)));

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        Ui.image(backdrop, meta.optString("background", ""));
        focusLater(playBtn);
        load();
    }

    private void load() {
        String type = meta.optString("type"), id = meta.optString("id");
        Context c = getApplicationContext();
        Net.async(() -> {
            JSONObject m = Stremio.meta(type, id);
            String pt = Stremio.ptDescription(id);
            if (pt != null) m.put("gfDesc", pt);
            return m;
        }, (m, err) -> {
            if (isFinishing()) return;
            if (m == null) { status.setText("Não foi possível carregar os detalhes."); return; }
            if (!m.has("type")) try { m.put("type", type); } catch (Exception ignored) {}
            meta = m;
            full = true;
            fill();
        });
        Net.async(() -> Stremio.library(c, false), (lib, err) -> { if (!isFinishing()) updateButtons(); });
    }

    private void fill() {
        title.setText(meta.optString("name"));
        List<String> parts = new ArrayList<>();
        if (!meta.optString("releaseInfo").isEmpty()) parts.add(meta.optString("releaseInfo"));
        if (!meta.optString("runtime").isEmpty()) parts.add(meta.optString("runtime"));
        if (!meta.optString("imdbRating").isEmpty()) parts.add("IMDb " + meta.optString("imdbRating"));
        JSONArray g = meta.optJSONArray("genres");
        if (g != null) { List<String> gs = new ArrayList<>(); for (int i = 0; i < g.length() && i < 3; i++) gs.add(g.optString(i)); parts.add(TextUtils.join(", ", gs)); }
        info.setText(TextUtils.join("  ·  ", parts));
        desc.setText(meta.optString("gfDesc", meta.optString("description", "")));
        if (backdrop.getDrawable() == null) Ui.image(backdrop, meta.optString("background", ""));

        JSONArray vids = meta.optJSONArray("videos");
        if ("series".equals(meta.optString("type")) && vids != null && vids.length() > 0) {
            seasons.clear();
            for (int i = 0; i < vids.length(); i++) {
                JSONObject v = vids.optJSONObject(i);
                if (v == null) continue;
                int s = v.optInt("season", 0);
                List<JSONObject> l = seasons.get(s);
                if (l == null) seasons.put(s, l = new ArrayList<>());
                l.add(v);
            }
            for (List<JSONObject> l : seasons.values()) l.sort((a, b2) -> Integer.compare(a.optInt("episode"), b2.optInt("episode")));
            seasonsBar.removeAllViews();
            for (int s : seasons.keySet()) {
                if (s == 0 && seasons.size() > 1) continue;
                TextView t = Ui.button(this, s == 0 ? "Especiais" : "Temporada " + s, 0x00000000, Ui.TEXT);
                t.setTextSize(15);
                t.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
                t.setTag(s);
                t.setOnClickListener(v -> showSeason(s));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 6));
                seasonsBar.addView(t, lp);
            }
            String resume = Stremio.resumeVideo(this, meta.optString("id"));
            int start = firstSeason();
            if (resume != null) { JSONObject v = video(resume); if (v != null) start = v.optInt("season", start); }
            showSeason(start);
            episodes.setVisibility(View.VISIBLE);
        }
        updateButtons();
    }

    private int firstSeason() {
        for (int s : seasons.keySet()) if (s > 0) return s;
        return seasons.isEmpty() ? 0 : seasons.firstKey();
    }

    private void showSeason(int s) {
        season = s;
        for (int i = 0; i < seasonsBar.getChildCount(); i++) {
            TextView t = (TextView) seasonsBar.getChildAt(i);
            boolean on = Integer.valueOf(s).equals(t.getTag());
            android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
            GradientDrawable f = Ui.round(on ? 0xFF4A4A50 : 0xFF2A2A2E, Ui.dp(this, 18));
            f.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
            d.addState(new int[]{android.R.attr.state_focused}, f);
            d.addState(new int[]{}, Ui.round(on ? 0xFF4A4A50 : 0x00000000, Ui.dp(this, 18)));
            t.setBackground(d);
            t.setTextColor(on ? Ui.TEXT : Ui.MUTED);
        }
        List<JSONObject> l = seasons.get(s);
        epAdapter.set(l == null ? new ArrayList<>() : l);
        String resume = Stremio.resumeVideo(this, meta.optString("id"));
        int at = 0;
        if (l != null && resume != null) for (int i = 0; i < l.size(); i++) if (resume.equals(l.get(i).optString("id"))) at = i;
        episodes.scrollToPosition(at);
    }

    private JSONObject video(String id) {
        for (List<JSONObject> l : seasons.values()) for (JSONObject v : l) if (id.equals(v.optString("id"))) return v;
        return null;
    }

    /** qual vídeo o Assistir abre: o episódio onde parou, ou o primeiro */
    private String mainVideo() {
        if (!"series".equals(meta.optString("type"))) return meta.optString("id");
        String r = Stremio.resumeVideo(this, meta.optString("id"));
        if (r != null && video(r) != null) return r;
        List<JSONObject> l = seasons.get(firstSeason());
        return l != null && !l.isEmpty() ? l.get(0).optString("id") : null;
    }

    private void updateButtons() {
        String id = meta.optString("id");
        String vid = full ? mainVideo() : null;
        long resume = vid != null ? Stremio.resumeFor(this, id, vid) : 0;
        String label = resume > 0 ? "▶  Continuar" : "▶  Assistir";
        if ("series".equals(meta.optString("type")) && vid != null) {
            JSONObject v = video(vid);
            if (v != null) label += String.format(Locale.ROOT, "  T%d:E%d", v.optInt("season"), v.optInt("episode"));
        }
        playBtn.setText(label);
        listBtn.setText(Stremio.inLibrary(this, id) ? "✓  Na lista" : "+  Mais Tarde");
    }

    @Override
    protected void onProgressSaved() { if (full) { updateButtons(); if (season >= 0) showSeason(season); } }

    private void toggleList() {
        if (!Stremio.loggedIn(this)) { startActivity(new Intent(this, TvLoginActivity.class)); return; }
        boolean add = !Stremio.inLibrary(this, meta.optString("id"));
        listBtn.setText(add ? "✓  Na lista" : "+  Mais Tarde");
        Context c = getApplicationContext();
        JSONObject m = meta;
        Net.async(() -> { Stremio.setInLibrary(c, m, add); return true; }, (ok, e) -> {
            if (e != null) { Toast.makeText(this, "Não foi possível salvar.", Toast.LENGTH_SHORT).show(); updateButtons(); }
        });
    }

    private void playMain(boolean auto) {
        if (!full) { status.setText("Carregando…"); return; }
        String vid = mainVideo();
        if (vid == null) { status.setText("Sem episódios disponíveis."); return; }
        sources(vid, auto);
    }

    // ---------- fontes ----------

    private Dialog sourcesDialog;

    /** procura as fontes nos addons da conta. auto = toca a melhor sozinho */
    private void sources(String videoId, boolean auto) {
        if (!Stremio.loggedIn(this)) {
            Toast.makeText(this, "Entre na sua conta do Stremio para ver as fontes.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, TvLoginActivity.class));
            return;
        }
        final JSONObject m = meta;
        final boolean[] done = {false};
        SourceList adapter = new SourceList();
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 12));
        box.setBackground(Ui.round(0xFF17171A, Ui.dp(this, 14)));
        TextView head = Ui.text(this, auto ? "Procurando a melhor fonte…" : "Procurando fontes…", 18, true, Ui.TEXT);
        box.addView(head);
        box.addView(Ui.space(this, 1, 10));
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        lv.setItemsCanFocus(true);
        box.addView(lv, new LinearLayout.LayoutParams(Ui.dp(this, 620), Ui.dp(this, 380)));
        d.setContentView(box);
        d.setOnDismissListener(x -> done[0] = true);
        adapter.onPick = src -> { done[0] = true; d.dismiss(); play(m, videoId, src); };
        d.show();
        sourcesDialog = d;

        Runnable autoPick = () -> {
            if (done[0]) return;
            for (Stremio.Source s : adapter.items) if (!s.torrent) { adapter.onPick.pick(s); return; }
        };
        if (auto) Net.MAIN.postDelayed(autoPick, 7000);

        Stremio.streams(getApplicationContext(), m.optString("type"), videoId, (all, finished) -> {
            if (done[0]) return;
            adapter.set(all);
            if (all.isEmpty()) head.setText(finished ? "Nenhuma fonte encontrada. Verifique os addons da conta." : "Procurando fontes…");
            else head.setText((finished ? "" : "Procurando… ") + all.size() + (all.size() == 1 ? " fonte" : " fontes"));
            if (finished && auto) {
                boolean any = false;
                for (Stremio.Source s : all) if (!s.torrent) { any = true; break; }
                if (any) autoPick.run();
                else if (!all.isEmpty()) head.setText("Só há torrents: tocam apenas com Real-Debrid no addon.");
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (sourcesDialog != null && sourcesDialog.isShowing()) sourcesDialog.dismiss();
        super.onDestroy();
    }

    private final class SourceList extends BaseAdapter {
        interface Pick { void pick(Stremio.Source s); }
        final List<Stremio.Source> items = new ArrayList<>();
        Pick onPick;
        private boolean focusedOnce;

        void set(List<Stremio.Source> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Context c = parent.getContext();
            TextView t = v instanceof TextView ? (TextView) v : null;
            if (t == null) {
                t = Ui.button(c, "", 0xFF232327, Ui.TEXT);
                t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                t.setTextSize(14);
                t.setTypeface(android.graphics.Typeface.DEFAULT);
                t.setMaxLines(3);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            }
            Stremio.Source s = items.get(i);
            String firstLine = s.title.split("\n")[0];
            String rest = s.title.contains("\n") ? s.title.substring(s.title.indexOf('\n') + 1).replace('\n', ' ') : "";
            t.setText((s.torrent ? "🧲 " : "") + s.name + "  ·  " + firstLine + (rest.isEmpty() ? "" : "\n" + rest));
            t.setAlpha(s.torrent ? 0.55f : 1f);
            t.setOnClickListener(x -> {
                if (s.torrent) { Toast.makeText(c, "Torrent: só toca com Real-Debrid no addon.", Toast.LENGTH_LONG).show(); return; }
                if (onPick != null) onPick.pick(s);
            });
            ListView.LayoutParams lp = new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t.setLayoutParams(lp);
            if (i == 0 && !focusedOnce) { focusedOnce = true; TextView ft = t; ft.post(ft::requestFocus); }
            return t;
        }
    }

    // ---------- episódios ----------

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.EH> {
        final List<JSONObject> items = new ArrayList<>();
        void set(List<JSONObject> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        final class EH extends RecyclerView.ViewHolder {
            final ImageView img; final TextView name, mark;
            EH(View v, ImageView i, TextView n, TextView m) { super(v); img = i; name = n; mark = m; }
        }

        @NonNull @Override
        public EH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 210), Ui.dp(c, 136));
            lp.setMargins(Ui.dp(c, 5), Ui.dp(c, 6), Ui.dp(c, 5), Ui.dp(c, 6));
            f.setLayoutParams(lp);
            f.setBackground(Ui.round(Ui.CARD, Ui.dp(c, 8)));
            f.setForeground(Ui.focusRing(c, 8));
            f.setFocusable(true);
            f.setClickable(true);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 92), Gravity.TOP));
            TextView n = Ui.text(c, "", 13, true, Ui.TEXT);
            n.setMaxLines(2);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 6));
            f.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 42), Gravity.BOTTOM));
            TextView mark = Ui.text(c, "", 12, true, Ui.TEXT);
            mark.setBackground(Ui.round(0xE6E50914, Ui.dp(c, 5)));
            mark.setPadding(Ui.dp(c, 6), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 3));
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            ml.setMargins(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
            f.addView(mark, ml);
            EH h = new EH(f, img, n, mark);
            f.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && p < items.size()) sources(items.get(p).optString("id"), true);
            });
            f.setOnLongClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && p < items.size()) sources(items.get(p).optString("id"), false);
                return true;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull EH h, int position) {
            JSONObject v = items.get(position);
            String name = v.optString("name", v.optString("title", ""));
            h.name.setText(v.optInt("episode") + ". " + name);
            Ui.image(h.img, v.optString("thumbnail", ""));
            String resume = Stremio.resumeVideo(TvDetailActivity.this, meta.optString("id"));
            boolean here = v.optString("id").equals(resume) && Stremio.resumeFor(TvDetailActivity.this, meta.optString("id"), resume) > 0;
            h.mark.setVisibility(here ? View.VISIBLE : View.GONE);
            h.mark.setText("Parou aqui");
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
