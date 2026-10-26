package io.github.gabrielxyz404.gabiflix.tv;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Entrar com a mesma conta do Stremio usada no celular (os addons e o Continuar assistindo vêm dela). */
public class TvLoginActivity extends TvBase {

    private EditText email, password;
    private TextView status, go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 30), Ui.dp(this, 26), Ui.dp(this, 30), Ui.dp(this, 26));
        box.setBackground(Ui.round(0xFF17171A, Ui.dp(this, 16)));
        TextView head = Ui.text(this, "Entrar na conta do Stremio", 24, false, Ui.TEXT);
        TvHomeActivity.outfit(head, 600);
        box.addView(head);
        box.addView(Ui.space(this, 1, 6));
        box.addView(Ui.text(this, "Use a mesma conta do celular: os addons e o Continuar assistindo vêm dela.", 14, false, Ui.MUTED));
        box.addView(Ui.space(this, 1, 18));
        email = field("E-mail", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        box.addView(email, fieldLp());
        box.addView(Ui.space(this, 1, 12));
        password = field("Senha", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v, id, ev) -> { if (id == EditorInfo.IME_ACTION_DONE) { go.requestFocus(); return false; } return false; });
        box.addView(password, fieldLp());
        box.addView(Ui.space(this, 1, 18));
        go = Ui.button(this, "Entrar", 0xFFFFFFFF, 0xFF000000);
        go.setOnClickListener(v -> login());
        box.addView(go);
        box.addView(Ui.space(this, 1, 10));
        status = Ui.text(this, "", 14, false, 0xFFFF8A80);
        box.addView(status);

        root.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        String saved = Stremio.email(this);
        if (saved != null) email.setText(saved);
        focusLater(email);
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x80FFFFFF);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = Ui.round(0xFF26262B, Ui.dp(this, 10));
        on.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, Ui.round(0xFF26262B, Ui.dp(this, 10)));
        e.setBackground(bg);
        return e;
    }

    private LinearLayout.LayoutParams fieldLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void login() {
        String e = email.getText().toString().trim(), p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) { status.setText("Preencha o e-mail e a senha."); return; }
        status.setTextColor(Ui.MUTED);
        status.setText("Entrando…");
        go.setEnabled(false);
        android.content.Context c = getApplicationContext();
        Net.async(() -> { Stremio.login(c, e, p); return true; }, (ok, err) -> {
            go.setEnabled(true);
            if (err != null) {
                status.setTextColor(0xFFFF8A80);
                status.setText(err.getMessage() != null ? err.getMessage() : "Não foi possível entrar.");
                return;
            }
            finish();
        });
    }
}
