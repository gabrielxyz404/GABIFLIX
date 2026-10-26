package io.github.gabrielxyz404.gabiflix.tv;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/** Pesquisa de filmes e séries. */
public class TvSearchActivity extends TvBase {

    private EditText input;
    private TextView status;
    private PosterAdapter adapter;
    private RecyclerView grid;
    private int seq = 0;
    private final Runnable run = this::search;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 18), Ui.dp(this, 28), 0);

        input = new EditText(this);
        input.setHint("Pesquisar filmes e séries");
        input.setHintTextColor(0x80FFFFFF);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(20);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = Ui.round(0xFF232327, Ui.dp(this, 10));
        on.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, Ui.round(0xFF232327, Ui.dp(this, 10)));
        input.setBackground(bg);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                Net.MAIN.removeCallbacks(run);
                search();
                hideKeyboard();
                grid.post(() -> { if (adapter.getItemCount() > 0) grid.requestFocus(); });
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { Net.MAIN.removeCallbacks(run); Net.MAIN.postDelayed(run, 700); }
        });
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "", 16, false, Ui.MUTED);
        status.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 4));
        root.addView(status);

        FrameLayout box = new FrameLayout(this);
        grid = PosterAdapter.list(this);
        grid.setPadding(0, 0, 0, Ui.dp(this, 30));
        grid.setLayoutManager(new PosterAdapter.Grid(this, PosterAdapter.span(this, Ui.POSTER_W, 28)));
        adapter = new PosterAdapter(Ui.POSTER_W, Ui.POSTER_H);
        grid.setAdapter(adapter);
        box.addView(grid);
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        if (getIntent().getBooleanExtra("library", false)) { showLibrary(); return; }
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    // ---------- Mais Tarde (a lista da conta) ----------
    private boolean library;

    private void showLibrary() {
        library = true;
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        input.setVisibility(android.view.View.GONE);
        TextView title = Ui.text(this, "Mais Tarde", 26, true, Ui.TEXT);
        title.setTypeface(TvHomeActivity.font(this, 700));
        ((LinearLayout) input.getParent()).addView(title, 0);
        loadLibrary();
    }

    private void loadLibrary() {
        if (!Stremio.loggedIn(this)) { status.setText("Entre na sua conta para ver o Mais Tarde."); return; }
        status.setText("Carregando…");
        android.content.Context c = getApplicationContext();
        Net.async(() -> {
            java.util.List<org.json.JSONObject> out = new java.util.ArrayList<>();
            for (org.json.JSONObject it : Stremio.library(c, true).values()) {
                if (it.optBoolean("removed") || it.optBoolean("temp") || "other".equals(it.optString("type"))) continue;
                out.add(it);
            }
            out.sort((x, y) -> y.optString("_mtime").compareTo(x.optString("_mtime")));
            return out;
        }, (list, err) -> {
            if (list == null) { status.setText("Não foi possível carregar."); return; }
            adapter.set(list);
            status.setText(list.isEmpty() ? "Nada no Mais Tarde ainda. Na página de um filme, use \"+ Mais Tarde\"." : list.size() + (list.size() == 1 ? " título" : " títulos"));
            grid.post(() -> { RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0); if (h != null) h.itemView.requestFocus(); });
        });
    }

    private boolean resumedOnce;

    @Override
    protected void onResume() {
        super.onResume();
        if (library && resumedOnce) loadLibrary(); // tirou ou pôs algo na lista
        resumedOnce = true;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void search() {
        String q = input.getText().toString().trim();
        final int my = ++seq;
        if (q.length() < 2) { adapter.set(null); status.setText(""); return; }
        status.setText("Procurando…");
        Net.async(() -> Stremio.search(q), (list, err) -> {
            if (my != seq) return;
            adapter.set(list);
            status.setText(list == null || list.isEmpty() ? "Nada encontrado para \"" + q + "\"." : list.size() + " resultados");
        });
    }

    @Override
    protected void onDestroy() {
        Net.MAIN.removeCallbacks(run);
        super.onDestroy();
    }
}
