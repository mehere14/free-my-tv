package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ask AI: one question to Gemini (generateContent), with Google Search grounding when it's on (the google_search
 * tool), answered in a few lines readable from the couch. The key comes from Keys; it goes in a header, never in the
 * URL or the log. Background threads only.
 */
final class Gemini {
    private Gemini() {}

    private static final String API = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String SYSTEM = "You answer questions asked on a living-room TV. The viewer is in %s. Be brief "
            + "and readable from the couch: a one-sentence answer first, then at most 4 short bullet points. Use "
            + "Celsius and metric units. Plain text with '- ' bullets only, no markdown headings, tables or bold.";

    static final class Answer {
        String text = "", model = "", error = "";
        final List<String> sources = new ArrayList<>();
    }

    static Answer ask(Context c, String question) {
        Answer a = new Answer();
        String key = Keys.gemini(c);
        if (key.isEmpty()) {
            a.error = "no_key";
            return a;
        }
        try {
            JSONObject body = new JSONObject();
            String place = Prefs.city().isEmpty() ? "their city" : Prefs.city();
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", String.format(SYSTEM, place)))));
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", question)))));
            if (Keys.grounding(c)) body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
            JSONObject res = post(API + Keys.model(c) + ":generateContent", key, body.toString());
            JSONObject cand = res.optJSONArray("candidates") != null ? res.getJSONArray("candidates").optJSONObject(0) : null;
            StringBuilder text = new StringBuilder();
            if (cand != null && cand.optJSONObject("content") != null) {
                JSONArray parts = cand.getJSONObject("content").optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (!p.optBoolean("thought")) text.append(p.optString("text"));
                }
            }
            a.text = text.toString().trim();
            a.model = res.optString("modelVersion", Keys.model(c));
            JSONObject meta = cand == null ? null : cand.optJSONObject("groundingMetadata");
            JSONArray chunks = meta == null ? null : meta.optJSONArray("groundingChunks");
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; chunks != null && i < chunks.length(); i++) {
                JSONObject web = chunks.getJSONObject(i).optJSONObject("web");
                if (web != null && !web.optString("title").isEmpty()) seen.add(web.optString("title"));
            }
            for (String s : seen) if (a.sources.size() < 5) a.sources.add(s);
            if (a.text.isEmpty()) {
                String why = cand != null ? cand.optString("finishReason", "") : "";
                a.error = Ui.tr("O Gemini não respondeu", "Gemini gave no answer", "Gemini no respondió") + (why.isEmpty() ? "." : " (" + why + ").");
            }
        } catch (ApiError e) {
            a.error = e.getMessage();
        } catch (Exception e) {
            a.error = Ui.tr("Não deu para falar com o Gemini: ", "Couldn't reach Gemini: ", "No se pudo hablar con Gemini: ") + e.getClass().getSimpleName();
        }
        return a;
    }

    /** Settings' "Test connection": the model exists, and it answers. Returns "" when it works, else the API's words. */
    static String test(Context c) {
        Answer a = ask(c, "Reply with the single word: ready");
        if ("no_key".equals(a.error)) return Ui.tr("Falta a chave do Gemini.", "There's no Gemini key yet.", "Falta la clave de Gemini.");
        return a.error.isEmpty() ? "" : a.error;
    }

    /** The API's own message when it answers with an error ("API key not valid…"). */
    static final class ApiError extends IOException {
        ApiError(String message) { super(message); }
    }

    private static JSONObject post(String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(45_000);   // grounded answers search first: 10 to 15 s is normal
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "";
                try { msg = new JSONObject(text).getJSONObject("error").optString("message"); } catch (Exception ignored) {}
                throw new ApiError("Gemini (" + code + "): " + (msg.isEmpty() ? "HTTP " + code : msg));
            }
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
