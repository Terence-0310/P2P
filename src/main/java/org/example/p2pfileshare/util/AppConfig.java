// java
package org.example.p2pfileshare.util;

import java.util.prefs.Preferences;

public final class AppConfig {
    private static final Preferences PREFS = Preferences.userNodeForPackage(AppConfig.class);

    private AppConfig() {}

    public static void save(String key, String value) {
        if (key != null && value != null) {
            PREFS.put(key, value);
        }
    }

    public static String load(String key) {
        if (key == null) return null;
        return PREFS.get(key, null);
    }

    public static void remove(String key) {
        if (key != null) {
            PREFS.remove(key);
        }
    }
}
