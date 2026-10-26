package io.github.gabrielxyz404.gabiflix.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Janela de fontes simples para a TV: em cima os provedores (Torrentio, TorrentsDB…),
 * em baixo as fontes desse provedor com o serviço (Real-Debrid / TorBox) e a qualidade.
 * Torrents sem Real-Debrid/TorBox não aparecem (não tocam).
 */
final class SourcePicker {

    interface OnPick { void pick(Stremio.Source s); }

    /** uma fonte já "traduzida" para mostrar */
    static final class Item {
        Stremio.Source src;
        String service = "";   // RD, TB, …
        boolean cached;         // já está pronto no serviço (toca na hora)
        String quality = "";    // 4K, 1080p, 720p…
        int qRank;
        double sizeGb;
        boolean pt, hdr;
        String file = "";
    }

    private static final Pattern SVC = Pattern.compile("\\[(RD|TB|AD|PM|DL|OC|ED|PKP)\\s*(\\+|⚡|⬇️|download)?\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile("([\\d]+[.,]?[\\d]*)\\s*(GB|GiB|MB|MiB)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PT = Pattern.compile("🇧🇷|🇵🇹|\\bdublado\\b|\\bdual\\b|pt[-_. ]?br|\\bportugu", Pattern.CASE_INSENSITIVE);

    static Item parse(Stremio.Source s) {
        Item it = new Item();
        it.src = s;
        String all = (s.name + " " + s.title);
        String low = all.toLowerCase(Locale.ROOT);
        Matcher m = SVC.matcher(all);
        if (m.find()) {
            it.service = m.group(1).toUpperCase(Locale.ROOT);
            String flag = m.group(2);
            // só "⬇️"/"download" quer dizer que ainda não está pronto no serviço
            it.cached = flag == null || flag.equals("+") || flag.equals("⚡");
        } else {
            it.cached = true;
            if (low.contains("real-debrid") || low.contains("realdebrid")) it.service = "RD";
            else if (low.contains("torbox")) it.service = "TB";
        }
        if (low.contains("2160p") || low.contains("4k") || low.contains("uhd")) { it.quality = "4K"; it.qRank = 4; }
        else if (low.contains("1080p")) { it.quality = "1080p"; it.qRank = 3; }
        else if (low.contains("720p")) { it.quality = "720p"; it.qRank = 2; }
        else if (low.contains("480p") || low.contains("sd")) { it.quality = "SD"; it.qRank = 1; }
        Matcher z = SIZE.matcher(s.title);
        if (z.find()) {
            try {
                double v = Double.parseDouble(z.group(1).replace(',', '.'));
                it.sizeGb = z.group(2).toUpperCase(Locale.ROOT).startsWith("M") ? v / 1024 : v;
            } catch (Exception ignored) {}
        }
        it.pt = PT.matcher(all).find();
        it.hdr = low.contains("hdr") || low.contains("dolby vision") || low.contains(" dv ");
        String first = s.title.split("\n")[0].trim();
        it.file = first;
        return it;
    }

    static String serviceName(String code) {
        switch (code) {
            case "RD": return "Real-Debrid";
            case "TB": return "TorBox";
            case "AD": return "AllDebrid";
            case "PM": return "Premiumize";
            case "DL": return "Debrid-Link";
            default: return code.isEmpty() ? "Direto" : code;
        }
    }

    private final Activity a;
    private final Dialog d;
    private final LinearLayout tabs;
    private final TextView head;
    private final Map<String, List<Item>> byAddon = new LinkedHashMap<>();
    private final Map<String, TextView> tabViews = new LinkedHashMap<>();
    private final Adapter adapter = new Adapter();
    private final RecyclerView list;
    private String current;
    private boolean focusedOnce;
    private final OnPick onPick;

    SourcePicker(Activity a, String title, OnPick onPick) {
        this.a = a;
        this.onPick = onPick;
        // ocupa a tela toda (fundo escurecido) com a janela quadrada no meio: sem cortes nem posições estranhas
        d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 16));
        box.setBackground(Ui.round(0xFF141417, Ui.dp(a, 18)));

