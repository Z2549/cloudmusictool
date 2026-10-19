package com.cloudmusictool.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 开关读取：有 PROP_CAP_REMOTE 才读 getRemotePreferences("config")，
 * 否则（含读取抛异常）回退到 Feature 的编译期内置默认值。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    public static final String KEY_ALLOW_UNSUPPORTED = "allow_unsupported_version";
    public static final boolean DEFAULT_ALLOW_UNSUPPORTED = false;

    public static final String KEY_HOME_BLOCKS_FILTER = "home_blocks_filter";
    public static final String KEY_TOP_CHANNELS_KEEP = "top_channels_keep";
    public static final String KEY_DRAWER_ITEMS_FILTER = "drawer_items_filter";
    public static final String KEY_BOTTOM_NAV_KEEP = "bottom_nav_keep";
    public static final String KEY_STARTUP_TASK_BLOCKLIST = "startup_task_blocklist";

    private static final String TAG = "CloudMusicTool";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    private boolean mAllowUnsupported = DEFAULT_ALLOW_UNSUPPORTED;
    private final Map<String, Set<String>> mCustomSets = new java.util.HashMap<>();

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    public static Config load(XposedModule module) {
        Config config = new Config();
        boolean remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return config;
        }
        try {
            SharedPreferences sp = module.getRemotePreferences(GROUP);
            if (sp != null) {
                for (Feature f : Feature.values()) {
                    config.mEnabled.put(f, sp.getBoolean(f.key, f.defaultEnabled));
                }
                config.mAllowUnsupported = sp.getBoolean(KEY_ALLOW_UNSUPPORTED, DEFAULT_ALLOW_UNSUPPORTED);
                loadSet(sp, KEY_HOME_BLOCKS_FILTER, config.mCustomSets);
                loadSet(sp, KEY_TOP_CHANNELS_KEEP, config.mCustomSets);
                loadSet(sp, KEY_DRAWER_ITEMS_FILTER, config.mCustomSets);
                loadSet(sp, KEY_BOTTOM_NAV_KEEP, config.mCustomSets);
                loadSet(sp, KEY_STARTUP_TASK_BLOCKLIST, config.mCustomSets);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
        }
        return config;
    }

    private static void loadSet(SharedPreferences sp, String key, Map<String, Set<String>> out) {
        try {
            Set<String> set = sp.getStringSet(key, null);
            if (set != null) {
                out.put(key, new HashSet<>(set));
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            String raw = sp.getString(key, null);
            if (raw != null && !raw.isEmpty()) {
                Set<String> parsed = new HashSet<>();
                for (String item : raw.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed);
                    }
                }
                out.put(key, parsed);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean enabled(Feature f) {
        return Boolean.TRUE.equals(mEnabled.get(f));
    }

    public boolean anyEnabled() {
        for (Map.Entry<Feature, Boolean> e : mEnabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().blocked()) {
                return true;
            }
        }
        return false;
    }

    public boolean allowUnsupportedVersion() {
        return mAllowUnsupported;
    }

    public Set<String> getFilterSet(String key) {
        Set<String> s = mCustomSets.get(key);
        return s != null ? s : Collections.emptySet();
    }
}
