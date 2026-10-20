package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weather radar: Environment Canada's composite (MSC GeoMet; rain, or snow when it's freezing) in Canada, NOAA's
 * mosaic (nowCOAST) in the US, as WMS frames over a map (MapTiles). The radar card's picture (built in the background
 * at most every 6 minutes, the radar's own step) and, for RadarActivity, the last hour of frames.
 */
final class Radar {
    private static final String GEOMET = "https://geo.weather.gc.ca/geomet";
    private static final String NOAA = "https://nowcoast.noaa.gov/geoserver/weather_radar/";
    private static final long EVERY = 6 * 60_000L;
    private static final ExecutorService IO = Ui.background();

    /** The card's picture (map, latest frame, place names), and its line: "Rain · 3:54 PM · Map data ©2026 Google". */
    volatile Bitmap picture;
    volatile String caption = "";
    volatile boolean google;
    private volatile boolean fetching;
    private volatile long fetchedAt;
    private String fetchedFor = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Which radar covers home, and how it's labeled. */
    static final class Source {
        final boolean canada, snow;
        final String layer;
        Source(double lat, double lon, boolean snow) {
            canada = inCanada(lat, lon);
            this.snow = canada && snow;
            layer = canada ? (snow ? "RADAR_1KM_RSNO" : "RADAR_1KM_RRAI") : noaaLayer(lat, lon);
        }
        String label() {
            return !canada ? Ui.tr("Radar", "Radar", "Radar") : snow ? Ui.tr("Neve", "Snow", "Nieve") : Ui.tr("Chuva", "Rain", "Lluvia");
        }
        String legendTitle() {
            return !canada ? Ui.tr("Refletividade (dBZ)", "Reflectivity (dBZ)", "Reflectividad (dBZ)")
                    : snow ? Ui.tr("Neve (cm/h)", "Snow (cm/h)", "Nieve (cm/h)") : Ui.tr("Chuva (mm/h)", "Rain (mm/h)", "Lluvia (mm/h)");
        }
        String credit() { return canada ? "ECCC MSC GeoMet" : "NOAA nowCOAST"; }
        String frame(MapTiles.Map m, String time, int w, int h) {
            return (canada ? GEOMET : NOAA + "wms") + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + layer
                    + "&STYLES=&CRS=EPSG:3857&BBOX=" + m.bbox() + "&WIDTH=" + w + "&HEIGHT=" + h
                    + "&FORMAT=image/png&TRANSPARENT=TRUE&TIME=" + time;
        }
        String legend() {
            return (canada ? GEOMET : NOAA + "wms") + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetLegendGraphic&LAYER=" + layer
                    + "&FORMAT=image/png" + (canada ? "&SLD_VERSION=1.1.0" : "");
        }
    }

    static Source source(Context c, HomeData.Weather wx) {
        boolean snow = !Double.isNaN(wx.temp) && (wx.temp <= 0.5 || "snowflake".equals(Symbols.weather(wx.code, true)));
        return new Source(Double.parseDouble(Prefs.latitude()), Double.parseDouble(Prefs.longitude()), snow);
    }

    /** Builds a w × h picture if the last one is older than a radar step (or was for another size or look). */
    void refreshIfStale(Context c, int w, int h, boolean night, boolean snow, Runnable done) {
        Context app = c.getApplicationContext();
        String key = w + "x" + h + (night ? "n" : "d") + (snow ? "s" : "r") + Prefs.latitude() + Prefs.longitude() + Keys.maps(app).length();
        long now = System.currentTimeMillis();
        if (fetching || w <= 0 || h <= 0 || (key.equals(fetchedFor) && now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        IO.execute(() -> {
            try {
                build(app, w, h, night, snow);
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

    private void build(Context c, int w, int h, boolean night, boolean snow) throws Exception {
        double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
        Source src = new Source(lat, lon, snow);
        List<String> times = lastHour(src);
        String time = times.get(times.size() - 1);
        // Zoom 7 at 512 px a tile: the mockup's thumbnail, ~140 km across the card.
        MapTiles.Map m = MapTiles.build(c, lat, lon, 7, w, h, night);
        Canvas canvas = new Canvas(m.picture);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        byte[] raw = MapTiles.get(src.frame(m, time, w, h));
        Bitmap frame = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (frame != null) {
            p.setAlpha(Math.round(255 * 0.85f));
            canvas.drawBitmap(frame, 0, 0, p);
            frame.recycle();
        }
        Bitmap names = MapTiles.names(m, night);   // above the rain
        if (names != null) {
            p.setAlpha(255);
            canvas.drawBitmap(names, 0, 0, p);
            names.recycle();
        }
        if (m.google) MapTiles.logo();   // fetched once here, drawn by the card
        picture = m.picture;
        google = m.google;
        caption = src.label() + " · " + Ui.time(utc(time)) + " · " + (m.google ? m.credit : "Esri");
    }

    private static final Pattern GEOMET_TIME = Pattern.compile("<Dimension name=\"time\"[^>]*>[^/<]+/([^/<]+)/PT(\\d+)M");
    private static final Pattern NOAA_TIMES = Pattern.compile("<Dimension name=\"time\"[^>]*>([^<]+)");

    /**
     * The last hour of frames, oldest first, from the service's own time list: GeoMet gives a range in 6-minute
     * steps (11 frames), NOAA a list (a frame about every 4 minutes).
     */
    static List<String> lastHour(Source src) throws IOException {
        String caps = src.canada ? GEOMET + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities&LAYER=" + src.layer
                : NOAA + src.layer + "/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities";
        String xml = new String(MapTiles.get(caps), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        try {
            if (src.canada) {
                Matcher m = GEOMET_TIME.matcher(xml);
                if (!m.find()) throw new IOException("no radar times");
                long end = utc(m.group(1)), step = Long.parseLong(m.group(2)) * 60_000L;
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                for (long t = end - 10 * step; t <= end; t += step) out.add(iso.format(t));
            } else {
                Matcher m = NOAA_TIMES.matcher(xml);
                if (!m.find()) throw new IOException("no radar times");
                String[] all = m.group(1).split(",");
                long end = utc(all[all.length - 1].trim());
                for (String t : all) if (utc(t.trim()) >= end - 3_600_000L) out.add(t.trim());
            }
        } catch (java.text.ParseException e) {
            throw new IOException("bad radar times");
        }
        return out;
    }

    static long utc(String iso) throws java.text.ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(iso.substring(0, 19)).getTime();
    }

    /** Roughly Canada (GeoMet's composite); the rest of North America goes to NOAA. */
    static boolean inCanada(double lat, double lon) {
        return lat > 41.6 && lon > -141 && !(lat < 49 && lon < -95) && !(lon < -130 && lat < 60);
    }

    private static String noaaLayer(double lat, double lon) {
        String region = lat > 51 && lon < -129 ? "alaska" : lat < 23 && lon < -150 ? "hawaii" : lat < 20 && lon > -68 ? "caribbean" : "conus";
        return region + "_base_reflectivity_mosaic";
    }
}