        TextView name = Ui.text(a, title, 20, true, Ui.TEXT);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);
        box.addView(Ui.space(a, 1, 4));
        head = Ui.text(a, "Procurando fontes…", 14, false, Ui.MUTED);
        box.addView(head);
        box.addView(Ui.space(a, 1, 14));

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabs);
        box.addView(hs);
        box.addView(Ui.space(a, 1, 12));

        list = PosterAdapter.list(a);
        list.setLayoutManager(new PosterAdapter.Linear(a, RecyclerView.VERTICAL));
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 330)));
        android.widget.FrameLayout dim = new android.widget.FrameLayout(a);
        dim.setBackgroundColor(0xCC000000);
        dim.addView(box, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 720), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(dim);
    }

    void show() { d.show(); }
    boolean showing() { return d.isShowing(); }
    void dismiss() { if (d.isShowing()) d.dismiss(); }
    void onDismiss(Runnable r) { d.setOnDismissListener(x -> r.run()); }

    /** chegaram mais fontes (os addons respondem um a um) */
    void update(List<Stremio.Source> all, boolean finished) {
        try { doUpdate(all, finished); }
        catch (Exception e) { head.setText("Erro ao mostrar as fontes: " + e); }
    }

    private void doUpdate(List<Stremio.Source> all, boolean finished) {
        byAddon.clear();
        for (Stremio.Source s : all) {
            if (s.torrent || s.url == null) continue; // sem Real-Debrid/TorBox não toca
            Item it = parse(s);
            List<Item> l = byAddon.get(s.addon);
            if (l == null) byAddon.put(s.addon, l = new ArrayList<>());
            l.add(it);
        }
        for (List<Item> l : byAddon.values()) l.sort((x, y) -> {
            if (x.cached != y.cached) return x.cached ? -1 : 1;
            if (x.pt != y.pt) return x.pt ? -1 : 1; // em português primeiro
            if (x.qRank != y.qRank) return y.qRank - x.qRank;
            return Double.compare(y.sizeGb, x.sizeGb);
        });
        int total = 0;
        for (List<Item> l : byAddon.values()) total += l.size();
        if (total == 0) head.setText(finished ? "Nenhuma fonte disponível. Verifique o Real-Debrid/TorBox nos addons." : "Procurando fontes…");
        else head.setText((finished ? "" : "Procurando… ") + total + (total == 1 ? " fonte" : " fontes") + " · escolha o provedor em cima");

        // abas: só acrescenta as novas e atualiza os números (não mexe no que está selecionado)
        for (Map.Entry<String, List<Item>> e : byAddon.entrySet()) {
            TextView t = tabViews.get(e.getKey());
            if (t == null) {
                String key = e.getKey();
                t = Ui.pill(a, "");
                t.setTextSize(15);
                // muda a lista só depois (o foco pode mudar enquanto a lista se redesenha)
                t.setOnFocusChangeListener((v, has) -> { if (has) v.post(() -> { if (v.hasFocus()) select(key); }); });
                t.setOnClickListener(v -> focusFirstSource());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(a, 10);
                tabs.addView(t, lp);
                tabViews.put(key, t);
            }
            t.setText(e.getKey() + "  " + e.getValue().size());
        }
        if (current == null && !byAddon.isEmpty()) select(byAddon.keySet().iterator().next());
        else if (current != null) {
            List<Item> l = byAddon.get(current);
            if (list.isComputingLayout()) list.post(() -> adapter.set(l)); else adapter.set(l);
        }
        if (!focusedOnce && adapter.getItemCount() > 0) { focusedOnce = true; focusFirstSource(); }
    }

    private void select(String key) {
        if (list.isComputingLayout()) { list.post(() -> select(key)); return; }
        if (key.equals(current) && adapter.getItemCount() > 0) return;
        current = key;
        for (Map.Entry<String, TextView> e : tabViews.entrySet()) e.getValue().setAlpha(e.getKey().equals(key) ? 1f : 0.55f);
        adapter.set(byAddon.get(key));
        list.scrollToPosition(0);
    }

    private void focusFirstSource() {
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.requestFocus();
        });
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.H> {
        final List<Item> items = new ArrayList<>();
        void set(List<Item> l) { items.clear(); if (l != null) items.addAll(l); notifyDataSetChanged(); }

        final class H extends RecyclerView.ViewHolder {
            final TextView svc, quality, info, file;
            H(View v, TextView s, TextView q, TextView i, TextView f) { super(v); svc = s; quality = q; info = i; file = f; }
        }

        @NonNull @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
            row.setLayoutParams(lp);
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
            StateListDrawable s = new StateListDrawable();
            GradientDrawable on = Ui.round(0xFF2C2C32, Ui.dp(c, 10));
            on.setStroke(Ui.dp(c, 3), 0xFFFFFFFF);
            s.addState(new int[]{android.R.attr.state_focused}, on);
            s.addState(new int[]{}, Ui.round(0xFF1E1E22, Ui.dp(c, 10)));
            row.setBackground(s);
            row.setFocusable(true);
            row.setClickable(true);

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView svc = badge(c);
            top.addView(svc);
            top.addView(Ui.space(c, 8, 1));
            TextView q = badge(c);
            top.addView(q);
            top.addView(Ui.space(c, 12, 1));
            TextView info = Ui.text(c, "", 15, true, Ui.TEXT);
            top.addView(info);
            row.addView(top);
            TextView file = Ui.text(c, "", 12, false, 0x80FFFFFF);
            file.setMaxLines(1);
            file.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            file.setPadding(0, Ui.dp(c, 6), 0, 0);
            row.addView(file);

            H h = new H(row, svc, q, info, file);
            row.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && p < items.size()) { d.dismiss(); onPick.pick(items.get(p).src); }
            });
            return h;
        }

        private TextView badge(Context c) {
            TextView t = Ui.text(c, "", 13, true, Ui.TEXT);
            t.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
            return t;
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            Item it = items.get(position);
            Context c = h.itemView.getContext();
            String svc = serviceName(it.service);
            h.svc.setText(svc);
            int col = it.service.equals("RD") ? 0xFF1F7A3A : it.service.equals("TB") ? 0xFF5B3FC4 : 0xFF3A3A40;
            h.svc.setBackground(Ui.round(col, Ui.dp(c, 6)));
            h.quality.setText(it.quality.isEmpty() ? "?" : it.quality);
            h.quality.setBackground(Ui.round(it.qRank >= 4 ? 0xFFB8860B : it.qRank == 3 ? 0xFF2F6FD0 : 0xFF4A4A50, Ui.dp(c, 6)));
            List<String> parts = new ArrayList<>();
            if (it.sizeGb > 0) parts.add(String.format(Locale.ROOT, "%.1f GB", it.sizeGb));
            if (it.hdr) parts.add("HDR");
            if (it.pt) parts.add("🇧🇷 Português");
            parts.add(it.cached ? "pronto" : "pode demorar a começar");
            h.info.setText(TextUtils.join("  ·  ", parts));
            h.file.setText(it.file);
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
