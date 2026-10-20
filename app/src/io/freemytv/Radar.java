package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The radar card's picture: Esri's keyless grey map (dark at night) with the latest radar frame on it, and the place
 * names on top. The radar is Environment Canada's composite (MSC GeoMet; rain, or snow when it's freezing) in Canada,
 * NOAA's mosaic (nowCOAST) in the US. Built in the background, at most every 6 minutes (the radar's own step), and
 * only while the home screen asks.
 */
final class Radar {
    private static final String GEOMET = "https://geo.weather.gc.ca/geomet";
    private static final String NOAA = "https://nowcoast.noaa.gov/geoserver/weather_radar/";
    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/";
    private static final long EVERY = 6 * 60_000L;
    /** Zoom 8 at 2 screen pixels per dp: the mockup's zoom 7 at 1 CSS pixel per dp, ~140 km across the card. */
    private static final int ZOOM = 8;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    volatile Bitmap picture;
    /** "Rain · 3:54 PM", or why there's no picture. */
    volatile String caption = "";
    private volatile boolean fetching;
    private volatile long fetchedAt;
    private String fetchedFor = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Builds a w × h picture if the last one is older than a radar step (or was for another size or look). */
    void refreshIfStale(int w, int h, boolean night, boolean snow, Runnable done) {
        String key = w + "x" + h + (night ? "n" : "d") + (snow ? "s" : "r") + Prefs.latitude() + Prefs.longitude();
        long now = System.currentTimeMillis();
        if (fetching || w <= 0 || h <= 0 || (key.equals(fetchedFor) && now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        IO.execute(() -> {
            try {
                build(w, h, night, snow);
                fetchedFor = key;
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("radar: " + e.getMessage());
                caption = Ui.tr("Radar indisponível agora", "Radar unavailable right now", "Radar no disponible ahora");
            } finally {
                fetching = false;
                main.post(done);
            }
        });
    }

    private void build(int w, int h, boolean night, boolean snow) throws Exception {
        double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
        boolean canada = inCanada(lat, lon);
        String layer = canada ? (snow ? "RADAR_1KM_RSNO" : "RADAR_1KM_RRAI") : noaaLayer(lat, lon);
        String time = latestTime(canada, layer);

        double n = 256 * Math.pow(2, ZOOM), s = Math.sin(Math.toRadians(lat));
        double cx = (lon + 180) / 360 * n, cy = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n;
        double x0 = cx - w / 2.0, y0 = cy - h / 2.0;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        String tone = night ? "World_Dark_Gray_" : "World_Light_Gray_";
        tiles(c, p, tone + "Base", x0, y0, w, h);

        // The frame for exactly the map's box, in Web Mercator meters.
        double world = 2 * Math.PI * 6378137;
        String bbox = String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f", x0 / n * world - world / 2, world / 2 - (y0 + h) / n * world,
                (x0 + w) / n * world - world / 2, world / 2 - y0 / n * world);
        String wms = (canada ? GEOMET : NOAA + "wms") + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + layer
                + "&STYLES=&CRS=EPSG:3857&BBOX=" + bbox + "&WIDTH=" + w + "&HEIGHT=" + h
                + "&FORMAT=image/png&TRANSPARENT=TRUE&TIME=" + time;
        byte[] raw = bytes(wms);
        Bitmap frame = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (frame != null) {
            p.setAlpha(Math.round(255 * 0.85f));
            c.drawBitmap(frame, 0, 0, p);
            p.setAlpha(255);
            frame.recycle();
        }
        tiles(c, p, tone + "Reference", x0, y0, w, h);   // names above the rain

        picture = out;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String kind = !canada ? Ui.tr("Radar", "Radar", "Radar") : snow ? Ui.tr("Neve", "Snow", "Nieve") : Ui.tr("Chuva", "Rain", "Lluvia");
        caption = kind + " · " + Ui.time(iso.parse(time.substring(0, 19)).getTime());
    }

    /** Esri's 256 px tiles under the box (x0, y0, w, h) in world pixels at ZOOM. */
    private static void tiles(Canvas c, Paint p, String layer, double x0, double y0, int w, int h) throws IOException {
        for (int tx = (int) Math.floor(x0 / 256); tx <= (int) Math.floor((x0 + w - 1) / 256); tx++) {
            for (int ty = (int) Math.floor(y0 / 256); ty <= (int) Math.floor((y0 + h - 1) / 256); ty++) {
                byte[] raw = bytes(ESRI + layer + "/MapServer/tile/" + ZOOM + "/" + ty + "/" + tx);
                Bitmap t = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (t == null) continue;
                c.drawBitmap(t, (float) (tx * 256 - x0), (float) (ty * 256 - y0), p);
                t.recycle();
            }
        }
    }

    private static final Pattern GEOMET_TIME = Pattern.compile("<Dimension name=\"time\"[^>]*>[^/<]+/([^/<]+)/PT\\d+M");
    private static final Pattern NOAA_TIMES = Pattern.compile("<Dimension name=\"time\"[^>]*>([^<]+)");

    /** The newest frame, from the service's own list of times. */
    private static String latestTime(boolean canada, String layer) throws IOException {
        String caps = canada ? GEOMET + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities&LAYER=" + layer
                : NOAA + layer + "/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities";
        String xml = new String(bytes(caps), StandardCharsets.UTF_8);
        Matcher m = (canada ? GEOMET_TIME : NOAA_TIMES).matcher(xml);
        if (!m.find()) throw new IOException("no radar times");
        String[] all = m.group(1).split(",");
        return all[all.length - 1].trim();
    }

    /** Roughly Canada (GeoMet's composite); the rest of North America goes to NOAA. */
    static boolean inCanada(double lat, double lon) {
        return lat > 41.6 && lon > -141 && !(lat < 49 && lon < -95) && !(lon < -130 && lat < 60);
    }

    private static String noaaLayer(double lat, double lon) {
        String region = lat > 51 && lon < -129 ? "alaska" : lat < 23 && lon < -150 ? "hawaii" : lat < 20 && lon > -68 ? "caribbean" : "conus";
        return region + "_base_reflectivity_mosaic";
    }

    private static byte[] bytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int k;
                while ((k = in.read(buffer)) > 0) out.write(buffer, 0, k);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
