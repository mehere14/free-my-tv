package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The API keys (Gemini, Google Maps Platform) and Ask AI's settings, on the TV. Values are encrypted with AES-GCM by a
 * key that lives in the Android Keystore (it never leaves the TV's secure hardware), the way Jetpack's
 * EncryptedSharedPreferences does it, without the library. Read and written by the :home process only.
 *
 * From the computer, without typing on the remote: push config.properties to /data/local/tmp/freemytv.properties
 * (owner-only), and the home screen imports its key lines through the TV's own ADB the next time it appears, then
 * deletes the file. Keys are never logged; the settings screen shows only their last 4 characters.
 */
final class Keys {
    private Keys() {}

    static final String GEMINI = "gemini_key", MODEL = "gemini_model", GROUNDING = "gemini_grounding", MAPS = "maps_key";
    static final String DEFAULT_MODEL = "gemini-flash-latest";
    /** Where the computer leaves config.properties for the import (only the shell user can read it). */
    static final String DROP = "/data/local/tmp/freemytv.properties";
    private static final String[] IMPORTED = {GEMINI, MODEL, GROUNDING, MAPS};
    private static final String ALIAS = "freemytv-keys";
    private static final Map<String, String> CACHE = new HashMap<>();

    static String gemini(Context c) { return get(c, GEMINI); }
    static String maps(Context c) { return get(c, MAPS); }
    static String model(Context c) {
        String m = get(c, MODEL);
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }
    static boolean grounding(Context c) { return !"false".equals(get(c, GROUNDING)); }

    /** "••••••••abcd", or "" when there's no key. */
    static String hint(String key) {
        return key.length() < 4 ? "" : "••••••••" + key.substring(key.length() - 4);
    }

    static synchronized String get(Context c, String name) {
        if (CACHE.containsKey(name)) return CACHE.get(name);
        String stored = prefs(c).getString(name, null), value = "";
        if (stored != null) {
            try {
                byte[] all = Base64.decode(stored, Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, all, 0, 12));
                value = new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
            } catch (Exception e) {
                AppLog.w("keys: couldn't read " + name + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        CACHE.put(name, value);
        return value;
    }

    /** Stores `value` encrypted (empty removes it). */
    static synchronized void put(Context c, String name, String value) {
        value = value == null ? "" : value.trim();
        SharedPreferences.Editor e = prefs(c).edit();
        if (value.isEmpty()) {
            e.remove(name);
        } else {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secret());
                byte[] iv = cipher.getIV(), sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                byte[] all = new byte[iv.length + sealed.length];
                System.arraycopy(iv, 0, all, 0, iv.length);
                System.arraycopy(sealed, 0, all, iv.length, sealed.length);
                e.putString(name, Base64.encodeToString(all, Base64.NO_WRAP));
            } catch (Exception ex) {
                AppLog.w("keys: couldn't store " + name + " (" + ex.getClass().getSimpleName() + ")");
                return;
            }
        }
        e.commit();
        CACHE.put(name, value);
    }

    /**
     * Imports the key lines of a config.properties the computer pushed to DROP, through the TV's own ADB (only the
     * shell can read that file), then deletes it. Returns how many settings were imported (0: no file). Background
     * thread only: it opens a socket.
     */
    static int importDrop(Context c) {
        // The app may see that the file is there (the folder lets anyone look up a known name) but not read it: only
        // then is it worth an ADB connection.
        if (!new java.io.File(DROP).exists()) return 0;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            String text = adb.shell("cat " + DROP + " 2>/dev/null");
            if (text.trim().isEmpty()) return 0;
            int count = 0;
            for (String line : text.split("\n")) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                String name = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
                for (String wanted : IMPORTED) {
                    if (!wanted.equals(name) || value.isEmpty()) continue;
                    put(c, name, value);
                    count++;
                }
            }
            adb.shell("rm -f " + DROP);
            AppLog.w("keys: imported " + count + " settings from the computer (file deleted)");
            return count;
        } catch (Exception e) {
            AppLog.w("keys: import failed (" + e.getMessage() + ")");
            return 0;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("keys", Context.MODE_PRIVATE);
    }

    private static SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return gen.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
