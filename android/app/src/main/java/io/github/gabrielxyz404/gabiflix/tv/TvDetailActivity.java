package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

/**
 * Página do título, no estilo da Netflix: a imagem cobre o fundo, o nome (ou o logo), as informações e uma lista de botões.
 * Filme: "Assistir" escolhe a fonte. Série: "Temporada 1: E1" (ou onde parou) e "Episódios" abre temporadas e episódios.
 */
public class TvDetailActivity extends TvBase {

    private JSONObject meta;
    private TextView title, info, desc, playBtn, episodesBtn, listBtn, removeBtn, status;
    private ImageView backdrop, logo;
    private TextView where;
    private final TreeMap<Integer, List<JSONObject>> seasons = new TreeMap<>();
    private boolean full, autoDone;

    // painel dos episódios (escondido até carregar em "Episódios")
    private FrameLayout panel;
    private LinearLayout seasonsCol;
    private RecyclerView episodes;
    private final EpisodeAdapter epAdapter = new EpisodeAdapter();
    private int season = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { meta = new JSONObject(getIntent().getStringExtra("meta")); } catch (Exception e) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF0000000, 0xB3000000, 0x26000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220), Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(Ui.dp(this, 48), Ui.dp(this, 30), 0, Ui.dp(this, 30));

        // onde está disponível (como no celular): nome do serviço na cor dele, em cima do título
        where = Ui.text(this, "", 20, true, Ui.TEXT);
        TvHomeActivity.outfit(where, 800);
        where.setLetterSpacing(0.04f);

        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(View.GONE);
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 440), Ui.dp(this, 110)));
        title = Ui.text(this, meta.optString("name"), 38, true, Ui.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.space(this, 1, 12));
        info = Ui.text(this, "", 15, true, 0xD9FFFFFF);
        col.addView(info);
        col.addView(Ui.space(this, 1, 12));
        desc = Ui.text(this, "", 15, false, 0xE6FFFFFF);
        desc.setLineSpacing(0, 1.25f);
        desc.setMaxLines(4);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(desc, new LinearLayout.LayoutParams(Ui.dp(this, 540), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.space(this, 1, 22));

        playBtn = pill("▶   Assistir");
        playBtn.setOnClickListener(v -> playMain());
        col.addView(playBtn, new LinearLayout.LayoutParams(Ui.dp(this, 380), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.space(this, 1, 6));
        episodesBtn = item("☰   Episódios");
        episodesBtn.setOnClickListener(v -> openPanel());
        episodesBtn.setVisibility(View.GONE);
        col.addView(episodesBtn, itemLp());
        listBtn = item("+   Mais Tarde");
        listBtn.setOnClickListener(v -> toggleList());
        col.addView(listBtn, itemLp());
        removeBtn = item("✕   Remover do Continuar assistindo");
        removeBtn.setOnClickListener(v -> removeFromContinue());
        removeBtn.setVisibility(View.GONE);
        col.addView(removeBtn, itemLp());
        status = Ui.text(this, "", 14, false, Ui.MUTED);
        status.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        col.addView(status);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // serviço(s) no topo da página, canto esquerdo
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        wl.setMargins(Ui.dp(this, 48), Ui.dp(this, 28), 0, 0);
        root.addView(where, wl);
        root.addView(buildPanel(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        Ui.image(backdrop, meta.optString("background", ""));
        focusLater(playBtn);
        load();
        loadWhere();
    }

    // ---------- botões no estilo da Netflix ----------

    /** botão principal: branco */
    private TextView pill(String label) {
        TextView t = Ui.text(this, label, 18, true, 0xFF000000);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 26), Ui.dp(this, 13), Ui.dp(this, 26), Ui.dp(this, 13));
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = Ui.round(0xFFFFFFFF, Ui.dp(this, 28));
        on.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, Ui.round(0x33FFFFFF, Ui.dp(this, 28))); // sem seleção: cinza transparente
        t.setBackground(s);
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF000000, 0xFFFFFFFF}));
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }

    /** botões de baixo: só texto; selecionado fica com fundo branco */
    private TextView item(String label) {
        TextView t = Ui.text(this, label, 17, true, Ui.TEXT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 26), Ui.dp(this, 11), Ui.dp(this, 26), Ui.dp(this, 11));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.round(0xFFFFFFFF, Ui.dp(this, 28)));
        s.addState(new int[]{}, Ui.round(0x00000000, Ui.dp(this, 28)));
        t.setBackground(s);
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF000000, 0xFFFFFFFF}));
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams itemLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 380), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        return lp;
    }

    // ---------- em que serviço está ----------

    private static final String[][] SVC = {
            {"netflix", "Netflix", "#e50914"}, {"disney", "Disney+", "#3aa3ff"}, {"prime", "Prime Video", "#1ba8e0"},
            {"hbo", "HBO Max", "#a36bff"}, {"appletv", "Apple TV+", "#f5f5f7"}, {"paramount", "Paramount+", "#2f7dff"},
            {"globoplay", "Globoplay", "#c8d23a"}, {"clarovideo", "Claro Video", "#ff2a2a"}};

    private void loadWhere() {
        String id = meta.optString("id");
        Net.async(() -> {
            List<String[]> found = new ArrayList<>();
            for (String[] s : SVC) if (Stremio.ids(s[0]).contains(id)) found.add(s);
            return found;
        }, (found, err) -> {
            if (isFinishing() || found == null) return;
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
            if (found.isEmpty()) found.add(new String[]{"cinemateca", "CineTime", "#e9c46a"});
            for (int i = 0; i < found.size() && i < 2; i++) {
                if (i > 0) b.append("  ·  ");
                int start = b.length();
                b.append(found.get(i)[1]);
                b.setSpan(new android.text.style.ForegroundColorSpan(Ui.parseColor(found.get(i)[2], 0xFFFFFFFF)), start, b.length(), 0);
            }
            where.setText(b);
        });
    }

    // ---------- dados ----------

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
            // veio do "Assistir" do destaque: abre logo as fontes
            if (getIntent().getBooleanExtra("autoplay", false) && !autoDone) { autoDone = true; playMain(); }
        });
        Net.async(() -> Stremio.library(c, false), (lib, err) -> { if (!isFinishing()) updateButtons(); });
    }

    private void fill() {
        title.setText(meta.optString("name"));
        String logoUrl = meta.optString("logo", "");
        if (!logoUrl.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(logoUrl).dontAnimate()
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t, boolean first) { return false; }
                        @Override public boolean onResourceReady(@NonNull android.graphics.drawable.Drawable r, @NonNull Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t, @NonNull com.bumptech.glide.load.DataSource ds, boolean first) {
                            logo.setVisibility(View.VISIBLE);
                            title.setVisibility(View.GONE);
                            return false;
                        }
                    }).into(logo);
        }
        List<String> parts = new ArrayList<>();
        if (!meta.optString("releaseInfo").isEmpty()) parts.add(meta.optString("releaseInfo"));
        JSONArray g = meta.optJSONArray("genres");
        if (g != null && g.length() > 0) parts.add(g.optString(0));
        if (!meta.optString("runtime").isEmpty()) parts.add(meta.optString("runtime"));
        if (!meta.optString("imdbRating").isEmpty()) parts.add("IMDb " + meta.optString("imdbRating"));
        info.setText(TextUtils.join("  •  ", parts));
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
            if (seasons.size() > 1) seasons.remove(0); // especiais só quando não há mais nada
            episodesBtn.setVisibility(View.VISIBLE);
            buildSeasons();
        }
        updateButtons();
    }

    private int firstSeason() {
        for (int s : seasons.keySet()) if (s > 0) return s;
        return seasons.isEmpty() ? 0 : seasons.firstKey();
    }

    private JSONObject video(String id) {
        for (List<JSONObject> l : seasons.values()) for (JSONObject v : l) if (id.equals(v.optString("id"))) return v;
        return null;
    }

    private boolean isSeries() { return "series".equals(meta.optString("type")); }

    /** qual vídeo o botão principal abre: o episódio onde parou, ou o primeiro */
    private String mainVideo() {
        if (!isSeries()) return meta.optString("id");
        String r = Stremio.resumeVideo(this, meta.optString("id"));
        if (r != null && video(r) != null) return r;
        List<JSONObject> l = seasons.get(firstSeason());
        return l != null && !l.isEmpty() ? l.get(0).optString("id") : null;
    }

    private void updateButtons() {
        String id = meta.optString("id");
        String vid = full ? mainVideo() : null;
        long resume = vid != null ? Stremio.resumeFor(this, id, vid) : 0;
        String label;
        if (isSeries() && vid != null && video(vid) != null) {
            JSONObject v = video(vid);
            String ep = (v.optInt("season") == 0 ? "Especial" : "Temporada " + v.optInt("season")) + ": E" + v.optInt("episode");
            label = (resume > 0 ? "▶   Continuar  " : "▶   ") + ep;
        } else {
            label = resume > 0 ? "▶   Continuar" : "▶   Assistir";
        }
        playBtn.setText(label);
        listBtn.setText(Stremio.inLibrary(this, id) ? "✓   Na lista" : "+   Mais Tarde");
        removeBtn.setVisibility(resume > 0 || hasProgress() ? View.VISIBLE : View.GONE);
    }

    private boolean hasProgress() {
        JSONObject it = Stremio.cachedItem(meta.optString("id"));
        JSONObject st = it != null ? it.optJSONObject("state") : null;
        return st != null && st.optLong("timeOffset") > 0;
    }

    @Override
    protected void onProgressSaved() { if (full) { updateButtons(); epAdapter.notifyDataSetChanged(); } }

    private void toggleList() {
        if (!Stremio.loggedIn(this)) { startActivity(new Intent(this, TvLoginActivity.class)); return; }
        boolean add = !Stremio.inLibrary(this, meta.optString("id"));
        listBtn.setText(add ? "✓   Na lista" : "+   Mais Tarde");
        Context c = getApplicationContext();
        JSONObject m = meta;
        Net.async(() -> { Stremio.setInLibrary(c, m, add); return true; }, (ok, e) -> {
            if (e != null) { Toast.makeText(this, "Não foi possível salvar.", Toast.LENGTH_SHORT).show(); updateButtons(); }
        });
    }

    private void removeFromContinue() {
        Context c = getApplicationContext();
        String id = meta.optString("id");
        Net.async(() -> { Stremio.rewind(c, id); return true; }, (ok, e) -> {
            if (e != null) { Toast.makeText(this, "Não foi possível remover.", Toast.LENGTH_SHORT).show(); return; }
            Toast.makeText(this, "Removido do Continuar assistindo.", Toast.LENGTH_SHORT).show();
            updateButtons();
            playBtn.requestFocus();
        });
    }

    private void playMain() {
        if (!full) { status.setText("Carregando…"); return; }
        String vid = mainVideo();
        if (vid == null) { status.setText("Sem episódios disponíveis."); return; }
        sources(vid);
    }

    // ---------- painel de temporadas e episódios ----------

    private View buildPanel() {
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xFF0B0B0D);
        panel.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 40), Ui.dp(this, 30), Ui.dp(this, 30), 0);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        seasonsCol = new LinearLayout(this);
        seasonsCol.setOrientation(LinearLayout.VERTICAL);
        sc.addView(seasonsCol);
        row.addView(sc, new LinearLayout.LayoutParams(Ui.dp(this, 230), ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(Ui.space(this, 24, 1));

        episodes = PosterAdapter.list(this);
        episodes.setLayoutManager(new PosterAdapter.Linear(this, RecyclerView.VERTICAL));
        episodes.setPadding(0, 0, 0, Ui.dp(this, 30));
        episodes.setAdapter(epAdapter);
        row.addView(episodes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return panel;
    }

    private void buildSeasons() {
        seasonsCol.removeAllViews();
        TextView name = Ui.text(this, meta.optString("name"), 22, true, Ui.TEXT);
        name.setMaxLines(2);
        name.setPadding(Ui.dp(this, 10), 0, 0, Ui.dp(this, 16));
        seasonsCol.addView(name);
        for (int s : seasons.keySet()) {
            TextView t = item(s == 0 ? "Especiais" : "Temporada " + s);
            t.setTextSize(16);
            t.setTag(s);
            t.setOnFocusChangeListener((v, has) -> { if (has && season != s) showSeason(s); });
            t.setOnClickListener(v -> episodes.requestFocus());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 4);
            seasonsCol.addView(t, lp);
        }
    }

    private void showSeason(int s) {
        season = s;
        for (int i = 0; i < seasonsCol.getChildCount(); i++) {
            View v = seasonsCol.getChildAt(i);
            if (v.getTag() == null) continue;
            v.setAlpha(Integer.valueOf(s).equals(v.getTag()) ? 1f : 0.6f);
        }
        List<JSONObject> l = seasons.get(s);
        epAdapter.set(l == null ? new ArrayList<>() : l);
        episodes.scrollToPosition(0);
    }

    private void openPanel() {
        if (seasons.isEmpty()) return;
        String vid = mainVideo();
        JSONObject v = vid != null ? video(vid) : null;
        int s = v != null ? v.optInt("season") : firstSeason();
        if (!seasons.containsKey(s)) s = firstSeason();
        showSeason(s);
        panel.setVisibility(View.VISIBLE);
        int at = 0;
        List<JSONObject> l = seasons.get(s);
        if (l != null && vid != null) for (int i = 0; i < l.size(); i++) if (vid.equals(l.get(i).optString("id"))) at = i;
        final int pos = at;
        episodes.scrollToPosition(pos);
        episodes.post(() -> {
            RecyclerView.ViewHolder h = episodes.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.requestFocus(); else episodes.requestFocus();
        });
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        episodesBtn.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) { closePanel(); return; }
        super.onBackPressed();
    }

    // ---------- fontes ----------

    private SourcePicker picker;

    /** janela das fontes: provedores em cima, fontes (Real-Debrid / TorBox e qualidade) em baixo */
    private void sources(String videoId) {
        if (!Stremio.loggedIn(this)) {
            Toast.makeText(this, "Entre na sua conta do Stremio para ver as fontes.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, TvLoginActivity.class));
            return;
        }
        final JSONObject m = meta;
        String what = meta.optString("name");
        JSONObject v = isSeries() ? video(videoId) : null;
        if (v != null) what += "  ·  Temporada " + v.optInt("season") + ": E" + v.optInt("episode");
        final boolean[] done = {false};
        SourcePicker p = new SourcePicker(this, what, src -> { done[0] = true; play(m, videoId, src); });
        p.onDismiss(() -> done[0] = true);
        p.show();
        picker = p;
        Stremio.streams(getApplicationContext(), m.optString("type"), videoId, (all, finished) -> {
            if (!done[0]) p.update(all, finished);
        });
    }

    @Override
    protected void onDestroy() {
        if (picker != null) picker.dismiss();
        super.onDestroy();
    }

    // ---------- lista de episódios (no painel) ----------

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.EH> {
        final List<JSONObject> items = new ArrayList<>();
        void set(List<JSONObject> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        final class EH extends RecyclerView.ViewHolder {
            final ImageView img; final TextView name, overview, mark;
            final View bar, fill;
            EH(View v, ImageView i, TextView n, TextView o, TextView m, View b, View f) { super(v); img = i; name = n; overview = o; mark = m; bar = b; fill = f; }
        }

        @NonNull @Override
        public EH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
            row.setLayoutParams(lp);
            row.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            StateListDrawable s = new StateListDrawable();
            GradientDrawable on = Ui.round(0xFF26262B, Ui.dp(c, 10));
            on.setStroke(Ui.dp(c, 3), 0xFFFFFFFF);
            s.addState(new int[]{android.R.attr.state_focused}, on);
            s.addState(new int[]{}, Ui.round(0x00000000, Ui.dp(c, 10)));
            row.setBackground(s);
            row.setFocusable(true);
            row.setClickable(true);

            FrameLayout thumb = new FrameLayout(c);
            thumb.setBackground(Ui.round(Ui.CARD, Ui.dp(c, 6)));
            thumb.setClipToOutline(true);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View bar = new View(c);
            bar.setBackgroundColor(0x99000000);
            thumb.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
            View fill = new View(c);
            fill.setBackgroundColor(0xFFE50914);
            thumb.addView(fill, new FrameLayout.LayoutParams(0, Ui.dp(c, 4), Gravity.BOTTOM | Gravity.START));
            row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 200), Ui.dp(c, 112)));
            row.addView(Ui.space(c, 18, 1));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView n = Ui.text(c, "", 17, true, Ui.TEXT);
            n.setMaxLines(1);
            n.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(n);
            TextView m = Ui.text(c, "Parou aqui", 12, true, 0xFFE50914);
            m.setPadding(0, Ui.dp(c, 4), 0, 0);
            texts.addView(m);
            TextView o = Ui.text(c, "", 14, false, Ui.MUTED);
            o.setMaxLines(3);
            o.setEllipsize(TextUtils.TruncateAt.END);
            o.setLineSpacing(0, 1.2f);
            o.setPadding(0, Ui.dp(c, 6), 0, 0);
            texts.addView(o);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            EH h = new EH(row, img, n, o, m, bar, fill);
            row.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && p < items.size()) sources(items.get(p).optString("id"));
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull EH h, int position) {
            JSONObject v = items.get(position);
            String name = v.optString("name", v.optString("title", ""));
            h.name.setText(v.optInt("episode") + ".  " + (name.isEmpty() ? "Episódio " + v.optInt("episode") : name));
            h.overview.setText(v.optString("overview", v.optString("description", "")));
            Ui.image(h.img, v.optString("thumbnail", ""));
            String id = meta.optString("id");
            String resume = Stremio.resumeVideo(TvDetailActivity.this, id);
            long off = v.optString("id").equals(resume) ? Stremio.resumeFor(TvDetailActivity.this, id, resume) : 0;
            h.mark.setVisibility(off > 0 ? View.VISIBLE : View.GONE);
            long dur = 0;
            JSONObject it = Stremio.cachedItem(id);
            if (it != null && it.optJSONObject("state") != null) dur = it.optJSONObject("state").optLong("duration");
            boolean show = off > 0 && dur > 0;
            h.bar.setVisibility(show ? View.VISIBLE : View.GONE);
            h.fill.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                ViewGroup.LayoutParams lp = h.fill.getLayoutParams();
                lp.width = (int) (Ui.dp(h.itemView.getContext(), 200) * Math.min(1.0, off / (double) dur));
                h.fill.setLayoutParams(lp);
            }
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
