package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The radar, full screen (OK on the weather or radar card): the map centered on home (Google's with a key, else
 * Esri's), the last hour of radar animated over it with the frame's time, the legend and the credits. The newest frame
 * comes first, with the map: the screen is useful as soon as both are in (the mockup measured ~0.6 s); the rest of the
 * hour streams in after. ← → step through the hour, OK pauses or plays, Back closes.
 */
public final class RadarActivity extends Activity {
    private RadarView view;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        view = new RadarView(this);
        setContentView(view);
    }

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            view.step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            view.togglePlay();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    static final class RadarView extends View {
        private static final int ZOOM = 8;   // the mockup's full-screen radar: ~425 km across
        private final ExecutorService io = Executors.newFixedThreadPool(4);
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Context app;
        private final boolean night;
        private final Radar.Source src;
        private final String city;
        private volatile MapTiles.Map map;
        private volatile Bitmap names, legend;
        private final List<String> times = new ArrayList<>();
        private Bitmap[] frames = new Bitmap[0];
        private int index;
        private boolean playing = true, stopped, started;
        private String status = "";
        private final long startedAt = SystemClock.uptimeMillis();
        private float k = 1;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint cap, time, ago, small, foot, credit;
        private final RectF r = new RectF(), img = new RectF();

        RadarView(Activity a) {
            super(a);
            app = a.getApplicationContext();
            HomeData data = HomeData.get(a);
            night = data.isNight(System.currentTimeMillis());
            src = Radar.source(a, data.weather);
            city = Prefs.city();
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            cap = Ui.text(Ui.semibold, 25 * k, night ? 0xADFFFFFF : 0xFF6E6E73);
            time = Ui.numbers(Ui.light, 68 * k, night ? 0xFFF5F5F7 : 0xFF1D1D1F);
            ago = Ui.text(Ui.medium, 25 * k, night ? 0xADFFFFFF : 0xFF6E6E73);
            small = Ui.text(Ui.medium, 21 * k, night ? 0xADFFFFFF : 0xFF6E6E73);
            foot = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
            foot.setShadowLayer(6 * k, 0, 2 * k, 0x99000000);
            credit = Ui.text(Ui.regular, 18 * k, 0xBFFFFFFF);
            credit.setTextAlign(Paint.Align.RIGHT);
            credit.setShadowLayer(4 * k, 0, 1 * k, 0x99000000);
            if (!started && w > 0) {
                started = true;
                load(w, h);
            }
        }

        private void load(int w, int h) {
            double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
            status = Ui.tr("Carregando o radar…", "Loading radar…", "Cargando el radar…");
            // Frames at half the screen's pixels (the radar's 1 km cells are coarser than that anyway), drawn stretched.
            final int fw = w / 2, fh = h / 2;
            MapTiles.Map box = MapTiles.box(lat, lon, ZOOM, w, h);
            Future<MapTiles.Map> tiles = io.submit(() -> MapTiles.build(app, lat, lon, ZOOM, w, h, night));
            io.execute(() -> { try { legend = bitmap(src.legend()); } catch (Exception ignored) {} });
            io.execute(() -> {
                try {
                    List<String> all = Radar.lastHour(src);
                    int last = all.size() - 1;
                    Bitmap newest = bitmap(src.frame(box, all.get(last), fw, fh));
                    MapTiles.Map m = tiles.get();
                    Bitmap overlay = MapTiles.names(m, night);
                    if (m.google) MapTiles.logo();
                    float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
                    AppLog.w(String.format(java.util.Locale.US, "radar: ready in %.2f s", seconds));
                    main.post(() -> {
                        if (stopped) return;
                        map = m;
                        names = overlay;
                        times.clear();
                        times.addAll(all);
                        frames = new Bitmap[all.size()];
                        frames[last] = newest;
                        index = last;
                        status = String.format(Ui.locale(), Ui.tr("Pronto em %.1f s", "Ready in %.1f s", "Listo en %.1f s"), seconds);
                        invalidate();
                        for (int i = 0; i < last; i++) fetchFrame(i, box, fw, fh);
                        schedule();
                    });
                } catch (Exception e) {
                    AppLog.w("radar: " + e.getMessage());
                    main.post(() -> {
                        status = src.credit() + Ui.tr(" não respondeu. Tente de novo em um minuto.", " isn't answering. Try again in a minute.",
                                " no responde. Intenta de nuevo en un minuto.");
                        invalidate();
                    });
                }
            });
        }

        private void fetchFrame(int i, MapTiles.Map box, int fw, int fh) {
            String t = times.get(i);
            io.execute(() -> {
                try {
                    Bitmap b = bitmap(src.frame(box, t, fw, fh));
                    main.post(() -> {
                        if (stopped || i >= frames.length) return;
                        frames[i] = b;
                        invalidate();   // the frame bar fills in
                    });
                } catch (Exception ignored) {
                }
            });
        }

        private static Bitmap bitmap(String url) throws java.io.IOException {
            byte[] raw = MapTiles.get(url);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        }

        private final Runnable advance = new Runnable() {
            @Override public void run() {
                if (stopped || !playing || frames.length == 0) return;
                int last = frames.length - 1, next = index >= last ? 0 : index + 1;
                while (next < last && frames[next] == null) next++;   // frames still loading are skipped
                index = next;
                invalidate();
                schedule();
            }
        };

        /** Like the mockup: 0.45 s a frame, a 1.5 s pause on the newest. */
        private void schedule() {
            main.removeCallbacks(advance);
            if (playing && frames.length > 0) main.postDelayed(advance, index == frames.length - 1 ? 1500 : 450);
        }

        void step(int d) {
            if (frames.length == 0) return;
            playing = false;
            main.removeCallbacks(advance);
            int next = index;
            do { next += d; } while (next >= 0 && next < frames.length && frames[next] == null);
            if (next >= 0 && next < frames.length) index = next;
            status = Ui.tr("Pausado", "Paused", "En pausa");
            invalidate();
        }

        void togglePlay() {
            playing = !playing;
            status = playing ? Ui.tr("Tocando", "Playing", "Reproduciendo") : Ui.tr("Pausado", "Paused", "En pausa");
            schedule();
            invalidate();
        }

        void stop() {
            stopped = true;
            main.removeCallbacks(advance);
            io.shutdownNow();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(night ? 0xFF1B1B1D : 0xFFE8E8EC);
            MapTiles.Map m = map;
            if (m != null) c.drawBitmap(m.picture, 0, 0, image);
            if (index < frames.length && frames[index] != null) {
                r.set(0, 0, w, h);
                image.setAlpha(Math.round(255 * 0.85f));
                c.drawBitmap(frames[index], null, r, image);
                image.setAlpha(255);
            }
            if (names != null) c.drawBitmap(names, 0, 0, image);
            // Home: a blue dot with a white ring and a soft halo.
            if (m != null) {
                double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
                float x = m.x(lat, lon), y = m.y(lat, lon);
                fill.setColor(0x2E0A84FF);
                c.drawCircle(x, y, 36 * k, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(x, y, 18 * k, fill);
                fill.setColor(0xFF0A84FF);
                c.drawCircle(x, y, 12 * k, fill);
            }
            // Top: the frame's glass card.
            r.set(80 * k, 60 * k, 520 * k, 268 * k);
            glass(c, r, 30 * k);
            c.drawText(Ui.tr("Radar · ", "Radar · ", "Radar · ") + (city.isEmpty() ? Ui.tr("Casa", "Home", "Casa") : city), r.left + 32 * k, r.top + 52 * k, cap);
            if (index < times.size()) {
                try {
                    long at = Radar.utc(times.get(index));
                    c.drawText(Ui.time(at), r.left + 30 * k, r.top + 124 * k, time);
                    long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
                    String when = (index == times.size() - 1 ? Ui.tr("mais recente, ", "latest, ", "más reciente, ") : "")
                            + Ui.tr("há " + min + " min", min + " min ago", "hace " + min + " min");
                    c.drawText(src.label() + " · " + when, r.left + 32 * k, r.top + 164 * k, ago);
                } catch (java.text.ParseException ignored) {
                }
                // The hour as a bar: loaded frames tinted, the one shown solid.
                float bx = r.left + 32 * k, bw = (r.width() - 64 * k - 6 * k * (frames.length - 1)) / Math.max(1, frames.length);
                for (int i = 0; i < frames.length; i++) {
                    fill.setColor(i == index ? 0xFF7C8CFF : frames[i] != null ? 0x737C8CFF : (night ? 0x24FFFFFF : 0x1A000000));
                    float x = bx + i * (bw + 6 * k);
                    c.drawRoundRect(x, r.top + 186 * k, x + bw, r.top + 194 * k, 4 * k, 4 * k, fill);
                }
            }
            // The legend, top right.
            Bitmap lg = legend;
            if (lg != null) {
                float lw = Math.min(300 * k, lg.getWidth() * k * 1.5f), lh = lw * lg.getHeight() / lg.getWidth();
                r.set(w - 80 * k - lw - 48 * k, 60 * k, w - 80 * k, 60 * k + 64 * k + Math.min(lh, 300 * k));
                glass(c, r, 24 * k);
                c.drawText(src.legendTitle(), r.left + 24 * k, r.top + 40 * k, small);
                img.set(r.left + 24 * k, r.top + 56 * k, r.left + 24 * k + lw, r.top + 56 * k + Math.min(lh, 300 * k));
                c.drawBitmap(lg, null, img, image);
            }
            // Bottom: how it's going, the keys, the credits and (on Google's map) Google's logo.
            c.drawText(status, 80 * k, h - 76 * k, foot);
            String keys = Ui.tr("← → passos · OK pausa · Voltar fecha", "← → step · OK pause · Back close", "← → pasos · OK pausa · Atrás cierra");
            c.drawText(keys, w - 80 * k - foot.measureText(keys), h - 76 * k, foot);
            c.drawText(Ui.tr("Radar: ", "Radar: ", "Radar: ") + src.credit() + (m == null ? "" : " · " + (m.google ? "Google Maps · " : "") + m.credit),
                    w - 80 * k, h - 24 * k, credit);
            Bitmap logo = m != null && m.google ? MapTiles.logoIfLoaded() : null;
            if (logo != null) {
                float lh = 36 * k, lw = lh * logo.getWidth() / logo.getHeight();
                r.set(80 * k, h - 24 * k - lh, 80 * k + lw, h - 24 * k);
                c.drawBitmap(logo, null, r, image);
            }
        }

        /** The home screen's card glass (white by day, smoked at night), flat as on Android 9 to 12. */
        private void glass(Canvas c, RectF box, float radius) {
            fill.setColor(night ? 0xB3111113 : 0xD9FFFFFF);
            c.drawRoundRect(box, radius, radius, fill);
            fill.setColor(night ? 0x24FFFFFF : 0xCCFFFFFF);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * k);
            c.drawRoundRect(box, radius, radius, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }
}
