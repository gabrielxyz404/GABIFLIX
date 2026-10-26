package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.app.Dialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Janela de opções da TV (no lugar das caixas cinzentas do Android):
 * fundo escurecido, cartão com o pôster, o nome e botões grandes. Entra com um pequeno zoom.
 */
final class TvSheet {

    private final Dialog d;
    private final LinearLayout buttons;
    private final Activity a;
    private TextView first;

    TvSheet(Activity a, JSONObject item, String subtitle) {
        this.a = a;
        d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(0xCC000000);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(Ui.dp(a, 26), Ui.dp(a, 26), Ui.dp(a, 30), Ui.dp(a, 26));
        card.setBackground(Ui.round(0xFF16161A, Ui.dp(a, 22)));

        // pôster
        String poster = item.optString("poster", "");
        if (poster.isEmpty()) poster = "https://images.metahub.space/poster/medium/" + item.optString(item.has("_id") ? "_id" : "id") + "/img";
        FrameLayout pf = new FrameLayout(a);
        pf.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 12)));
        pf.setClipToOutline(true);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pf.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.image(img, poster);
        card.addView(pf, new LinearLayout.LayoutParams(Ui.dp(a, 120), Ui.dp(a, 180)));
        card.addView(Ui.space(a, 24, 1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(a, item.optString("name"), 24, false, Ui.TEXT);
        TvHomeActivity.outfit(title, 600);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.text(a, subtitle, 15, false, Ui.MUTED);
            TvHomeActivity.outfit(sub, 400);
            sub.setPadding(0, Ui.dp(a, 6), 0, 0);
            col.addView(sub);
        }
        col.addView(Ui.space(a, 1, 18));
        buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.VERTICAL);
        col.addView(buttons);
        card.addView(col, new LinearLayout.LayoutParams(Ui.dp(a, 380), ViewGroup.LayoutParams.WRAP_CONTENT));

        dim.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(dim);

        // entrada: o fundo escurece e o cartão cresce um pouco
        dim.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        d.setOnShowListener(x -> {
            dim.animate().alpha(1f).setDuration(160).start();
            card.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            if (first != null) first.requestFocus();
        });
    }

    /** acrescenta um botão (o primeiro fica selecionado) */
    TvSheet action(String label, Runnable onClick) {
        TextView b = Ui.pill(a, label);
        TvHomeActivity.outfit(b, 600);
        b.setOnClickListener(v -> { d.dismiss(); if (onClick != null) onClick.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        buttons.addView(b, lp);
        if (first == null) first = b;
        return this;
    }

    void show() { d.show(); }
}
