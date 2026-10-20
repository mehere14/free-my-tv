package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Map pictures for the radar and the route planner, in Web Mercator: Google's default roadmap (Map Tiles API) with
 * a Google Maps Platform key, else Esri's keyless grey canvas. Drawn at the mockup's scale: each zoom-z tile covers
 * 512 screen pixels (Google's 2x tiles; Esri's are stretched).
 *
 * Google's terms: their logo and the copyright line for what's shown stay on the map (Map.logo, Map.credit), and
 * tiles are kept only in memory, for the picture being drawn. The key goes in a header, never in a URL or the log.
 */
final class MapTiles {
    private MapTiles() {}

    static final int TILE = 512;
    private static final String GOOGLE = "https://tile.googleapis.com/";
    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/";
    private static String session = "", sessionKey = "";
    private static long sessionUntil;
    private static Bitmap logo;

    /** One map picture: its box in world pixels at `zoom` (TILE per tile), and whose map it is. */
    static final class Map {
        Bitmap picture;
        int zoom, width, height;
        double x0, y0;
        boolean google;
        /** "Map data ©2026 Google", or Esri's credit. */
        String credit = "";

        /** Where (lat, lon) falls on the picture. */
        float x(double lat, double lon) { return (float) (worldX(lon, zoom) - x0); }
        float y(double lat, double lon) { return (float) (worldY(lat, zoom) - y0); }

