package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ask AI, full screen: a chat with Gemini. The question (said holding the remote's mic button, or typed with the TV
 * keyboard), Gemini's answer in large type and its sources when grounding is on; follow-ups go in the box at the
 * bottom and keep the chat, New chat starts over. Back closes it. Built from
 * plain views (the TV keyboard, focus and scrolling come with them), in the home screen's fonts and colors.
 */
public final class AskActivity extends Activity {
    static final String MODE = "mode", QUESTION = "q";
    static final String VOICE = "voice", TYPE = "type";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean night;
    private int ink, dim;
    private EditText question;
    private TextView chip, title, status, answer;
    private LinearLayout sources, actions;
    private ScrollView scroll;
    private int asking;   // the latest question; older answers that arrive late are dropped
    private boolean waiting;
    private final List<String> chat = new ArrayList<>();   // user, model, user, model…

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        night = HomeData.get(this).isNight(System.currentTimeMillis());
        ink = night ? 0xFFF5F5F7 : 0xFF1D1D1F;
        dim = night ? 0xADFFFFFF : 0xFF6E6E73;
        setContentView(build());
        Intent in = getIntent();
        String q = in.getStringExtra(QUESTION);
        if (q != null && !q.trim().isEmpty()) {
            ask(q);
        } else if (VOICE.equals(in.getStringExtra(MODE))) {
            listen();
        } else {
            typeQuestion();
        }
    }

    @Override protected void onDestroy() {
        asking++;
        voice.destroy();
        super.onDestroy();
    }

    // MARK: - Layout (dp = the mockup's CSS px)

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float size, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                night ? new int[] {0xFF1C1C1E, 0xFF0B0B0D} : new int[] {0xFFF5F5F7, 0xFFE2E2E7}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(40), dp(80), dp(30));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chip = text(12, Ui.semibold, 0xFFFFFFFF);
        chip.setText("✦  " + Ui.tr("Pergunte à IA", "Ask AI", "Pregunta a la IA") + "  ·  " + Keys.model(this));
        chip.setPadding(dp(11), dp(6), dp(11), dp(6));
        GradientDrawable chipBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF5AC8FA, 0xFF7C8CFF, 0xFFB48CFF});
        chipBg.setCornerRadius(dp(12));
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(chip, chipLp);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        title = text(22, Ui.displaySemibold, ink);
        title.setText(Ui.tr("Nova conversa", "New chat", "Nuevo chat"));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(button(Ui.tr("Nova conversa", "New chat", "Nuevo chat"), v -> newChat()));
        LinearLayout.LayoutParams hdLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hdLp.topMargin = dp(10);
        col.addView(heading, hdLp);

        status = text(14, Ui.medium, dim);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(12);
        col.addView(status, sLp);

        scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setVerticalScrollBarEnabled(false);
        answer = text(18, Ui.medium, ink);
        answer.setLineSpacing(dp(8), 1);
        scroll.addView(answer, new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        aLp.topMargin = dp(12);
        col.addView(scroll, aLp);

        sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.HORIZONTAL);
        sources.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(sources, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(10);
        col.addView(actions, actLp);

        LinearLayout compose = new LinearLayout(this);
        compose.setOrientation(LinearLayout.HORIZONTAL);
        compose.setGravity(Gravity.CENTER_VERTICAL);
        question = new EditText(this);
        question.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        question.setTypeface(Ui.medium);
        question.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF1C1C1E, ink}));
        question.setHintTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF6E6E73, dim}));
        question.setHint(Ui.tr("Pergunte algo ou continue a conversa…", "Ask a question or follow up…", "Pregunta algo o sigue la conversación…"));
        question.setBackground(pill(false));
        question.setPadding(dp(18), dp(10), dp(18), dp(10));
        question.setSingleLine(true);
        question.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        question.setOnEditorActionListener((v, id, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || enter) {
                send();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        qLp.rightMargin = dp(12);
        compose.addView(question, qLp);
        compose.addView(button(Ui.tr("Enviar", "Send", "Enviar"), v -> send()));
        compose.addView(button(Ui.tr("Falar", "Speak", "Hablar"), v -> listen()));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(14);
        col.addView(compose, cLp);

        TextView hint = text(11, Ui.medium, night ? 0x99FFFFFF : 0xFF8E8E93);
        hint.setText(Ui.tr("Voltar para fechar", "Back to close", "Atrás para cerrar"));
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hLp.setMargins(0, 0, dp(80), dp(16));
        root.addView(hint, hLp);
        return root;
    }

    /** The home screen's pills: glass at rest, white when focused (the tvOS focus). */
    private StateListDrawable pill(boolean round) {
        GradientDrawable rest = new GradientDrawable(), focus = new GradientDrawable();
        rest.setColor(night ? 0x33FFFFFF : 0x8CFFFFFF);
        rest.setStroke(dp(1), night ? 0x24FFFFFF : 0xCCFFFFFF);
        focus.setColor(0xFFFFFFFF);
        float r = dp(round ? 15 : 17);
        rest.setCornerRadius(r);
        focus.setCornerRadius(r);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, focus);
        d.addState(new int[] {}, rest);
        return d;
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView b = text(13, Ui.semibold, ink);
        b.setText(label);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF1C1C1E, ink}));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setBackground(pill(true));
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** After an error: Try again (the same question, keeping the chat) when there's one, and Settings. */
    private void setActions(String retry) {
        actions.removeAllViews();
        if (retry != null) actions.addView(button(Ui.tr("Tentar de novo", "Try again", "Reintentar"), v -> ask(retry)));
        actions.addView(button(Ui.tr("Abrir Ajustes", "Open Settings", "Abrir Ajustes"), v -> openSettings()));
        actions.getChildAt(0).requestFocus();
    }

    // MARK: - Asking

    private void typeQuestion() {
        if (chat.isEmpty()) status.setText(Ui.tr("Pergunte qualquer coisa. Você pode continuar a conversa aqui.",
                "Ask anything. You can follow up here.", "Pregunta lo que quieras. Puedes seguir la conversación aquí."));
        question.requestFocus();
        question.setSelection(question.getText().length());
        main.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(question, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void newChat() {
        asking++;
        waiting = false;
        voice.cancel();
        chat.clear();
        title.setText(Ui.tr("Nova conversa", "New chat", "Nuevo chat"));
        answer.setText("");
        sources.removeAllViews();
        actions.removeAllViews();
        question.setText("");
        typeQuestion();
    }

    /** The box at the bottom: a follow-up in this chat (or the first question of a new one). */
    private void send() {
        ask(question.getText().toString());
    }

    private void ask(String asked) {
        final String q = asked.trim();
        if (q.isEmpty() || waiting) return;
        hideKeyboard();
        final int id = ++asking;
        waiting = true;
        final boolean grounded = Keys.grounding(this);
        title.setText(q);
        status.setText(grounded ? Ui.tr("Pesquisando no Google e escrevendo a resposta…", "Searching Google and writing an answer…", "Buscando en Google y escribiendo la respuesta…")
                : Ui.tr("Escrevendo a resposta…", "Writing an answer…", "Escribiendo la respuesta…"));
        sources.removeAllViews();
        actions.removeAllViews();
        scroll.requestFocus();
        final List<String> history = new ArrayList<>(chat);
        Context app = getApplicationContext();
        IO.execute(() -> {
            Gemini.Answer a = Gemini.ask(app, history, q);
            main.post(() -> {
                if (id != asking || isFinishing()) return;
                waiting = false;
                show(a, grounded, q);
            });
        });
    }

    private void show(Gemini.Answer a, boolean grounded, String q) {
        if ("no_key".equals(a.error)) {
            status.setText(Ui.tr("O Pergunte à IA precisa de uma chave do Gemini.\nColoque uma em Ajustes, ou do computador com um comando adb (veja o README).",
                    "Ask AI needs a Gemini API key.\nAdd one in Settings, or from your computer with one adb command (see the README).",
                    "Pregunta a la IA necesita una clave de Gemini.\nAgrégala en Ajustes, o desde la computadora con un comando adb (ver el README)."));
            setActions(null);
            return;
        }
        if (!a.error.isEmpty()) {
            status.setText(a.error);
            setActions(q);
            return;
        }
        status.setText("");
        question.setText("");
        chip.setText("✦  " + Ui.tr("Pergunte à IA", "Ask AI", "Pregunta a la IA") + "  ·  " + a.model);
        chat.add(q);
        chat.add(a.text);
        answer.setText(format());
        // Land on the newest turn: its "You ·" line at the top.
        final int top = chat.size() > 2 ? latestTop : 0;
        scroll.post(() -> scroll.scrollTo(0, answer.getLayout() == null ? 0 : answer.getLayout().getLineTop(answer.getLayout().getLineForOffset(top))));
        sources.removeAllViews();
        if (!a.sources.isEmpty()) {
            TextView label = text(12.5f, Ui.semibold, dim);
            label.setText(Ui.tr("Fontes  ", "Sources  ", "Fuentes  "));
            sources.addView(label);
            for (int i = 0; i < a.sources.size(); i++) {
                TextView s = text(13, Ui.medium, ink);
                s.setText((i + 1) + "  " + a.sources.get(i));
                s.setPadding(dp(13), dp(7), dp(13), dp(7));
                s.setBackground(pill(true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(10);
                sources.addView(s, lp);
            }
        } else if (grounded) {
            TextView none = text(12.5f, Ui.medium, dim);
            none.setText(Ui.tr("Sem fontes da web para esta resposta", "No web sources for this answer", "Sin fuentes web para esta respuesta"));
            sources.addView(none);
        }
        scroll.requestFocus();
    }

    private int latestTop;   // where the newest turn starts in the formatted chat

    /** The whole chat: each question small and dim ("You · …"), then its answer (format). */
    private CharSequence format() {
        SpannableStringBuilder b = new SpannableStringBuilder();
        String you = Ui.tr("Você", "You", "Tú") + " · ";
        for (int i = 0; i + 1 < chat.size(); i += 2) {
            if (b.length() > 0) b.append("\n\n");
            latestTop = b.length();
            if (chat.size() > 2) {
                int start = b.length();
                b.append(you).append(chat.get(i)).append("\n");
                b.setSpan(new AbsoluteSizeSpan(dp(15)), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new android.text.style.ForegroundColorSpan(dim), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            format(b, chat.get(i + 1));
        }
        return b;
    }

    /** One answer: the first line large, "- " lines as bullets. */
    private void format(SpannableStringBuilder b, String text) {
        boolean first = true;
        int begin = b.length();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            line = line.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            boolean bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ");
            if (bullet) line = "•  " + line.substring(2).trim();
            if (b.length() > begin) b.append("\n");
            int start = b.length();
            b.append(line);
            // A wrapped bullet's next lines start under its text, not under the dot.
            if (bullet) b.setSpan(new android.text.style.LeadingMarginSpan.Standard(0, Math.round(answer.getPaint().measureText("•  "))),
                    start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (first && !bullet) {
                b.setSpan(new AbsoluteSizeSpan(dp(21)), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new TypefaceSpan(Ui.semibold), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.append("\n");
            }
            first = false;
        }
    }

    // MARK: - Voice: hold the remote's mic button (Voice)

    /** The on-screen Speak button (and Ask AI from home): listens until you stop talking. */
    private void listen() {
        hideKeyboard();
        voice.start(false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (voice.onKey(e)) return true;   // the remote's mic button: hold to talk
        return super.dispatchKeyEvent(e);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        voice.onPermission(code, results);
    }

    private final Voice voice = new Voice(this, new Voice.Listener() {
        @Override public void status(String text) {
            hideKeyboard();
            status.setText(text);
        }
        @Override public void heard(String text, boolean done) {
            question.setText(text);
            if (done) ask(text);
        }
        @Override public void failed(String why) {
            hideKeyboard();
            status.setText(why);
        }
    });

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(question.getWindowToken(), 0);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.OPEN, SettingsActivity.OPEN_AI));
    }
}
