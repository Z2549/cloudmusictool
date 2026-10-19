package com.cloudmusictool.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 模块 App 侧的开关读写。
 * 必须写框架的 remote preferences：网易云音乐进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 */
final class ConfigStore {

    private static final String TAG = "CloudMusicTool";

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;

    ConfigStore(Context context) {
        mRemote = ServiceBridge.remotePreferences(Config.GROUP);
        mLocal = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
    }

    boolean remote() {
        return mRemote != null;
    }

    boolean get(String key, boolean def) {
        try {
            return (mRemote != null ? mRemote : mLocal).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    void put(String key, boolean value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            sp.edit().putBoolean(key, value).apply();
            if (mRemote == null) {
                Log.w(TAG, "config written to LOCAL prefs (framework not bound): " + key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "config write failed: " + key, t);
        }
    }

    Set<String> getStringSet(String key, Set<String> def) {
        SharedPreferences sp = mRemote != null ? mRemote : mLocal;
        try {
            Set<String> set = sp.getStringSet(key, null);
            if (set != null) {
                return new HashSet<>(set);
            }
        } catch (Throwable ignored) {
        }
        try {
            String raw = sp.getString(key, null);
            if (raw != null) {
                Set<String> parsed = new HashSet<>();
                for (String part : raw.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed);
                    }
                }
                return parsed;
            }
        } catch (Throwable ignored) {
        }
        return def != null ? def : Collections.emptySet();
    }

    void putStringSet(String key, Set<String> value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            SharedPreferences.Editor editor = sp.edit();
            if (value != null) {
                try {
                    editor.putStringSet(key, value);
                } catch (Throwable t) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : value) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(s);
                    }
                    editor.putString(key, sb.toString());
                }
            } else {
                editor.remove(key);
            }
            editor.apply();
        } catch (Throwable t) {
            Log.w(TAG, "config putStringSet failed: " + key, t);
        }
    }
}
