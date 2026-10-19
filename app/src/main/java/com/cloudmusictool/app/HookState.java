package com.cloudmusictool.app;

import android.util.Log;

/**
 * 目标进程内的诊断日志工具。
 */
public final class HookState {

    private static final String TAG = "CloudMusicTool";

    private HookState() {
    }

    public static void enable(Feature f) {
        Log.i(TAG, f.id + " enabled");
    }

    public static void installed(Feature f, String label) {
    }

    public static void markNotFound(Feature f) {
    }

    public static void hit(Feature f) {
    }

    public static void note(Feature f, String text) {
        if (text != null) {
            Log.i(TAG, f.id + " " + text);
        }
    }
}
