package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan a route's two Google calls, with the Google Maps Platform key (Keys): Places Text Search (New) for what was
 * said or typed, nearest home first, and Routes computeRoutes for the drive there with live traffic. Background
 * threads only. Billing: names, addresses and locations are Text Search Pro (5,000 free a month); traffic-aware
 * routes are Routes Pro (5,000 free a month).
 */
final class Places {
    private Places() {}

    static final class Place {
        String id, name, address;
        double lat, lon;
    }

    static final class Route {
        int seconds, staticSeconds, meters;
        String via = "";
        final List<double[]> path = new ArrayList<>();
    }

    static List<Place> search(Context c, String text) throws IOException {
        String key = key(c);
        try {
            JSONObject body = new JSONObject().put("textQuery", text).put("pageSize", 6).put("languageCode", Ui.locale().getLanguage())
                    .put("locationBias", new JSONObject().put("circle", new JSONObject()
                            .put("center", new JSONObject().put("latitude", home(true)).put("longitude", home(false)))
                            .put("radius", 50000.0)));
            JSONObject res = post("https://places.googleapis.com/v1/places:searchText", key,
                    "places.id,places.displayName,places.shortFormattedAddress,places.formattedAddress,places.location", body);
            List<Place> out = new ArrayList<>();
            JSONArray list = res.optJSONArray("places");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.getJSONObject(i);
                if (p.optJSONObject("location") == null) continue;
                Place place = new Place();
                place.id = p.getString("id");
                place.name = p.optJSONObject("displayName") != null ? p.getJSONObject("displayName").optString("text") : "";
                place.address = p.optString("shortFormattedAddress", p.optString("formattedAddress"));
                place.lat = p.getJSONObject("location").getDouble("latitude");
                place.lon = p.getJSONObject("location").getDouble("longitude");
                out.add(place);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from Google Places");
        }
    }

    static Route route(Context c, Place to) throws IOException {
        String key = key(c);
        try {
            JSONObject body = new JSONObject()
                    .put("origin", new JSONObject().put("location", new JSONObject().put("latLng",
                            new JSONObject().put("latitude", home(true)).put("longitude", home(false)))))
                    .put("destination", new JSONObject().put("placeId", to.id))
                    .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_AWARE").put("units", "METRIC")
                    .put("languageCode", Ui.locale().toLanguageTag());
            JSONObject res = post("https://routes.googleapis.com/directions/v2:computeRoutes", key,
                    "routes.duration,routes.staticDuration,routes.distanceMeters,routes.description,routes.polyline.encodedPolyline", body);
            JSONArray routes = res.optJSONArray("routes");
            if (routes == null || routes.length() == 0) throw new IOException(Ui.tr("O Google não tem rota de carro até lá.",
                    "Google has no driving route from home to there.", "Google no tiene una ruta en auto hasta allí."));
            JSONObject r = routes.getJSONObject(0);
            Route out = new Route();
            out.seconds = seconds(r.optString("duration", "0s"));
            out.staticSeconds = seconds(r.optString("staticDuration", r.optString("duration", "0s")));
            out.meters = r.optInt("distanceMeters");
            out.via = r.optString("description");
            decode(r.getJSONObject("polyline").getString("encodedPolyline"), out.path);
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from Google Routes");
        }
    }

    private static String key(Context c) throws IOException {
        String key = Keys.maps(c);
        if (key.isEmpty()) throw new IOException(Ui.tr("Falta a chave do Google Maps: veja \"API keys\" no README.",
                "There's no Google Maps key yet: see \"API keys\" in the README.", "Falta la clave de Google Maps: ver \"API keys\" en el README."));
        return key;
    }

    static double home(boolean lat) {
        return Double.parseDouble(lat ? Prefs.latitude() : Prefs.longitude());
    }

    private static int seconds(String s) {
        try { return (int) Double.parseDouble(s.replace("s", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Google's encoded polyline as [lat, lon] points. */
    static void decode(String s, List<double[]> out) {
        int i = 0, lat = 0, lon = 0;
        while (i < s.length()) {
            for (int axis = 0; axis < 2; axis++) {
                int shift = 0, result = 0, b;
                do {
                    b = s.charAt(i++) - 63;
                    result |= (b & 31) << shift;
                    shift += 5;
                } while (b >= 32);
                int delta = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
                if (axis == 0) lat += delta; else lon += delta;
            }
            out.add(new double[] {lat / 1e5, lon / 1e5});
        }
    }

    private static JSONObject post(String url, String key, String fields, JSONObject body) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Goog-Api-Key", key);
        c.setRequestProperty("X-Goog-FieldMask", fields);
        try {
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "";
                try { msg = new JSONObject(text).getJSONObject("error").optString("message"); } catch (Exception ignored) {}
                throw new IOException("Google (" + code + "): " + (msg.isEmpty() ? "HTTP " + code : msg));
            }
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from Google");
        } finally {
            c.disconnect();
        }
    }

    private static String read(java.io.InputStream in) throws IOException {
        if (in == null) return "";
        try (java.io.InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
