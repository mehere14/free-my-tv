package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plan a route (the home screen's box): say or type a place, Google Maps' matches near home appear as a list and as
 * numbered pins, and picking one shows the drive from home with live traffic, drawn on the map. Back goes from a
 * route to the matches, then closes. The list is plain views (TV keyboard, focus); the map is drawn (MapTiles).
 */
public final class PlanActivity extends Activity {
    /** The panel's width and margin (dp): the map's open area starts right of it. */
    private static final int PANEL_W = 320, PANEL_X = 40;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean night;
    private int ink, dim;
    private MapPanel map;
    private EditText search;
    private TextView mic, header, foot;
    private LinearLayout body;
    private List<Places.Place> places = new ArrayList<>();
    private Places.Place chosen;
    private int asking;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        night = HomeData.get(this).isNight(System.currentTimeMillis());
        ink = night ? 0xFFF5F5F7 : 0xFF1D1D1F;
        dim = night ? 0xADFFFFFF : 0xFF6E6E73;
        setContentView(build());
        hint(Ui.tr("Aperte OK no microfone e diga um lugar, como “Costco” ou “Wasaga Beach”. Ou OK na caixa para digitar.",
                "Press OK on the mic and say a place, like “Costco” or “Wasaga Beach”. Or press OK on the box to type.",
                "Pulsa OK en el micrófono y di un lugar, como “Costco” o “Wasaga Beach”. O OK en la caja para escribir."));
        mic.requestFocus();
        map.show(new ArrayList<>(), null, -1);
    }

    @Override protected void onDestroy() {
        asking++;
        voice.destroy();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (chosen != null && !places.isEmpty()) {
            chosen = null;
            showPlaces();
            return;
        }
        super.onBackPressed();
    }

    // MARK: - Layout

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
        map = new MapPanel(this);
        root.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(14), dp(12), dp(10));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(night ? 0xE6111113 : 0xEBFFFFFF);
        glass.setStroke(dp(1), night ? 0x24FFFFFF : 0xCCFFFFFF);
        glass.setCornerRadius(dp(18));
        panel.setBackground(glass);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(dp(PANEL_W), ViewGroup.LayoutParams.MATCH_PARENT);
        pLp.setMargins(dp(PANEL_X), dp(30), 0, dp(30));
        root.addView(panel, pLp);

        header = text(12.5f, Ui.semibold, dim);
        String city = Prefs.city().isEmpty() ? Ui.tr("casa", "home", "casa") : Prefs.city();
        header.setText(Ui.tr("Planejar rota a partir de ", "Plan a route from ", "Planear ruta desde ") + city);
        header.setPadding(dp(4), 0, 0, dp(10));
        panel.addView(header);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        search.setTypeface(Ui.medium);
        search.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF1C1C1E, ink}));
        search.setHintTextColor(dim);
        search.setHint(Ui.tr("Pesquisar no Google Maps", "Search Google Maps", "Buscar en Google Maps"));
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(pill());
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setOnEditorActionListener((v, id, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || enter) {
                find(search.getText().toString());
                return true;
            }
            return false;
        });
        search.setOnClickListener(v -> showKeyboard());
        row.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mic = text(13, Ui.semibold, ink);
        mic.setText(Ui.tr("Falar", "Speak", "Hablar"));
        mic.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFF1C1C1E, ink}));
        mic.setPadding(dp(14), dp(9), dp(14), dp(9));
        mic.setBackground(pill());
        mic.setFocusable(true);
        mic.setOnClickListener(v -> listen());
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.leftMargin = dp(6);
        row.addView(mic, mLp);
        panel.addView(row);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bLp.topMargin = dp(10);
        panel.addView(body, bLp);

        foot = text(10.5f, Ui.medium, night ? 0x99FFFFFF : 0xFF8E8E93);
        foot.setText(Ui.tr("Lugares e rotas: Google Maps · trânsito ao vivo", "Places and routes: Google Maps · live traffic", "Lugares y rutas: Google Maps · tráfico en vivo"));
        foot.setPadding(dp(4), dp(6), 0, 0);
        panel.addView(foot);
        return root;
    }

    private StateListDrawable pill() {
        GradientDrawable rest = new GradientDrawable(), focus = new GradientDrawable();
        rest.setColor(night ? 0x33FFFFFF : 0x8CFFFFFF);
        rest.setStroke(dp(1), night ? 0x24FFFFFF : 0xCCFFFFFF);
        focus.setColor(0xFFFFFFFF);
        rest.setCornerRadius(dp(17));
        focus.setCornerRadius(dp(17));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, focus);
        d.addState(new int[] {}, rest);
        return d;
    }

    /** A focusable row in the panel: white when focused, like the home screen's. */
    private LinearLayout row(View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable rest = new GradientDrawable(), focus = new GradientDrawable();
        rest.setColor(night ? 0x0FFFFFFF : 0x59FFFFFF);
        focus.setColor(0xFFF5F5F7);
        rest.setCornerRadius(dp(10));
        focus.setCornerRadius(dp(10));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, focus);
        d.addState(new int[] {}, rest);
        r.setBackground(d);
        r.setFocusable(true);
        r.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        r.setLayoutParams(lp);
        return r;
    }

    private void hint(String s) {
        body.removeAllViews();
        TextView t = text(12.5f, Ui.medium, dim);
        t.setLineSpacing(dp(4), 1);
        t.setText(s);
        t.setPadding(dp(4), dp(6), dp(4), 0);
        body.addView(t);
    }

    // MARK: - Finding places

    private void find(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        hideKeyboard();
        chosen = null;
        final int id = ++asking;
        hint(Ui.tr("Procurando “", "Searching Google Maps for “", "Buscando “") + text + Ui.tr("” no Google Maps…", "”…", "” en Google Maps…"));
        Context app = getApplicationContext();
        final String q = text;
        io.execute(() -> {
            try {
                List<Places.Place> found = Places.search(app, q);
                main.post(() -> {
                    if (id != asking || isFinishing()) return;
                    places = found;
                    if (found.isEmpty()) {
                        hint(Ui.tr("O Google Maps não achou nada para “", "Google Maps found nothing for “", "Google Maps no encontró nada para “") + q
                                + Ui.tr("”. Tente outro nome.", "”. Try another name.", "”. Prueba otro nombre."));
                        return;
                    }
                    showPlaces();
                });
            } catch (Exception e) {
                main.post(() -> { if (id == asking) hint(e.getMessage()); });
            }
        });
    }

    private void showPlaces() {
        body.removeAllViews();
        double hl = Places.home(true), hn = Places.home(false);
        for (int i = 0; i < places.size(); i++) {
            Places.Place p = places.get(i);
            final int at = i;
            LinearLayout r = row(v -> pick(at));
            TextView n = text(10.5f, Ui.semibold, 0xFFFFFFFF);
            n.setText(String.valueOf(i + 1));
            n.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFEA4335);
            n.setBackground(dot);
            r.addView(n, new LinearLayout.LayoutParams(dp(20), dp(20)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(10), 0, dp(8), 0);
            TextView name = text(13.5f, Ui.semibold, ink), address = text(11, Ui.medium, dim);
            name.setText(p.name);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setText(p.address);
            address.setSingleLine(true);
            address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setPadding(0, dp(3), 0, 0);
            words.addView(name);
            words.addView(address);
            r.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView km = text(11, Ui.medium, dim);
            double d = km(hl, hn, p.lat, p.lon);
            km.setText(String.format(Locale.US, d < 10 ? "%.1f km" : "%.0f km", d));
            r.addView(km);
            r.setOnFocusChangeListener((v, has) -> {
                int black = 0xFF1C1C1E;
                name.setTextColor(has ? black : ink);
                if (has) map.select(at);
            });
            body.addView(r);
        }
        map.show(places, null, -1);
        main.post(() -> { if (body.getChildCount() > 0) body.getChildAt(0).requestFocus(); });
    }

    /** Straight-line km (to tell the matches apart before asking for a route). */
    private static double km(double la1, double lo1, double la2, double lo2) {
        double r = Math.PI / 180, x = Math.pow(Math.sin((la2 - la1) * r / 2), 2)
                + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.pow(Math.sin((lo2 - lo1) * r / 2), 2);
        return 12742 * Math.asin(Math.sqrt(x));
    }

    private void pick(int i) {
        Places.Place p = places.get(i);
        chosen = p;
        final int id = ++asking;
        body.removeAllViews();
        TextView name = text(17, Ui.semibold, ink), address = text(11.5f, Ui.medium, dim), wait = text(13, Ui.medium, dim);
        name.setText(p.name);
        address.setText(p.address);
        address.setPadding(0, dp(3), 0, 0);
        wait.setText(Ui.tr("Calculando o trajeto com trânsito ao vivo…", "Finding the drive with live traffic…", "Calculando el trayecto con tráfico en vivo…"));
        wait.setPadding(0, dp(14), 0, 0);
        body.addView(name);
        body.addView(address);
        body.addView(wait);
        Context app = getApplicationContext();
        io.execute(() -> {
            try {
                Places.Route r = Places.route(app, p);
                main.post(() -> { if (id == asking && !isFinishing()) showRoute(p, r); });
            } catch (Exception e) {
                main.post(() -> {
                    if (id != asking) return;
                    wait.setText(e.getMessage());
                    addBack();
                });
            }
        });
    }

    private void showRoute(Places.Place p, Places.Route r) {
        body.removeAllViews();
        TextView name = text(17, Ui.semibold, ink), address = text(11.5f, Ui.medium, dim);
        name.setText(p.name);
        address.setText(p.address);
        address.setPadding(0, dp(3), 0, 0);
        body.addView(name);
        body.addView(address);
        TextView time = text(40, Ui.thin, ink);
        time.setText(duration(r.seconds));
        time.setPadding(0, dp(14), 0, 0);
        body.addView(time);
        int delay = r.seconds - r.staticSeconds;
        float ratio = r.staticSeconds > 0 ? delay / (float) r.staticSeconds : 0;
        TextView traffic = text(12.5f, Ui.medium, dim);
        String color = ratio < 0.1f ? "#30D158" : ratio < 0.3f ? "#FF9F0A" : "#FF453A";
        traffic.setText(android.text.Html.fromHtml("<font color='" + color + "'>●</font>  " + (delay >= 120
                ? "+" + duration(delay) + Ui.tr(" de trânsito", " from traffic", " de tráfico") : Ui.tr("Sem atrasos", "No traffic delays", "Sin demoras"))));
        traffic.setPadding(0, dp(4), 0, 0);
        body.addView(traffic);
        TextView facts = text(12.5f, Ui.medium, ink);
        facts.setLineSpacing(dp(5), 1);
        String km = String.format(Locale.US, r.meters < 10000 ? "%.1f km" : "%.0f km", r.meters / 1000f);
        facts.setText(km + (r.via.isEmpty() ? "" : Ui.tr(" · por ", " · via ", " · por ") + r.via) + "\n"
                + Ui.tr("Chega por volta de ", "Arrive about ", "Llegas hacia las ") + Ui.time(System.currentTimeMillis() + r.seconds * 1000L)
                + Ui.tr(" saindo agora", " if you leave now", " si sales ahora"));
        facts.setPadding(0, dp(12), 0, 0);
        body.addView(facts);
        addBack();
        foot.setText(Ui.tr("Trânsito ao vivo · ", "Live traffic · ", "Tráfico en vivo · ") + Ui.time(System.currentTimeMillis()));
        map.show(places, r.path, -1);
    }

    private void addBack() {
        LinearLayout back = row(v -> onBackPressed());
        TextView t = text(14, Ui.medium, ink);
        t.setText(Ui.tr("Voltar aos resultados", "Back to the matches", "Volver a los resultados"));
        back.setOnFocusChangeListener((v, has) -> t.setTextColor(has ? 0xFF1C1C1E : ink));
        back.addView(t);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) back.getLayoutParams();
        lp.topMargin = dp(14);
        body.addView(back);
        back.requestFocus();
    }

    private static String duration(int s) {
        return s >= 3600 ? s / 3600 + " h " + Math.round((s % 3600) / 60f) + " min" : Math.max(1, Math.round(s / 60f)) + " min";
    }

    // MARK: - Voice and keyboard

    private void showKeyboard() {
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** The on-screen Speak button: listens until you stop talking. */
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
            hint(text);
        }
        @Override public void heard(String text, boolean done) {
            search.setText(text);
            if (done) find(text);
        }
        @Override public void failed(String why) {
            hideKeyboard();
            hint(why);
            mic.requestFocus();
        }
    });

    // MARK: - The map

    /**
     * Google's map (Esri's without a key) fitted to what's shown, right of the panel: home, the matches as numbered
     * pins (the focused one blue), or a route's path in Google blue. Rebuilt in the background when that changes.
     */
    final class MapPanel extends View {
        private final ExecutorService tiles = Executors.newSingleThreadExecutor();
        private volatile MapTiles.Map shown;
        private List<Places.Place> pins = new ArrayList<>();
        private List<double[]> path;
        private int selected = -1, version;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                line = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), credit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path route = new Path();
        private final RectF r = new RectF();

        MapPanel(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            label.setTypeface(Ui.semibold);
            label.setColor(0xFFFFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            credit.setTypeface(Ui.regular);
            credit.setColor(0xFF5F6368);
            credit.setTextAlign(Paint.Align.RIGHT);
        }

        void select(int i) {
            selected = i;
            invalidate();
        }

        /** What to show: the map is fitted to home and these places (or this path) and rebuilt. */
        void show(List<Places.Place> places, List<double[]> pathPoints, int focus) {
            pins = places;
            path = pathPoints;
            selected = focus;
            final int v = ++version;
            post(() -> {
                int w = getWidth(), h = getHeight();
                if (w == 0) return;
                List<double[]> pts = new ArrayList<>();
                pts.add(new double[] {Places.home(true), Places.home(false)});
                if (pathPoints != null) pts.addAll(pathPoints);
                else for (Places.Place p : places) pts.add(new double[] {p.lat, p.lon});
                if (pts.size() == 1) {   // just home: a few km around it
                    pts.add(new double[] {pts.get(0)[0] + 0.05, pts.get(0)[1] + 0.08});
                    pts.add(new double[] {pts.get(0)[0] - 0.05, pts.get(0)[1] - 0.08});
                }
                float panel = dp(PANEL_X + PANEL_W);
                double roomW = w - panel - dp(70), roomH = h - dp(110);
                int z = 15;
                for (; z > 3; z--) {
                    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                    for (double[] p : pts) {
                        double x = MapTiles.worldX(p[1], z), y = MapTiles.worldY(p[0], z);
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                    if (maxX - minX <= roomW && maxY - minY <= roomH) break;
                }
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (double[] p : pts) {
                    double x = MapTiles.worldX(p[1], z), y = MapTiles.worldY(p[0], z);
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
                // The points' middle lands in the middle of the open area right of the panel.
                double middle = panel + (w - panel) / 2.0;
                double cx = (minX + maxX) / 2 - (middle - w / 2.0), cy = (minY + maxY) / 2;
                final int zoom = z;
                final double lat = MapTiles.lat(cy, zoom), lon = MapTiles.lon(cx, zoom);
                tiles.execute(() -> {
                    try {
                        MapTiles.Map m = MapTiles.build(getContext().getApplicationContext(), lat, lon, zoom, w, h, false);
                        Bitmap names = MapTiles.names(m, false);
                        if (names != null) new Canvas(m.picture).drawBitmap(names, 0, 0, null);
                        if (m.google) MapTiles.logo();
                        post(() -> { if (v == version) { shown = m; invalidate(); } });
                    } catch (Exception e) {
                        AppLog.w("plan map: " + e.getMessage());
                    }
                });
            });
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFFE8E8EC);
            MapTiles.Map m = shown;
            if (m == null) return;
            c.drawBitmap(m.picture, 0, 0, image);
            if (path != null && path.size() > 1) {
                route.reset();
                for (int i = 0; i < path.size(); i++) {
                    float x = m.x(path.get(i)[0], path.get(i)[1]), y = m.y(path.get(i)[0], path.get(i)[1]);
                    if (i == 0) route.moveTo(x, y); else route.lineTo(x, y);
                }
                line.setColor(0xFFFFFFFF);
                line.setStrokeWidth(dp(9));
                c.drawPath(route, line);
                line.setColor(0xFF1A73E8);
                line.setStrokeWidth(dp(5));
                c.drawPath(route, line);
                double[] end = path.get(path.size() - 1);
                pin(c, m.x(end[0], end[1]), m.y(end[0], end[1]), "", false);
            } else {
                for (int i = 0; i < pins.size(); i++) {
                    Places.Place p = pins.get(i);
                    if (i != selected) pin(c, m.x(p.lat, p.lon), m.y(p.lat, p.lon), String.valueOf(i + 1), false);
                }
                if (selected >= 0 && selected < pins.size()) {
                    Places.Place p = pins.get(selected);
                    pin(c, m.x(p.lat, p.lon), m.y(p.lat, p.lon), String.valueOf(selected + 1), true);
                }
            }
            // Home: the blue dot.
            float hx = m.x(Places.home(true), Places.home(false)), hy = m.y(Places.home(true), Places.home(false));
            fill.setColor(0x2E0A84FF);
            c.drawCircle(hx, hy, dp(16), fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(hx, hy, dp(8), fill);
            fill.setColor(0xFF0A84FF);
            c.drawCircle(hx, hy, dp(5.5f), fill);
            // Google's terms: the logo and the copyright line on their map, uncovered (right of the panel).
            credit.setTextSize(dp(9));
            c.drawText((m.google ? "Google Maps · " : "") + m.credit, w - dp(40), h - dp(10), credit);
            Bitmap logo = m.google ? MapTiles.logoIfLoaded() : null;
            if (logo != null) {
                float lh = dp(18), lw = lh * logo.getWidth() / logo.getHeight(), x = dp(PANEL_X + PANEL_W + 20);
                r.set(x, h - dp(10) - lh, x + lw, h - dp(10));
                c.drawBitmap(logo, null, r, image);
            }
        }

        private void pin(Canvas c, float x, float y, String n, boolean on) {
            float radius = dp(on ? 11 : 9);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, radius + dp(2.5f), fill);
            fill.setColor(on ? 0xFF1A73E8 : 0xFFEA4335);
            c.drawCircle(x, y, radius, fill);
            if (!n.isEmpty()) {
                label.setTextSize(dp(10));
                c.drawText(n, x, y + dp(3.5f), label);
            }
        }
    }
}
