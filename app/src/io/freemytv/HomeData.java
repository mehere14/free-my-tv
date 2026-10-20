package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather, sunrise and sunset, cached so the screen opens with numbers: Google Weather (once an hour) when there's a
 * Google Maps Platform key (Keys), else, or when Google fails, Open-Meteo (every 30 min, no key). Only fetched while
 * the home screen or the screensaver is visible (the screen asks). The location comes from config.properties.
 */
final class HomeData {
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long WEATHER_EVERY = 30 * 60_000L;
    /** Google's Weather API: 10,000 free calls a month, 3 per refresh; once an hour is ~2,200 even left on all day. */
    private static final long GOOGLE_EVERY = 60 * 60_000L;
    private static final String GOOGLE = "https://weather.googleapis.com/v1/";
    interface Listener { void onData(); }

    /** Settings changed (the city): fetch again now. */
    void settingsChanged() {
        weatherAt = 0;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        /** The highest chance of rain or snow (%) over those hours. */
        int rainChance;
        /** Google's own words for the sky ("Partly cloudy"); empty with Open-Meteo (the app's words from `code`). */
        String text = "";
        String source = "Open-Meteo";
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    volatile long weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final Context app;
    private final ExecutorService io = Ui.background();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingWeather;
    private volatile long googleTriedAt;

    private HomeData(Context c) {
        app = c;
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        boolean hasKey = !Keys.maps(app).isEmpty(), google = "Google Weather".equals(weather.source);
        long every = hasKey && google ? GOOGLE_EVERY : WEATHER_EVERY;
        // A key just arrived (or Google failed a while ago): try Google now instead of waiting for Open-Meteo's turn.
        boolean upgrade = hasKey && !google && now - googleTriedAt > 10 * 60_000L;
        if (!fetchingWeather && (upgrade || now - weatherAt > every || now < weatherAt)) {
            fetchingWeather = true;
            if (hasKey) googleTriedAt = now;
            io.execute(() -> {
                try {
                    String body = null;
                    String key = Keys.maps(app);
                    if (!key.isEmpty()) {
                        try {
                            body = googleBody(key);
                            parseWeather(body);   // Google's answer must make sense before it replaces anything
                        } catch (Exception e) {
                            AppLog.w("Google Weather: " + e.getMessage() + " (Open-Meteo instead)");
                            body = null;
                        }
                    }
                    if (body == null) body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("google")) return parseGoogle(o.getJSONObject("google"));
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            JSONArray chances = hourly.optJSONArray("precipitation_probability");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
                if (chances != null) w.rainChance = Math.max(w.rainChance, chances.optInt(i, 0));
            }
        }
        return w;
    }

    /** Google Weather's three answers (now, the next hours, today and tomorrow), kept together as one cached body. */
    private static String googleBody(String key) throws Exception {
        String q = "?location.latitude=" + Prefs.latitude() + "&location.longitude=" + Prefs.longitude()
                + "&unitsSystem=METRIC&languageCode=" + Ui.locale().toLanguageTag();
        JSONObject all = new JSONObject();
        all.put("now", new JSONObject(get(GOOGLE + "currentConditions:lookup" + q, key)));
        all.put("hours", new JSONObject(get(GOOGLE + "forecast/hours:lookup" + q + "&hours=8&pageSize=8", key)));
        all.put("days", new JSONObject(get(GOOGLE + "forecast/days:lookup" + q + "&days=1&pageSize=1", key)));
        return new JSONObject().put("google", all).toString();
    }

    private static Weather parseGoogle(JSONObject g) throws Exception {
        Weather w = new Weather();
        w.source = "Google Weather";
        JSONObject now = g.getJSONObject("now"), cond = now.getJSONObject("weatherCondition");
        w.temp = now.getJSONObject("temperature").getDouble("degrees");
        w.isDay = now.optBoolean("isDaytime", true);
        w.code = googleCode(cond.optString("type"));
        w.text = cond.optJSONObject("description") != null ? cond.getJSONObject("description").optString("text") : "";
        JSONObject today = g.getJSONObject("days").getJSONArray("forecastDays").getJSONObject(0);
        w.max = today.getJSONObject("maxTemperature").getDouble("degrees");
        w.min = today.getJSONObject("minTemperature").getDouble("degrees");
        JSONObject sun = today.getJSONObject("sunEvents");
        w.sunrise = utc(sun.getString("sunriseTime"));
        w.sunset = utc(sun.getString("sunsetTime"));
        JSONArray hours = g.getJSONObject("hours").getJSONArray("forecastHours");
        long t = System.currentTimeMillis();
        for (int i = 0; i < hours.length() && w.hours.size() < 6; i++) {
            JSONObject h = hours.getJSONObject(i);
            long at = utc(h.getJSONObject("interval").getString("startTime"));
            if (at <= t) continue;
            w.hours.add(at);
            w.hourTemp.add(h.getJSONObject("temperature").getDouble("degrees"));
            w.hourCode.add(googleCode(h.getJSONObject("weatherCondition").optString("type")));
            w.hourDay.add(h.optBoolean("isDaytime", true));
            JSONObject rain = h.optJSONObject("precipitation");
            if (rain != null && rain.optJSONObject("probability") != null) w.rainChance = Math.max(w.rainChance, rain.getJSONObject("probability").optInt("percent"));
        }
        return w;
    }

    /** Google's WeatherCondition type as the WMO code the app's symbols and words use (Symbols.weather, condition). */
    static int googleCode(String type) {
        if (type.equals("CLEAR")) return 0;
        if (type.equals("MOSTLY_CLEAR")) return 1;
        if (type.equals("PARTLY_CLOUDY")) return 2;
        if (type.contains("THUNDER")) return 95;
        if (type.contains("SNOW")) return 73;
        if (type.contains("HEAVY") || type.equals("MODERATE_TO_HEAVY_RAIN") || type.equals("RAIN_PERIODICALLY_HEAVY")) return 65;
        if (type.contains("RAIN") || type.contains("SHOWER") || type.contains("HAIL")) return 61;
        return 3;   // MOSTLY_CLOUDY, CLOUDY, WINDY
    }

    /** "2026-10-20T19:00:00.123456789Z" (UTC, any fraction) in epoch ms. */
    private static long utc(String iso) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(iso.substring(0, 19)).getTime();
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String get(String url) throws IOException {
        return get(url, null);
    }

    /** GET; with a key, it goes in Google's header (never the URL, never the log). */
    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        if (key != null) c.setRequestProperty("X-Goog-Api-Key", key);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (Ui.es) {
            if (code == 0) return day ? "Despejado" : "Noche despejada";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
            if (code >= 95) return "Tormenta";
            return "Lluvia";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.en ? new SimpleDateFormat("h a", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat(Ui.es ? "HH:mm" : "HH'h'", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        if (Ui.es) return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}
