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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ask AI, full screen: the question (said through Google's voice input, or typed with the TV keyboard), then
 * Gemini's answer in large type, its sources when grounding is on, and what to do next. Back closes it. Built from
 * plain views (the TV keyboard, focus and scrolling come with them), in the home screen's fonts and colors.
 */
public final class AskActivity extends Activity {
    static final String MODE = "mode", QUESTION = "q";
    static final String VOICE = "voice", TYPE = "type";
    private static final int VOICE_INPUT = 7;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean night;
    private int ink, dim;
    private EditText question;
    private TextView chip, status, answer;
    private LinearLayout sources, actions;
    private ScrollView scroll;
    private int asking;   // the latest question; older answers that arrive late are dropped

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
            question.setText(q);
            ask(q);
        } else if (VOICE.equals(in.getStringExtra(MODE))) {
            listen();
        } else {
            typeQuestion();
        }
    }

    @Override protected void onDestroy() {
        asking++;
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

        question = new EditText(this);
        question.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        question.setTypeface(Ui.displaySemibold);
        question.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF1C1C1E, ink}));
        question.setHintTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF6E6E73, dim}));
        question.setHint(Ui.tr("Pergunte qualquer coisa", "Ask anything", "Pregunta lo que quieras"));
        question.setBackground(pill(false));
        question.setPadding(dp(18), dp(10), dp(18), dp(10));
        question.setSingleLine(true);
        question.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        question.setOnEditorActionListener((v, id, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || enter) {
                String q = question.getText().toString();
                if (!q.trim().isEmpty()) ask(q);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(14);
        col.addView(question, qLp);

        status = text(14, Ui.medium, dim);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(16);
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
        actLp.topMargin = dp(14);
        col.addView(actions, actLp);

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

    private void setActions(boolean settings) {
        actions.removeAllViews();
        actions.addView(button(Ui.tr("Falar", "Speak", "Hablar"), v -> listen()));
        actions.addView(button(Ui.tr("Digitar", "Type", "Escribir"), v -> typeQuestion()));
        if (settings) actions.addView(button(Ui.tr("Abrir Ajustes", "Open Settings", "Abrir Ajustes"), v -> openSettings()));
    }

    // MARK: - Asking

    private void typeQuestion() {
        status.setText(Ui.tr("Digite a pergunta e aperte Enter.", "Type your question, then press Enter.", "Escribe la pregunta y pulsa Enter."));
        if (actions.getChildCount() == 0) setActions(false);
        question.requestFocus();
        question.setSelection(question.getText().length());
        main.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(question, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void ask(String q) {
        hideKeyboard();
        final int id = ++asking;
        final boolean grounded = Keys.grounding(this);
        status.setText(grounded ? Ui.tr("Pesquisando no Google e escrevendo a resposta…", "Searching Google and writing an answer…", "Buscando en Google y escribiendo la respuesta…")
                : Ui.tr("Escrevendo a resposta…", "Writing an answer…", "Escribiendo la respuesta…"));
        answer.setText("");
        sources.removeAllViews();
        actions.removeAllViews();
        scroll.requestFocus();
        Context app = getApplicationContext();
        IO.execute(() -> {
            Gemini.Answer a = Gemini.ask(app, q);
            main.post(() -> { if (id == asking && !isFinishing()) show(a, grounded); });
        });
    }

    private void show(Gemini.Answer a, boolean grounded) {
        if ("no_key".equals(a.error)) {
            status.setText("");
            answer.setText(Ui.tr("O Pergunte à IA precisa de uma chave do Gemini.\nColoque uma em Ajustes, ou do computador com um comando adb (veja o README).",
                    "Ask AI needs a Gemini API key.\nAdd one in Settings, or from your computer with one adb command (see the README).",
                    "Pregunta a la IA necesita una clave de Gemini.\nAgrégala en Ajustes, o desde la computadora con un comando adb (ver el README)."));
            setActions(true);
            actions.getChildAt(2).requestFocus();
            return;
        }
        if (!a.error.isEmpty()) {
            status.setText("");
            answer.setText(a.error);
            setActions(true);
            actions.getChildAt(0).requestFocus();
            return;
        }
        status.setText("");
        chip.setText("✦  " + Ui.tr("Pergunte à IA", "Ask AI", "Pregunta a la IA") + "  ·  " + a.model);
        answer.setText(format(a.text));
        scroll.scrollTo(0, 0);
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
        setActions(false);
        scroll.requestFocus();
    }

    /** The first line large, "- " lines as bullets. */
    private CharSequence format(String text) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        boolean first = true;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            line = line.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            boolean bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ");
            if (bullet) line = "•  " + line.substring(2).trim();
            if (b.length() > 0) b.append("\n");
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
        return b;
    }

    // MARK: - Voice (Google's voice input screen: it turns on the remote's microphone, as the mic button does)

    private void listen() {
        hideKeyboard();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ui.locale().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Pergunte qualquer coisa", "Ask anything", "Pregunta lo que quieras"));
        try {
            startActivityForResult(intent, VOICE_INPUT);
            status.setText(Ui.tr("Ouvindo…", "Listening…", "Escuchando…"));
        } catch (android.content.ActivityNotFoundException e) {
            status.setText(Ui.tr("Esta TV não tem entrada de voz: digite a pergunta.", "This TV has no voice input: type your question.", "Este TV no tiene entrada de voz: escribe la pregunta."));
            typeQuestion();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != VOICE_INPUT) return;
        ArrayList<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && said != null && !said.isEmpty() && !said.get(0).trim().isEmpty()) {
            question.setText(said.get(0));
            ask(said.get(0));
        } else {
            status.setText(Ui.tr("Não ouvi nada. Fale de novo ou digite.", "I didn't catch that. Speak again or type.", "No te escuché. Habla de nuevo o escribe."));
            setActions(false);
            actions.getChildAt(0).requestFocus();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(question.getWindowToken(), 0);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.OPEN, SettingsActivity.OPEN_AI));
    }
}
