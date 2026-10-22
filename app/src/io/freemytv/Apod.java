package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * NASA's Astronomy Picture of the Day as the home screen background (no key needed). The feed lists the last few
 * weeks; the five newest still images (some days are videos) can be browsed with ‹ ›, the newest first. The list and
 * the photos are kept on disk, so the home screen shows the last one right away, offline too. The feed is checked
 * once an hour; each photo is built into a Backdrop in the background.
 */
final class Apod {
    interface Listener { void onApod(); }

    static final class Photo {
        String date = "", title = "", credit = "", explanation = "", image = "";
    }

    static final int COUNT = 5;
    private static final String FEED = "https://science.nasa.gov/wp-json/wp/v2/apod-basic";
    private static final long EVERY = 3600_000L, RETRY = 10 * 60_000L;

    private static Apod instance;

    static synchronized Apod get(Context c) {
        if (instance == null) instance = new Apod(c.getApplicationContext());
        return instance;
    }

    private final File dir, listFile;
    private final ExecutorService io = Ui.background();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Photo> photos = new ArrayList<>();
    private volatile Backdrop backdrop;
    private volatile Photo shown;
    private volatile int index, wanted = -1;
    private volatile long checkedAt, failedAt;
    private volatile boolean busy, failed;

    private Apod(Context c) {
        dir = new File(c.getFilesDir(), "apod");
        listFile = new File(dir, "photos.json");
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The background, or null until the first photo is ready. */
    Backdrop backdrop() { return backdrop; }

    /** The photo on screen (null until the first one is ready). */
    Photo shown() { return shown; }

    /** Which of the photos is on screen (0 = the newest), and how many there are. */
    int index() { return index; }
    int count() { return photos.size(); }

    /** A photo picked with ‹ › is still downloading. */
    boolean loading() { return wanted >= 0; }

    /** The last pick couldn't be loaded (the home screen says so once). */
    boolean takeFailed() {
        boolean f = failed;
        failed = false;
        return f;
    }

    /** The file of the photo on screen (for the full view), or null. */
    File shownFile() {
        Photo p = shown;
        return p == null ? null : file(p);
    }

    /** "Title · date · NASA APOD · © credit", for the corner of the screen. */
    String credit() {
        Photo p = shown;
        if (p == null) return "";
        return p.title + " · " + p.date + " · NASA APOD" + (p.credit.isEmpty() ? "" : " · © " + p.credit);
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (busy) return;
        boolean stale = backdrop == null || now - checkedAt > EVERY || now < checkedAt;
        if (!stale || (failedAt != 0 && now - failedAt >= 0 && now - failedAt < RETRY)) return;
        busy = true;
        io.execute(() -> {
            try {
                if (backdrop == null) loadSaved();
                fetchList();
                checkedAt = System.currentTimeMillis();
                failedAt = 0;
            } catch (Exception | OutOfMemoryError e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("APOD: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    /** ‹ › on the home screen: shows photo i (0 = the newest), downloading it if needed. */
    void select(int i) {
        List<Photo> list = photos;
        if (list.isEmpty() || wanted >= 0) return;
        int target = (i % list.size() + list.size()) % list.size();
        if (target == index && backdrop != null) return;
        wanted = target;
        notifyListeners();
        io.execute(() -> {
            try {
                show(list.get(target), target);
            } catch (Exception | OutOfMemoryError e) {
                failed = true;
                AppLog.w("APOD photo " + list.get(target).date + ": " + e.getMessage());
            } finally {
                wanted = -1;
                notifyListeners();
            }
        });
    }

    /** The list and the photo from the last time (shown while the feed is checked). */
    private void loadSaved() {
        if (!listFile.exists()) return;
        try {
            JSONObject saved = new JSONObject(read(new FileInputStream(listFile)));
            List<Photo> list = parse(saved.getJSONArray("photos"));
            int i = Math.max(0, Math.min(list.size() - 1, saved.optInt("index")));
            if (list.isEmpty() || !file(list.get(i)).exists()) return;
            photos = list;
            show(list.get(i), i);
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("APOD saved photo: " + e.getMessage());
        }
    }

    /** The feed's newest still images; a new day's photo goes on screen (an older one picked by hand stays). */
    private void fetchList() throws Exception {
        JSONArray entries = new JSONArray(get(FEED));
        List<Photo> list = new ArrayList<>();
        for (int i = 0; i < entries.length() && list.size() < COUNT; i++) {
            JSONObject e = entries.getJSONObject(i);
            String image = e.optString("hdurl", "");
            if (!"image".equals(e.optString("media_type")) || !image.startsWith("https://")) continue;
            Photo p = new Photo();
            p.date = e.optString("date", "");
            p.title = plain(e.optString("title", "Astronomy Picture of the Day"));
            p.credit = plain(e.optString("copyright", e.optString("credit", ""))).replace('\n', ' ');
            p.explanation = plain(e.optString("explanation", "")).replaceFirst("^Explanation:\\s*", "");
            p.image = sized(image);
            list.add(p);
        }
        if (list.isEmpty()) throw new IOException("no still image in the feed");
        boolean newDay = photos.isEmpty() || !photos.get(0).date.equals(list.get(0).date);
        Photo current = shown;
        int at = 0;
        for (int i = 0; current != null && !newDay && i < list.size(); i++) if (list.get(i).date.equals(current.date)) at = i;
        photos = list;
        if (newDay || backdrop == null) {
            show(list.get(0), 0);
        } else {
            index = at;
            save();
        }
        prune(list);
        notifyListeners();
    }

    private void show(Photo p, int i) throws IOException {
        File f = file(p);
        if (!f.exists()) download(p.image, f);
        Backdrop b = Backdrop.apod(f);
        shown = p;
        index = i;
        backdrop = b;
        save();
        AppLog.w("APOD " + p.date + ": " + p.title);
        notifyListeners();
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onApod(); });
    }

    private File file(Photo p) {
        return new File(dir, "photo-" + p.date.replaceAll("[^0-9-]", ""));
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Photo p : photos) {
                a.put(new JSONObject().put("date", p.date).put("title", p.title).put("credit", p.credit)
                        .put("explanation", p.explanation).put("image", p.image));
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(listFile)) {
                out.write(new JSONObject().put("photos", a).put("index", index).toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            AppLog.w("APOD save: " + e.getMessage());
        }
    }

    private static List<Photo> parse(JSONArray a) throws org.json.JSONException {
        List<Photo> list = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Photo p = new Photo();
            p.date = o.optString("date");
            p.title = o.optString("title");
            p.credit = o.optString("credit");
            p.explanation = o.optString("explanation");
            p.image = o.optString("image");
            list.add(p);
        }
        return list;
    }

    /** Only the listed photos stay on disk. */
    private void prune(List<Photo> keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().startsWith("photo-")) continue;
            boolean listed = false;
            for (Photo p : keep) listed |= f.equals(file(p));
            //noinspection ResultOfMethodCallIgnored
            if (!listed) f.delete();
        }
    }

    /**
     * NASA's image service resizes on request: ask for about the screen's size instead of the original (often a PNG
     * of 8 MB or more).
     */
    private static String sized(String url) {
        if (!url.contains("/dynamicimage/")) return url;
        int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)) + "?w=" + Backdrop.W + "&h=" + Backdrop.W + "&fit=clip";
    }

    private void download(String url, File to) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(dir, "download.part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("image returned HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(to)) throw new IOException("couldn't save the image");
    }

    /** The feed's HTML (links, entities, line breaks) as plain text; paragraphs stay apart. */
    private static String plain(String html) {
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(' ', ' ');
        return text.replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("feed returned HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
