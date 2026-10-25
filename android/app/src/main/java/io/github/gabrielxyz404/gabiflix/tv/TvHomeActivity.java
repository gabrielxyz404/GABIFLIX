package io.github.gabrielxyz404.gabiflix.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tela inicial da TV: os serviços em barras verticais, de uma ponta à outra. */
public class TvHomeActivity extends TvBase {

    private TextView account;
    private View firstBar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 18), Ui.dp(this, 28), Ui.dp(this, 26));

        // topo: nome, pesquisar e conta
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
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
        root.addView(Ui.space(this, 1, 18));

        // barras dos serviços
        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        for (Services.Service s : Services.ALL) {
            View bar = bar(s);
            if (firstBar == null) firstBar = bar;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            bars.addView(bar, lp);
        }
        root.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        focusLater(firstBar);
    }

    private View bar(Services.Service s) {
        int color = Ui.parseColor(s.color, 0xFFFFFFFF);
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = Ui.round(Ui.tint(color, 0.2f), Ui.dp(this, 14));
        bg.setStroke(Ui.dp(this, 1), Ui.tint(color, 0.45f));
        f.setBackground(bg);
        f.setForeground(Ui.focusRing(this, 14));
        f.setFocusable(true);
        f.setClickable(true);
        TextView name = Ui.text(this, s.name, 24, true, color);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        name.setRotation(-90);
        f.addView(name, new FrameLayout.LayoutParams(Ui.dp(this, 420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        f.setOnClickListener(v -> {
            Intent i = new Intent(this, TvCatalogActivity.class);
            i.putExtra("service", s.id);
            startActivity(i);
        });
        return f;
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
