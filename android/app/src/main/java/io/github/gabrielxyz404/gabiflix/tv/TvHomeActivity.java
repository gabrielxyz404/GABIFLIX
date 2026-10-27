package io.github.gabrielxyz404.gabiflix.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

/** Tela inicial da TV: os serviços em cartões altos, lado a lado, com rolagem infinita para a direita e a esquerda. */
public class TvHomeActivity extends TvBase {

    private View accountBtn;
    private View rootView;
    private FrameLayout stage;
    private View bgFront, bgBack;   // camada de fundo visível e a que vai aparecer
    private String bgId = "";
    private android.graphics.drawable.Drawable bgNow = new android.graphics.drawable.ColorDrawable(0xFF0B0B0D);
    private RecyclerView list;
    private static final int N = Services.ALL.size();
    private static final int MIDDLE = (Integer.MAX_VALUE / 2) - ((Integer.MAX_VALUE / 2) % Services.ALL.size());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 14));
        rootView = root;
        root.setClipChildren(false);
        // o fundo fica em duas camadas por trás (cada uma vira uma textura da placa de vídeo): trocar de serviço é só esmaecer uma sobre a outra
        stage = new FrameLayout(this);
        bgBack = new View(this);
        bgFront = new View(this);
        for (View v : new View[]{bgBack, bgFront}) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            stage.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        bgFront.setBackground(bgNow);

        // topo: logo + "Home" à esquerda, ícones à direita
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 34), Ui.dp(this, 6), Ui.dp(this, 34), Ui.dp(this, 6));
        top.setClipChildren(false); // o ícone selecionado cresce sem ser cortado
        top.setClipToPadding(false);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(io.github.gabrielxyz404.gabiflix.R.mipmap.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.space(this, 14, 1));
        TextView home = Ui.text(this, "Home", 26, true, Ui.TEXT);
        outfit(home, 600);
        top.addView(home, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View later = iconButton("later", "Mais Tarde");
        later.setId(View.generateViewId());
        later.setNextFocusLeftId(later.getId()); // à esquerda do primeiro ícone não salta para os cartões
        later.setOnClickListener(v -> startActivity(new Intent(this, TvSearchActivity.class).putExtra("library", true)));
        top.addView(later);
        View search = iconButton("search", "Pesquisar");
        search.setOnClickListener(v -> startActivity(new Intent(this, TvSearchActivity.class)));
        top.addView(search);
        View update = iconButton("refresh", "Atualizar");
        update.setOnClickListener(v -> { Updater.run(this); prefetchSoon(); });
        top.addView(update);
        accountBtn = iconButton("account", "Conta");
        accountBtn.setOnClickListener(v -> onAccount());
        top.addView(accountBtn);
        root.addView(top);

        // cartões dos serviços (infinitos: depois do último vem o primeiro)
        list = PosterAdapter.list(this);
        list.setPadding(Ui.dp(this, 24), Ui.dp(this, 30), Ui.dp(this, 24), Ui.dp(this, 26));
        PosterAdapter.Linear hlm = new PosterAdapter.Linear(this, RecyclerView.HORIZONTAL);
        hlm.smooth = true; // os cartões deslizam ao mudar de catálogo
        list.setLayoutManager(hlm);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(N + 2);
        list.setAdapter(new Cards());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        stage.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(stage);
        ((PosterAdapter.Linear) list.getLayoutManager()).scrollToPositionWithOffset(MIDDLE, 0);
        focusCard(MIDDLE, 0);
        prefetch();
        Net.async(() -> Stremio.data("service-art"), (d, e) -> {
            if (d == null || isFinishing()) return;
            artData = d;
            for (int i = 0; i < list.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = list.getChildViewHolder(list.getChildAt(i));
                if (vh instanceof Cards.CH) {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0) bindArt((Cards.CH) vh, Services.ALL.get(p % N).id);
                }
            }
        });
        String crash = takeCrash(this);
        if (crash != null) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("O app fechou com um erro")
                    .setMessage("Tire uma foto desta tela e mande para o Claude corrigir:\n\n" + crash)
                    .setPositiveButton("OK", null)
                    .show();
        }
        testPlay(getIntent());
    }

    private void prefetchSoon() { list.postDelayed(this::prefetch, 1500); }

    /** baixa já os dados do Início de cada catálogo: ao entrar, abre na hora */
    private void prefetch() {
        Net.async(() -> {
            for (Services.Service s : Services.ALL) {
                try {
                    if (s.id.equals("cinemateca")) { Stremio.data("cine-cinemas"); Stremio.data("cine-classicos"); }
                    else Stremio.data(s.id + "-home");
                    if (s.id.equals("netflix")) { Stremio.data("netflix-top10-br-series"); Stremio.data("netflix-top10-br-movie"); }
                } catch (Exception ignored) {}
            }
            return true;
        }, (ok, e) -> {});
    }

    /** só para os testes automáticos: abre um vídeo direto no player */
    private void testPlay(Intent i) {
        if (i != null && i.getBooleanExtra("gfTestPicker", false)) {
            java.util.List<Stremio.Source> l = new java.util.ArrayList<>();
            String[][] f = {
                    {"Torrentio", "[RD+] Torrentio\n1080p", "Filme.2024.1080p.WEB-DL.DUAL.mkv\n👤 120 💾 2.4 GB ⚙️ TPB"},
                    {"Torrentio", "[TB+] Torrentio\n4k", "Filme.2024.2160p.HDR.mkv\n👤 80 💾 14.2 GB ⚙️ RARBG"},
                    {"Torrentio", "[RD download] Torrentio\n720p", "Filme.2024.720p.mkv\n👤 5 💾 900 MB"},
                    {"Torrentio", "[TB+] Torrentio\n1080p", "Filme.2024.1080p.Dublado.PT-BR.mkv\n💾 3.1 GB"},
                    {"TorrentsDB", "[RD+] TorrentsDB 1080p", "Filme 2024 1080p BluRay\n💾 8.7 GB"},
                    {"TorrentsDB", "[TB⚡] TorrentsDB 720p", "Filme 2024 720p\n💾 1.2 GB"}};
            for (String[] x : f) { Stremio.Source s = new Stremio.Source(); s.addon = x[0]; s.name = x[1]; s.title = x[2]; s.url = "http://x/" + l.size(); l.add(s); }
            SourcePicker p = new SourcePicker(this, "Filme de teste", src -> {});
            p.show();
            list.postDelayed(() -> p.update(l, true), 500);
            return;
        }
        if (i != null && i.getBooleanExtra("gfTestContinue", false)) {
            Net.async(() -> {
                java.util.Map<String, org.json.JSONObject> m = new java.util.LinkedHashMap<>();
                int n = 0;
                for (String id : Stremio.ids("netflix")) {
                    org.json.JSONObject meta = null;
                    String type = "movie";
                    for (String t : new String[]{"movie", "series"}) {
                        try { meta = Stremio.meta(t, id); type = t; break; } catch (Exception ignored) {}
                    }
                    if (meta == null) continue;
                    org.json.JSONObject st = new org.json.JSONObject().put("timeOffset", 900000L).put("duration", 2700000L)
                            .put("lastWatched", "2026-10-26T20:00:0" + n + ".000Z").put("video_id", org.json.JSONObject.NULL);
                    m.put(id, new org.json.JSONObject().put("_id", id).put("type", type).put("name", meta.optString("name"))
                            .put("poster", meta.optString("poster", "")).put("state", st));
                    if (++n >= 3) break;
                }
                return m;
            }, (m, e) -> {
                if (m != null) Stremio.fakeLibrary(getApplicationContext(), m);
                startActivity(new Intent(this, TvCatalogActivity.class).putExtra("service", "netflix"));
            });
            return;
        }
        if (i != null && i.getBooleanExtra("gfTestLibrary", false)) {
            startActivity(new Intent(this, TvSearchActivity.class).putExtra("library", true));
            return;
        }
        String testMeta = i != null ? i.getStringExtra("gfTestMeta") : null;
        if (testMeta != null) {
            Intent d = new Intent(this, TvDetailActivity.class);
            d.putExtra("meta", testMeta);
            startActivity(d);
            return;
        }
        String url = i != null ? i.getStringExtra("gfTestUrl") : null;
        if (url == null) return;
        try {
            org.json.JSONObject meta = new org.json.JSONObject().put("id", "tt1254207").put("type", "movie").put("name", "Big Buck Bunny");
            Stremio.Source src = new Stremio.Source();
            src.url = url;
            play(meta, "tt1254207", src);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        testPlay(intent);
    }

    private void focusCard(int pos, int tries) {
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.requestFocus();
            else if (tries < 10) list.postDelayed(() -> focusCard(pos, tries + 1), 30);
        });
    }

    private static final int ART_H = 124; // 220 dp de largura em 16:9

    /** imagem com a ponta de cima esmaecida (some para a cor do cartão) */
    static final class FadeImage extends android.widget.ImageView {
        private final android.graphics.Paint mask = new android.graphics.Paint();
        FadeImage(Context c) {
            super(c);
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[]{0x00000000, 0xE6000000, 0xFF000000}, new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            int sc = cv.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.onDraw(cv);
            cv.drawRect(0, 0, getWidth(), getHeight(), mask);
            cv.restoreToCount(sc);
        }
    }

    private JSONObject artData; // data/service-art.json: backdrops por serviço (renovados todo dia)

    /** coloca o backdrop do dia no cartão (a imagem muda de um dia para o outro, entre as 4 do serviço) */
    private void bindArt(Cards.CH h, String serviceId) {
        if (artData == null) return;
        org.json.JSONArray a = artData.optJSONArray(serviceId);
        if (a == null || a.length() == 0) { h.artUrl = ""; h.art.setImageDrawable(null); return; }
        JSONObject pick = a.optJSONObject((int) ((System.currentTimeMillis() / 86400000L) % a.length()));
        String url = pick != null ? pick.optString("url", "") : "";
        if (url.equals(h.artUrl)) return;
        h.artUrl = url;
        Context c = h.itemView.getContext();
        Ui.imageSized(h.art, url, Ui.dp(c, 220), Ui.dp(c, ART_H));
    }

    private final class Cards extends RecyclerView.Adapter<Cards.CH> {
        final class CH extends RecyclerView.ViewHolder {
            final ServiceIcon accent;
            final TextView name, tabs;
            final FadeImage art;
            String artUrl = "";
            CH(View v, ServiceIcon accent, TextView name, TextView tabs, FadeImage art) { super(v); this.accent = accent; this.name = name; this.tabs = tabs; this.art = art; }
        }

        @NonNull @Override
        public CH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 220), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
            f.setLayoutParams(lp);
            f.setForeground(Ui.focusRing(c, 20));
            f.setFocusable(true);
            f.setClickable(true);
            // imagem de fundo (backdrop de um título em alta) bem escura, por baixo do símbolo e do nome
            f.setClipToOutline(true);
            final float radius = Ui.dp(c, 20);
            f.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
            // a imagem 16:9 aparece inteira, na largura do cartão (220 x 124), na parte de baixo e esmaecendo para cima
            FadeImage art = new FadeImage(c);
            art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            art.setAlpha(0.55f);
            f.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, ART_H), Gravity.BOTTOM));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            ServiceIcon accent = new ServiceIcon(c);
            col.addView(accent, new LinearLayout.LayoutParams(Ui.dp(c, 78), Ui.dp(c, 78)));
            col.addView(Ui.space(c, 1, 18));
            TextView name = Ui.text(c, "", 31, false, Ui.TEXT);
            outfit(name, 500);
            name.setLetterSpacing(0.005f);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(Ui.space(c, 1, 12));
            TextView tabs = Ui.text(c, "", 14, false, 0xA6FFFFFF);
            outfit(tabs, 400);
            tabs.setGravity(Gravity.CENTER);
            tabs.setMaxLines(3);
            tabs.setEllipsize(TextUtils.TruncateAt.END);
            tabs.setLineSpacing(0, 1.2f);
            col.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            cl.topMargin = Ui.dp(c, 70); // símbolo e nome na mesma altura em todos os cartões
            f.addView(col, cl);

            CH h = new CH(f, accent, name, tabs, art);
            // selecionado: cresce suavemente e o fundo da tela ganha a cor do serviço
            f.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                int p = h.getBindingAdapterPosition();
                if (has && p >= 0) paintBackground(Services.ALL.get(p % N));
            });
            f.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p < 0) return;
                Intent i = new Intent(TvHomeActivity.this, TvCatalogActivity.class);
                i.putExtra("service", Services.ALL.get(p % N).id);
                startActivity(i);
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull CH h, int position) {
            Services.Service s = Services.ALL.get(position % N);
            int color = Ui.parseColor(s.color, 0xFFFFFFFF);
            Context c = h.itemView.getContext();
            int color2 = s.id.equals("globoplay") ? 0xFF3ECF6B : color;
            int color1 = s.id.equals("globoplay") ? 0xFFF5C518 : color;
            h.itemView.setBackground(cardBackground(c, color1, color2));
            h.accent.set(s.id, color == 0xFFF5F5F7 ? Ui.TEXT : color);
            float sc = h.itemView.hasFocus() ? 1.08f : 1f;
            h.itemView.setScaleX(sc);
            h.itemView.setScaleY(sc);
            h.name.setText(s.name);
            h.name.getPaint().setShader(null);
            h.name.setTextColor(color);
            if (s.id.equals("globoplay")) {
                // nome em degradê amarelo → verde, como no celular
                float w = h.name.getPaint().measureText(s.name);
                h.name.post(() -> {
                    float start = (h.name.getWidth() - w) / 2f;
                    h.name.getPaint().setShader(new android.graphics.LinearGradient(start, 0, start + w, 0, color1, color2, android.graphics.Shader.TileMode.CLAMP));
                    h.name.invalidate();
                });
            }
            h.tabs.setVisibility(View.GONE); // sem o texto "Ver catálogo": só o símbolo e o nome
            bindArt(h, s.id);
        }

        @Override public int getItemCount() { return Integer.MAX_VALUE; }
    }

    /** fundo igual ao do celular: quase preto, com um brilho da cor no canto de cima e outro no canto de baixo */
    private static android.graphics.drawable.Drawable cardBackground(Context c, int color1, int color2) {
        float r = Ui.dp(c, 20);
        GradientDrawable base = Ui.round(0xFF0B0B0D, r);
        GradientDrawable glow1 = new GradientDrawable();
        glow1.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow1.setGradientCenter(0f, 0f);
        glow1.setGradientRadius(Ui.dp(c, 330));
        glow1.setColors(new int[]{alpha(color1, 0.34f), alpha(color1, 0.12f), 0x00000000});
        glow1.setCornerRadius(r);
        GradientDrawable glow2 = new GradientDrawable();
        glow2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow2.setGradientCenter(1f, 1f);
        glow2.setGradientRadius(Ui.dp(c, 300));
        glow2.setColors(new int[]{alpha(color2, 0.20f), 0x00000000});
        glow2.setCornerRadius(r);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0x00000000);
        border.setCornerRadius(r);
        border.setStroke(Ui.dp(c, 1), alpha(color1, 0.45f));
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{base, glow1, glow2, border});
    }

    private static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** fundo da tela na cor do serviço selecionado (troca suave) */
    private void paintBackground(Services.Service s) {
        // o mesmo visual dos cartões, na tela inteira: quase preto com brilho da cor no canto de cima
        // e outro mais fraco no canto de baixo (a Globoplay mistura amarelo e verde)
        if (s.id.equals(bgId)) return;
        bgId = s.id;
        boolean globo = s.id.equals("globoplay");
        int c1 = globo ? 0xFFF5C518 : Ui.parseColor(s.color, 0xFFFFFFFF);
        int c2 = globo ? 0xFF3ECF6B : c1;
        float w = getResources().getDisplayMetrics().widthPixels;
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF0B0B0D);
        GradientDrawable g1 = new GradientDrawable();
        g1.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g1.setGradientCenter(0f, 0f);
        g1.setGradientRadius(w * 0.95f);
        g1.setColors(new int[]{alpha(c1, 0.34f), alpha(c1, 0.12f), 0x00000000});
        GradientDrawable g2 = new GradientDrawable();
        g2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g2.setGradientCenter(1f, 1f);
        g2.setGradientRadius(w * 0.75f);
        g2.setColors(new int[]{alpha(c2, 0.20f), 0x00000000});
        android.graphics.drawable.Drawable next = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{base, g1, g2});
        // a camada que estava atrás recebe o novo fundo, sobe por cima da atual e aparece suavemente (esmaecer pela placa de vídeo: leve)
        View incoming = bgBack, old = bgFront;
        incoming.animate().cancel();
        old.animate().cancel();
        old.setAlpha(1f);
        incoming.setBackground(next);
        incoming.setAlpha(0f);
        stage.bringChildToFront(incoming);
        stage.bringChildToFront(rootView);
        incoming.animate().alpha(1f).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        bgFront = incoming;
        bgBack = old;
        bgNow = next;
    }

    /** aplica a fonte Outfit com o peso pedido (a fonte é variável: o peso vai por "wght") */
    static void outfit(TextView t, int weight) {
        t.setTypeface(font(t.getContext(), weight));
        if (android.os.Build.VERSION.SDK_INT >= 26) t.setFontVariationSettings("'wght' " + weight);
    }

    /** fonte moderna (Outfit, a mesma do celular) */
    static android.graphics.Typeface font(Context c, int weight) {
        try {
            android.graphics.Typeface t = androidx.core.content.res.ResourcesCompat.getFont(c, io.github.gabrielxyz404.gabiflix.R.font.outfit);
            if (t == null) return android.graphics.Typeface.DEFAULT_BOLD;
            if (android.os.Build.VERSION.SDK_INT >= 28) return android.graphics.Typeface.create(t, weight, false);
            return android.graphics.Typeface.create(t, weight >= 700 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        } catch (Exception e) { return weight >= 700 ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT; }
    }

    /** botão redondo só com ícone (o nome aparece por baixo quando selecionado) */
    private View iconButton(String icon, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle = new FrameLayout(this);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = new GradientDrawable(); on.setShape(GradientDrawable.OVAL); on.setColor(0xFFFFFFFF);
        GradientDrawable off = new GradientDrawable(); off.setShape(GradientDrawable.OVAL); off.setColor(0x33FFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        box.setFocusable(true);
        box.setClickable(true);
        circle.setBackground(bg);
        circle.setDuplicateParentStateEnabled(true);
        ServiceIcon ic = new ServiceIcon(this);
        ic.set(icon, 0xFFFFFFFF);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER);
        circle.addView(ic, il);
        box.addView(circle, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView t = Ui.text(this, label, 12, true, Ui.TEXT);
        t.setVisibility(View.INVISIBLE);
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(t);
        box.setOnFocusChangeListener((v, has) -> {
            ic.set(icon, has ? 0xFF000000 : 0xFFFFFFFF);
            t.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        return box;
    }

    private void onAccount() {
        if (!Stremio.loggedIn(this)) { startActivity(new Intent(this, TvLoginActivity.class)); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Stremio.email(this))
                .setItems(new String[]{"Sair da conta"}, (d, w) -> Stremio.logout(this))
                .setNegativeButton("Fechar", null)
                .show();
    }
}
