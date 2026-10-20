package com.cloudmusictool.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/**
 * 目标进程 -> 模块 App 的 Hook 状态回写通道。
 *
 * 为什么需要它：libxposed 的 remote preferences 在宿主进程是只读的，
 * 目标进程无法把运行时状态写回去；这里用一个 exported ContentProvider 接收
 * 目标进程的 call("report")，并在 provider 内校验调用方 UID 必须属于
 * com.netease.cloudmusic，其他 UID 一律拒绝。
 */
public class StatusProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".status";
    public static final String KEY_JSON = "json";
    public static final String KEY_OK = "ok";

    static final String PREFS = "hook_status";
    static final String KEY_STATE = "state";

    private static final String TAG = "CloudMusicTool";
    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";
    private static final int MAX_JSON_CHARS = 64 * 1024;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return handle(method, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return handle(method, extras);
    }

    private Bundle handle(String method, Bundle extras) {
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        int uid = Binder.getCallingUid();
        if (!isTargetUid(uid)) {
            Log.w(TAG, "status report rejected: uid=" + uid);
            return null;
        }
        String json = extras.getString(KEY_JSON);
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_CHARS) {
            Log.w(TAG, "status report rejected: invalid payload");
            return null;
        }
        try {
            prefs().edit().putString(KEY_STATE, json).apply();
        } catch (Throwable t) {
            Log.w(TAG, "status store failed", t);
            return null;
        }
        Bundle out = new Bundle();
        out.putBoolean(KEY_OK, true);
        return out;
    }

    private boolean isTargetUid(int uid) {
        try {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String pkg : packages) {
                if (TARGET_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "uid check failed", t);
        }
        return false;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 模块 App 内读取最近一次状态 JSON。 */
    public static String readState(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STATE, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clearState(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_STATE).apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
