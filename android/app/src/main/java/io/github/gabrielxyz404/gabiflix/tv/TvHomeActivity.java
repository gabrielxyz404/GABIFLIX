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
    private int lastFocused = MIDDLE;

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
            final GradientDrawable bg;
            final ServiceIcon accent;
            final TextView name, tabs;
            CH(View v, GradientDrawable bg, ServiceIcon accent, TextView name, TextView tabs) { super(v); this.bg = bg; this.accent = accent; this.name = name; this.tabs = tabs; }
        }

        @NonNull @Override
        public CH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 220), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(Ui.dp(c, 9), Ui.dp(c, 6), Ui.dp(c, 9), Ui.dp(c, 6));
            f.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0});
            bg.setCornerRadius(Ui.dp(c, 20));
            f.setBackground(bg);
            f.setForeground(Ui.focusRing(c, 20));
            f.setFocusable(true);
            f.setClickable(true);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            ServiceIcon accent = new ServiceIcon(c);
            col.addView(accent, new LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 84)));
            col.addView(Ui.space(c, 1, 20));
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

            CH h = new CH(f, bg, accent, name, tabs);
            f.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p < 0) return;
                lastFocused = p;
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
            h.bg.setColors(new int[]{Ui.tint(color, 0.42f), Ui.tint(color, 0.16f), Ui.tint(color, 0.08f)});
            h.bg.setStroke(Ui.dp(h.itemView.getContext(), 1), Ui.tint(color, 0.5f));
            h.accent.set(s.id, color == 0xFFF5F5F7 ? Ui.TEXT : brighten(color));
            h.name.setText(s.name);
            h.name.setTextColor(color == 0xFFF5F5F7 ? Ui.TEXT : brighten(color));
            h.tabs.setText(TextUtils.join("  ·  ", s.tabs));
        }

        @Override public int getItemCount() { return Integer.MAX_VALUE; }
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
