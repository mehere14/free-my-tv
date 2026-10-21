package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * Hold-to-talk for Ask AI and Plan a route, the way the TV's remote works: this TV has no microphone of its own, and
 * the remote's microphone streams only while its mic button is held (Sony's remote voice service connects then). So
 * holding the mic button starts the TV's speech recognizer (Google's) and letting go ends it; what was said arrives
 * as it's heard (partial) and when it's done. No overlay: the screen shows its own "Listening…".
 */
final class Voice implements RecognitionListener {
    interface Listener {
        /** Status for the screen ("Listening…"), already in the TV's language. */
        void status(String text);
        void heard(String text, boolean done);
        void failed(String why);
    }

    static final int PERMISSION = 77;
    private final Activity activity;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean listening, pending, pendingTap;

    Voice(Activity a, Listener l) {
        activity = a;
        listener = l;
    }

    /** The remote's mic button (whichever code this remote sends). */
    static boolean isMicKey(int code) {
        return code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST;
    }

    /** From the activity's dispatchKeyEvent: press starts listening, release ends it. True when it was the mic button. */
    boolean onKey(KeyEvent e) {
        if (!isMicKey(e.getKeyCode())) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) start(true);
        else if (e.getAction() == KeyEvent.ACTION_UP) stop();
        return true;
    }

    /**
     * Starts listening: held (the remote's mic button, until it's let go) or tapped (an on-screen button: the
     * recognizer stops by itself when you stop talking).
     */
    void start(boolean held) {
        if (!remotePaired()) {
            listener.failed(NOT_PAIRED);
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = true;
            pendingTap = !held;
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            listener.failed(Ui.tr("Esta TV não tem reconhecimento de voz: digite.", "This TV has no speech recognition: type instead.", "Este TV no tiene reconocimiento de voz: escribe."));
            return;
        }
        if (listening) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(this);
        }
        listening = true;
        listener.status(held ? Ui.tr("Ouvindo… solte o botão quando terminar", "Listening… let go of the button when you're done",
                "Escuchando… suelta el botón cuando termines")
                : Ui.tr("Ouvindo… fale agora", "Listening… speak now", "Escuchando… habla ahora"));
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ui.locale().toLanguageTag()));
    }

    /**
     * Whether a Bluetooth remote is paired: this TV has no microphone of its own, so without one nothing can hear you
     * (Google Assistant neither). Unknown (no Bluetooth, or not allowed to ask) counts as paired: try anyway.
     */
    private static boolean remotePaired() {
        try {
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return bt == null || !bt.isEnabled() || !bt.getBondedDevices().isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static final String NOT_PAIRED = Ui.tr(
            "O microfone do controle não está conectado: esta TV não tem microfone próprio. Pareie o controle por Bluetooth em Configurações → Controles remotos e acessórios, e então segure o botão do microfone.",
            "Your remote's microphone isn't connected: this TV has no microphone of its own. Pair the remote over Bluetooth in Settings → Remotes & Accessories, then hold its mic button.",
            "El micrófono del control no está conectado: este TV no tiene micrófono propio. Empareja el control por Bluetooth en Ajustes → Controles remotos y accesorios, y luego mantén pulsado su botón de micrófono.");

    /** The button was let go: what was heard so far becomes the result. */
    void stop() {
        if (recognizer != null && listening) recognizer.stopListening();
    }

    void cancel() {
        if (recognizer != null && listening) recognizer.cancel();
        listening = false;
    }

    void destroy() {
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
        listening = false;
    }

    /** From the activity's onRequestPermissionsResult. */
    void onPermission(int code, int[] results) {
        if (code != PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pending && pendingTap) { pending = false; start(false); return; }
        if (granted && pending) listener.status(Ui.tr("Pronto: segure o botão do microfone e fale.", "Ready: hold the mic button and speak.",
                "Listo: mantén pulsado el botón del micrófono y habla."));
        else if (!granted) listener.failed(Ui.tr("Sem o microfone, digite.", "Without the microphone, type instead.", "Sin el micrófono, escribe."));
        pending = false;
    }

    @Override public void onReadyForSpeech(Bundle b) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float v) {}
    @Override public void onBufferReceived(byte[] b) {}

    @Override public void onEndOfSpeech() {
        listener.status(Ui.tr("Entendendo…", "Getting that…", "Entendiendo…"));
    }

    @Override public void onError(int error) {
        listening = false;
        listener.failed(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ? Ui.tr("Não ouvi nada. Segure o botão do microfone do controle enquanto fala.", "I didn't catch that. Hold the remote's mic button while you speak.",
                        "No te escuché. Mantén pulsado el botón del micrófono del control mientras hablas.")
                : Ui.tr("A voz não funcionou (erro ", "Voice didn't work (error ", "La voz no funcionó (error ") + error + Ui.tr("). Digite.", "). Type instead.", "). Escribe."));
    }

    @Override public void onResults(Bundle b) {
        listening = false;
        ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said != null && !said.isEmpty() && !said.get(0).trim().isEmpty()) listener.heard(said.get(0), true);
        else onError(SpeechRecognizer.ERROR_NO_MATCH);
    }

    @Override public void onPartialResults(Bundle b) {
        ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said != null && !said.isEmpty() && !said.get(0).isEmpty()) listener.heard(said.get(0), false);
    }

    @Override public void onEvent(int type, Bundle b) {}
}
