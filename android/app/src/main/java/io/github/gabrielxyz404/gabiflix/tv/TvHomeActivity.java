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

/** Tela inicial da TV: os serviços em cartões altos, lado a lado, com rolagem infinita para a direita e a esquerda. */
public class TvHomeActivity extends TvBase {

    private TextView account;
    private RecyclerView list;
    private static final int N = Services.ALL.size();
    private static final int MIDDLE = (Integer.MAX_VALUE / 2) - ((Integer.MAX_VALUE / 2) % Services.ALL.size());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 30));

        // topo: nome, pesquisar e conta
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        TextView logo = Ui.text(this, "GabiFlix", 26, true, Ui.TEXT);
        top.addView(logo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView search = Ui.button(this, "Pesquisar", 0xFF232327, Ui.TEXT);
        search.setOnClickListener(v -> startActivity(new Intent(this, TvSearchActivity.class)));
        top.addView(search);
        top.addView(Ui.space(this, 12, 1));
        TextView update = Ui.button(this, "Atualizar", 0xFF232327, Ui.TEXT);
        update.setOnClickListener(v -> { Updater.run(this); prefetchSoon(); });
        top.addView(update);
        top.addView(Ui.space(this, 12, 1));
        account = Ui.button(this, "Entrar", 0xFF232327, Ui.TEXT);
        account.setOnClickListener(v -> onAccount());
        top.addView(account);
        root.addView(top);

        TextView hint = Ui.text(this, "Escolha um catálogo", 16, false, Ui.MUTED);
        hint.setPadding(Ui.dp(this, 34), Ui.dp(this, 22), 0, Ui.dp(this, 10));
        root.addView(hint);

        // cartões dos serviços (infinitos: depois do último vem o primeiro)
        list = PosterAdapter.list(this);
        list.setPadding(Ui.dp(this, 24), Ui.dp(this, 6), Ui.dp(this, 24), Ui.dp(this, 6));
        list.setLayoutManager(new PosterAdapter.Linear(this, RecyclerView.HORIZONTAL));
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(N + 2);
        list.setAdapter(new Cards());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ((PosterAdapter.Linear) list.getLayoutManager()).scrollToPositionWithOffset(MIDDLE, 0);
        focusCard(MIDDLE, 0);
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

    private final class Cards extends RecyclerView.Adapter<Cards.CH> {
        final class CH extends RecyclerView.ViewHolder {
            final View accent;
            final TextView name, tabs;
            CH(View v, View accent, TextView name, TextView tabs) { super(v); this.accent = accent; this.name = name; this.tabs = tabs; }
        }

        @NonNull @Override
        public CH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 220), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(Ui.dp(c, 9), Ui.dp(c, 6), Ui.dp(c, 9), Ui.dp(c, 6));
            f.setLayoutParams(lp);
            f.setForeground(Ui.focusRing(c, 20));
            f.setFocusable(true);
            f.setClickable(true);
            TvBase.focusGrow(f, 1.04f);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            View accent = new View(c);
            col.addView(accent, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 5)));
            col.addView(Ui.space(c, 1, 18));
            TextView name = Ui.text(c, "", 28, true, Ui.TEXT);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(Ui.space(c, 1, 12));
            TextView tabs = Ui.text(c, "", 14, false, 0xB3FFFFFF);
            tabs.setGravity(Gravity.CENTER);
            tabs.setMaxLines(3);
            tabs.setEllipsize(TextUtils.TruncateAt.END);
            tabs.setLineSpacing(0, 1.2f);
            col.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            CH h = new CH(f, accent, name, tabs);
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
            h.accent.setBackground(s.id.equals("globoplay")
                    ? gradientBar(c, color1, color2)
                    : Ui.round(color, Ui.dp(c, 3)));
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
            h.tabs.setText("Ver catálogo");
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

    private static GradientDrawable gradientBar(Context c, int a, int b) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{a, b});
        g.setCornerRadius(Ui.dp(c, 3));
        return g;
    }

    private static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** a cor do serviço um pouco mais clara, para o nome ler bem no fundo escuro */
    private static int brighten(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = r + (255 - r) / 4; g = g + (255 - g) / 4; b = b + (255 - b) / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        String email = Stremio.email(this);
        account.setText(email != null ? "Conta" : "Entrar");
    }

    private void onAccount() {
        if (!Stremio.loggedIn(this)) { startActivity(new Intent(this, TvLoginActivity.class)); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Stremio.email(this))
                .setItems(new String[]{"Sair da conta"}, (d, w) -> {
                    Stremio.logout(this);
                    account.setText("Entrar");
                })
                .setNegativeButton("Fechar", null)
                .show();
    }
}