        /** The picture's box in Web Mercator meters, for a WMS request (west, south, east, north). */
        String bbox() {
            double n = TILE * Math.pow(2, zoom), w = 2 * Math.PI * 6378137;
            return String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f", x0 / n * w - w / 2, w / 2 - (y0 + height) / n * w,
                    (x0 + width) / n * w - w / 2, w / 2 - y0 / n * w);
        }
    }

    static double worldX(double lon, int z) {
        return (lon + 180) / 360 * TILE * Math.pow(2, z);
    }

    static double worldY(double lat, int z) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * Math.pow(2, z);
    }

    static double lat(double y, int z) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / (TILE * Math.pow(2, z))))));
    }

    static double lon(double x, int z) {
        return x / (TILE * Math.pow(2, z)) * 360 - 180;
    }

    /**
     * A w × h picture centered on (lat, lon) at `zoom` (night: Esri's dark canvas; Google's default map stays light).
     * With Esri, the place names are a separate layer (`names`), drawn above whatever goes over the base. Background
     * thread only.
     */
    static Map build(Context c, double lat, double lon, int zoom, int w, int h, boolean night) throws IOException {
        Map m = box(lat, lon, zoom, w, h);
        m.picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(m.picture);
        String key = Keys.maps(c);
        if (!key.isEmpty()) {
            try {
                tiles(canvas, m, key, null);
                m.google = true;
                m.credit = credit(key, m);
                return m;
            } catch (IOException e) {
                AppLog.w("Google map: " + e.getMessage() + " (Esri instead)");
                m.picture.eraseColor(0);
            }
        }
        tiles(canvas, m, null, (night ? "World_Dark_Gray_" : "World_Light_Gray_") + "Base");
        m.credit = "Esri, HERE, Garmin, © OpenStreetMap";
        return m;
    }

    /** The map's box only, no picture: enough to ask for radar frames while the tiles are still coming. */
    static Map box(double lat, double lon, int zoom, int w, int h) {
        Map m = new Map();
        m.zoom = zoom;
        m.width = w;
        m.height = h;
        m.x0 = worldX(lon, zoom) - w / 2.0;
        m.y0 = worldY(lat, zoom) - h / 2.0;
        return m;
    }

    /** Esri's place names, as a transparent picture the size of the map; null with Google's (they're in its tiles). */
    static Bitmap names(Map m, boolean night) {
        if (m.google) return null;
        Bitmap b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888);
        try {
            tiles(new Canvas(b), m, null, (night ? "World_Dark_Gray_" : "World_Light_Gray_") + "Reference");
        } catch (IOException e) {
            AppLog.w("map names: " + e.getMessage());
        }
        return b;
    }

    private static void tiles(Canvas canvas, Map m, String key, String esriLayer) throws IOException {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        int w = m.width, h = m.height;
        RectF dst = new RectF();
        for (int tx = (int) Math.floor(m.x0 / TILE); tx <= (int) Math.floor((m.x0 + w - 1) / TILE); tx++) {
            for (int ty = (int) Math.floor(m.y0 / TILE); ty <= (int) Math.floor((m.y0 + h - 1) / TILE); ty++) {
                byte[] raw = key != null ? google(key, "v1/2dtiles/" + m.zoom + "/" + tx + "/" + ty, null)
                        : get(ESRI + esriLayer + "/MapServer/tile/" + m.zoom + "/" + ty + "/" + tx);
                Bitmap t = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (t == null) continue;
                float left = (float) (tx * TILE - m.x0), top = (float) (ty * TILE - m.y0);
                dst.set(left, top, left + TILE, top + TILE);
                canvas.drawBitmap(t, null, dst, p);
                t.recycle();
            }
        }
    }

    /** Google's copyright line for the map's box ("Map data ©2026 Google"). */
    private static String credit(String key, Map m) {
        try {
            double w = m.width, h = m.height;
            String q = String.format(Locale.US, "zoom=%d&north=%.4f&south=%.4f&west=%.4f&east=%.4f", m.zoom,
                    lat(m.y0, m.zoom), lat(m.y0 + h, m.zoom), lon(m.x0, m.zoom), lon(m.x0 + w, m.zoom));
            String text = new JSONObject(new String(google(key, "tile/v1/viewport", q), StandardCharsets.UTF_8)).optString("copyright");
            return text.isEmpty() ? "Map data © Google" : text;
        } catch (Exception e) {
            return "Map data © Google";
        }
    }

    /** The logo if it's here already: for drawing (never waits for the network). */
    static Bitmap logoIfLoaded() {
        return logo;
    }

    /** Google's logo (their own file, as the Maps JavaScript API shows it), for the corner of a Google map. */
    static synchronized Bitmap logo() {
        if (logo == null) {
            try {
                byte[] raw = get("https://maps.gstatic.com/mapfiles/api-3/images/google4_hdpi.png");
                logo = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            } catch (IOException e) {
                AppLog.w("Google logo: " + e.getMessage());
            }
        }
        return logo;
    }

    /** A Map Tiles request with the session (made once, kept until an hour before it expires, about two weeks). */
    private static byte[] google(String key, String path, String query) throws IOException {
        String token = session(key);
        String url = GOOGLE + path + "?session=" + token + (query == null ? "" : "&" + query);
        try {
            return get(url, key, null);
        } catch (IOException e) {
            synchronized (MapTiles.class) { sessionUntil = 0; }   // an expired session: a new one next time
            throw e;
        }
    }

    private static synchronized String session(String key) throws IOException {
        if (key.equals(sessionKey) && System.currentTimeMillis() < sessionUntil) return session;
        try {
            String body = "{\"mapType\":\"roadmap\",\"language\":\"" + Ui.locale().toLanguageTag() + "\",\"region\":\"CA\","
                    + "\"scale\":\"scaleFactor2x\",\"highDpi\":true}";
            JSONObject s = new JSONObject(new String(get(GOOGLE + "v1/createSession", key, body), StandardCharsets.UTF_8));
            session = s.getString("session");
            sessionKey = key;
            sessionUntil = s.optLong("expiry", System.currentTimeMillis() / 1000 + 86400) * 1000 - 3_600_000L;
            return session;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("no map session (" + e.getClass().getSimpleName() + ")");
        }
    }

    static byte[] get(String url) throws IOException {
        return get(url, null, null);
    }

    /** GET, or POST `body` as JSON; with a key, it goes in Google's header. Errors carry the API's words, not the URL. */
    static byte[] get(String url, String key, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        if (key != null) c.setRequestProperty("X-Goog-Api-Key", key);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String msg = "";
                try { msg = new JSONObject(new String(read(c.getErrorStream()), StandardCharsets.UTF_8)).getJSONObject("error").optString("message"); }
                catch (Exception ignored) {}
                throw new IOException("HTTP " + code + (msg.isEmpty() ? "" : ": " + msg));
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
