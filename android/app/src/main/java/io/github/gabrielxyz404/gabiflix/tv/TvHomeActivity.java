package io.github.gabrielxyz404.gabiflix.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tela inicial da TV, desenho "Prateleira": em cima o título em destaque do serviço selecionado (imagem, nome e sinopse);
 * embaixo a fila de serviços, com o selecionado maior e com contorno branco. Navega-se só com as setas e o OK.
 * Leve para a TV de 32 bits: uma só imagem grande de cada vez (só depois de o foco parar um instante), sem camadas de placa de vídeo
 * e só o aumento do cartão selecionado como animação.
 */
public class TvHomeActivity extends TvBase {

    private static final int BG = 0xFF07070A;
    private static final int N = Services.ALL.size();

    private View accountBtn;
    private RecyclerView list;
    private ImageView heroImg;
    private TextView heroTag, heroTitle, heroText;
    private int lastFocused = 0;          // serviço selecionado por último (o foco volta a ele depois dos ícones do topo)
    private Runnable heroImageTask;
    private String heroImageUrl = "";

    /** o título em destaque de um serviço */
    private static final class Feature { String title = "", text = "", image = "", info = ""; }
    private final ConcurrentHashMap<String, Feature> features = new ConcurrentHashMap<>();
    private final java.util.Set<String> loading = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** cores de cada serviço: {texto, fundo do cartão} (as mesmas do desenho aprovado) */
    static int[] look(String id) {
        switch (id) {
            case "netflix": return new int[]{0xFFFF4D55, 0xFF2A0D10};
            case "disney": return new int[]{0xFF7DB4FF, 0xFF0D1A33};
            case "prime": return new int[]{0xFF4FD1E8, 0xFF0A2329};
            case "hbo": return new int[]{0xFFB98CFF, 0xFF1E1233};
            case "appletv": return new int[]{0xFFF4F4F6, 0xFF202027};
            case "paramount": return new int[]{0xFF6FA0FF, 0xFF0E1A38};
            case "globoplay": return new int[]{0xFFE3D25A, 0xFF26240C};
            case "clarovideo": return new int[]{0xFFFF6B5E, 0xFF2C110E};
            default: return new int[]{0xFFE7C07A, 0xFF2A2213};
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (io.github.gabrielxyz404.gabiflix.MainActivity.duplicateLauncher(this)) { finish(); return; }   // ícone tocado com o app (e o filme) já abertos: não cobre a tarefa
        new Thread(io.github.gabrielxyz404.gabiflix.Site::resolve).start(); // descobre já qual endereço do site responde
        getWindow().getDecorView().setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setClipChildren(false);

        // ---------- metade de cima: o destaque do serviço selecionado ----------
        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        heroImg = new ImageView(this);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroImg.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(heroImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // escurece a esquerda (onde está o texto) e a parte de baixo (onde começa a fila de serviços); estáticos, sem custo
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF07070A, 0xD907070A, 0x0007070A}));
        hero.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF07070A, 0x9907070A, 0x0007070A}));
        hero.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150), Gravity.BOTTOM));
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB307070A, 0x0007070A}));
        hero.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 100), Gravity.TOP));

        // topo: logo e nome à esquerda, ícones à direita
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 48), Ui.dp(this, 22), Ui.dp(this, 48), Ui.dp(this, 6));
        top.setClipChildren(false); // o ícone selecionado cresce sem ser cortado
        top.setClipToPadding(false);
        ImageView logo = new ImageView(this);
        logo.setImageResource(io.github.gabrielxyz404.gabiflix.R.mipmap.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        top.addView(Ui.space(this, 12, 1));
        TextView home = Ui.text(this, "FramePlay", 22, true, Ui.TEXT);
        outfit(home, 700);
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
        for (View v : new View[]{later, search, update, accountBtn}) { // para baixo, o foco volta ao serviço onde estava
            v.setOnKeyListener((x, code, ev) -> {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) { focusCard(lastFocused, 0); return true; }
                return false;
            });
        }
        hero.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // texto do destaque
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        heroTag = Ui.text(this, "", 11, true, 0xFFFF4D55);
        outfit(heroTag, 600);
        heroTag.setLetterSpacing(0.12f);
        heroTitle = Ui.text(this, "", 36, true, Ui.TEXT);
        outfit(heroTitle, 700);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroText = Ui.text(this, "", 13, false, 0xFFC4C4CC);
        outfit(heroText, 400);
        heroText.setMaxLines(2);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setLineSpacing(0, 1.25f);
        info.addView(heroTag);
        info.addView(Ui.space(this, 1, 8));
        info.addView(heroTitle);
        info.addView(Ui.space(this, 1, 10));
        info.addView(heroText);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Ui.dp(this, 470), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        il.setMargins(Ui.dp(this, 48), 0, 0, Ui.dp(this, 22));
        hero.addView(info, il);
        root.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- metade de baixo: os serviços ----------
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        TextView head = Ui.text(this, "Serviços", 14, true, 0xFFC4C4CC);
        outfit(head, 600);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMargins(Ui.dp(this, 48), 0, 0, 0);
        shelf.addView(head, hl);
        list = PosterAdapter.list(this);
        list.setClipChildren(false);
        list.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        PosterAdapter.Linear hlm = new PosterAdapter.Linear(this, RecyclerView.HORIZONTAL);
        hlm.smooth = false;
        list.setLayoutManager(hlm);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(N + 2);
        list.setAdapter(new Cards());
        shelf.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        showHero(0, true);
        focusCard(0, 0);
        prefetch();
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

    /** baixa já os dados do Início de cada catálogo (ao entrar, abre na hora) e o título em destaque de cada serviço */
    private void prefetch() {
        Net.async(() -> {
            for (Services.Service s : Services.ALL) {
                try {
                    if (s.id.equals("cinemateca")) { Stremio.data("cine-cinemas"); Stremio.data("cine-classicos"); }
                    else Stremio.data(s.id + "-home");
                    if (s.id.equals("netflix")) { Stremio.data("netflix-top10-br-series"); Stremio.data("netflix-top10-br-movie"); }
                    features.put(s.id, loadFeature(s));
                } catch (Exception ignored) {}
                final int idx = Services.ALL.indexOf(s);
                Net.MAIN.post(() -> { if (!isFinishing() && idx == lastFocused) showHero(idx, false); });
            }
            return true;
        }, (ok, e) -> {});
    }

    /** o primeiro título em alta do serviço, com a sinopse em português */
    private static Feature loadFeature(Services.Service s) throws Exception {
        boolean cine = s.id.equals("cinemateca");
        JSONArray arr = cine ? Stremio.data("cine-cinemas").optJSONArray("metas") : Stremio.data(s.id + "-home").optJSONArray("top10");
        if ((arr == null || arr.length() == 0) && !cine) arr = Stremio.data(s.id + "-home").optJSONArray("movie");
        Feature f = new Feature();
        if (arr == null) return f;
        for (int i = 0; i < arr.length() && i < 10; i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null || m.optString("name").isEmpty()) continue;
            String id = m.optString("id");
            String bg = m.optString("background", "");
            if (bg.isEmpty() && id.startsWith("tt")) bg = "https://images.metahub.space/background/medium/" + id + "/img";
            if (bg.isEmpty()) continue;
            f.title = m.optString("name");
            f.image = bg;
            f.info = m.optString("releaseInfo", "");
            String d = Stremio.ptDescription(id);
            f.text = d != null ? d : "";
            break;
        }
        return f;
    }

    /** mostra o destaque do serviço (o texto já; a imagem só depois de o foco parar um instante) */
    private void showHero(int pos, boolean now) {
        Services.Service s = Services.ALL.get(pos);
        int[] look = look(s.id);
        Feature f = features.get(s.id);
        boolean cine = s.id.equals("cinemateca");
        heroTag.setText(s.name.toUpperCase() + (cine ? "  ·  NOS CINEMAS" : "  ·  EM ALTA"));
        heroTag.setTextColor(look[0]);
        if (f != null && !f.title.isEmpty()) {
            heroTitle.setText(f.title);
            heroText.setText(f.text.isEmpty() ? f.info : f.text);
        } else {
            heroTitle.setText(s.name);
            heroText.setText(cine ? "Cinema, clássicos e o que não está nos streamings" : "Ver catálogo");
            if (f == null) loadFeatureNow(s, pos);
        }
        if (heroImageTask != null) list.removeCallbacks(heroImageTask);
        final String url = f != null ? f.image : "";
        if (url.isEmpty() || url.equals(heroImageUrl)) return;
        heroImageTask = () -> {
            if (isFinishing() || lastFocused != pos) return;
            heroImageUrl = url;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = Math.min(dm.widthPixels, 1280);
            int h = Math.round(w * (dm.heightPixels * 0.62f) / dm.widthPixels);
            Ui.imageSized(heroImg, url, w, h);
        };
        list.postDelayed(heroImageTask, now ? 0 : 150);
    }

    /** o destaque ainda não chegou (internet lenta): busca já este e mostra quando chegar, se o foco ainda estiver nele */
    private void loadFeatureNow(Services.Service s, int pos) {
        if (!loading.add(s.id)) return;
        Net.async(() -> loadFeature(s), (f, e) -> {
            loading.remove(s.id);
            if (f == null || isFinishing()) return;
            features.put(s.id, f);
            if (lastFocused == pos) showHero(pos, false);
        });
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
        String testSubs = i.getStringExtra("gfTestSubs"); // só para os testes: abre o player direto com legendas conhecidas (separadas por vírgula)
        if (testSubs != null) {
            String testRefs = i.getStringExtra("gfTestRefs"); // só para os testes: referências de tempo conhecidas (links, separados por vírgula)
            Intent pi = new Intent(this, io.github.gabrielxyz404.gabiflix.PlayerActivity.class).putExtra("url", url).putExtra("subs", testSubs.split(","));
            if (testRefs != null) pi.putExtra("gfTestRefs", testRefs.split(","));
            if (i.getExtras() != null) for (String k : i.getExtras().keySet()) if (k.startsWith("gf") && !k.startsWith("gfTest")) pi.putExtra(k, String.valueOf(i.getExtras().get(k))); // só para os testes da sincronização pelo áudio
            startActivity(pi);
            return;
        }
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

    private final class Cards extends RecyclerView.Adapter<Cards.CH> {
        final class CH extends RecyclerView.ViewHolder {
            final TextView name;
            final GradientDrawable fill;
            CH(View v, TextView name, GradientDrawable fill) { super(v); this.name = name; this.fill = fill; }
        }

        @NonNull @Override
        public CH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 80), Ui.dp(c, 76));
            lp.setMargins(Ui.dp(c, 7), Ui.dp(c, 16), Ui.dp(c, 7), Ui.dp(c, 16)); // espaço para o cartão crescer sem ser cortado
            f.setLayoutParams(lp);
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(Ui.dp(c, 12));
            f.setBackground(fill);
            f.setForeground(Ui.focusRing(c, 12));
            f.setFocusable(true);
            f.setClickable(true);
            TextView name = Ui.text(c, "", 12, true, Ui.TEXT);
            outfit(name, 700);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            f.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            CH h = new CH(f, name, fill);
            // selecionado: cresce (só escala, sem refazer o desenho) e o destaque de cima muda para o serviço
            f.setOnFocusChangeListener((v, has) -> {
                float sc = has ? 1.28f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(110).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                v.setTranslationZ(has ? Ui.dp(c, 8) : 0); // o selecionado fica por cima dos vizinhos
                int p = h.getBindingAdapterPosition();
                if (has && p >= 0) { lastFocused = p; showHero(p, false); }
            });
            // nas pontas da fila o foco fica no cartão (não salta para os ícones do topo)
            f.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int p = h.getBindingAdapterPosition();
                return (code == KeyEvent.KEYCODE_DPAD_LEFT && p == 0) || (code == KeyEvent.KEYCODE_DPAD_RIGHT && p == N - 1);
            });
            f.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p < 0) return;
                Intent i = new Intent(TvHomeActivity.this, TvCatalogActivity.class);
                i.putExtra("service", Services.ALL.get(p).id);
                startActivity(i);
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull CH h, int position) {
            Services.Service s = Services.ALL.get(position);
            int[] look = look(s.id);
            Context c = h.itemView.getContext();
            h.fill.setColor(look[1]);
            h.fill.setStroke(Ui.dp(c, 1), (look[0] & 0x00FFFFFF) | 0x4D000000);
            h.name.setText(s.name);
            h.name.setTextColor(look[0]);
            h.itemView.setContentDescription(s.name);
            float sc = h.itemView.hasFocus() ? 1.28f : 1f;
            h.itemView.setScaleX(sc);
            h.itemView.setScaleY(sc);
        }

        @Override public int getItemCount() { return N; }
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
