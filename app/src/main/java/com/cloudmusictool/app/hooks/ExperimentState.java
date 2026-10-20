package com.cloudmusictool.app.hooks;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.cloudmusictool.app.StatusProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目标进程内的 ABTest 命中观测。
 *
 * 记录 key -> {group, source, count, lastAt}，最多跟踪 {@link #MAX_TRACKED_KEYS} 个 key，
 * 序列化时按 count 取前 {@link #MAX_KEYS} 个，并保证 JSON 不超过 64KB。
 *
 * 写入策略与 HookState 一致：记录时 2 秒节流（尾沿发送，避免丢掉最后一批），
 * 安装结束时由 CloudMusicToolModule 调 flush() 强制发送一次。
 * remote prefs 在宿主进程只读，因此通过模块 App 的 StatusProvider 回写（type=experiments）。
 */
public final class ExperimentState {

    private static final String TAG = "CloudMusicTool";
    private static final long PUBLISH_INTERVAL_MS = 2000L;
    private static final int MAX_TRACKED_KEYS = 500;
    private static final int MAX_KEYS = 200;
    private static final int MAX_JSON_CHARS = 64 * 1024;

    private static final Map<String, Item> sItems = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService sExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CloudMusicTool-ExperimentState");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);

    private static volatile String sModulePackage;
    private static volatile Context sTargetContext;
    private static volatile boolean sContextWarningLogged;
    private static volatile boolean sPublishFailureLogged;

    private ExperimentState() {
    }

    private static final class Item {
        volatile String group;
        volatile String source;
        final AtomicInteger count = new AtomicInteger();
        volatile long lastAt;

        synchronized void update(String g, String s) {
            if (g != null && !g.isEmpty()) {
                group = g;
            }
            if (s != null && !s.isEmpty()) {
                source = s;
            }
            count.incrementAndGet();
            lastAt = System.currentTimeMillis();
        }
    }

    /** 由 CloudMusicToolModule.onModuleLoaded 调用。 */
    public static void init(String modulePackage) {
        sModulePackage = modulePackage;
    }

    public static void record(String key, String group, String source) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            Item item = sItems.get(key);
            if (item == null) {
                if (sItems.size() >= MAX_TRACKED_KEYS) {
                    return;
                }
                Item created = new Item();
                Item prev = sItems.putIfAbsent(key, created);
                item = prev != null ? prev : created;
            }
            item.update(group, source);
            schedulePublish();
        } catch (Throwable t) {
            // 观测失败绝不影响宿主。
        }
    }

    /** 强制发送一次当前快照（不受 2 秒节流限制）；无数据时不发送，避免空快照覆盖。 */
    public static void flush() {
        if (sModulePackage == null || sModulePackage.isEmpty() || sItems.isEmpty()) {
            return;
        }
        try {
            sExecutor.execute(ExperimentState::doPublish);
        } catch (Throwable ignored) {
        }
    }

    private static void schedulePublish() {
        if (sModulePackage == null || sModulePackage.isEmpty()) {
            return;
        }
        if (!sScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            sExecutor.schedule(() -> {
                sScheduled.set(false);
                doPublish();
            }, PUBLISH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            sScheduled.set(false);
        }
    }

    private static String buildJson() {
        try {
            List<Map.Entry<String, Item>> entries = new ArrayList<>(sItems.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, Item>>() {
                @Override
                public int compare(Map.Entry<String, Item> a, Map.Entry<String, Item> b) {
                    return Integer.compare(b.getValue().count.get(), a.getValue().count.get());
                }
            });
            int limit = Math.min(entries.size(), MAX_KEYS);
            JSONArray items = new JSONArray();
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Item> e = entries.get(i);
                Item item = e.getValue();
                JSONObject o = new JSONObject();
                o.put("key", e.getKey());
                o.put("group", item.group == null ? "c" : item.group);
                o.put("source", item.source == null ? "unknown" : item.source);
                o.put("count", item.count.get());
                o.put("lastAt", item.lastAt);
                items.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("items", items);
            String json = root.toString();
            // 超限按 count 截断：entries 已按 count 降序，从尾部丢弃。
            while (json.length() > MAX_JSON_CHARS && items.length() > 0) {
                items.remove(items.length() - 1);
                root.put("items", items);
                json = root.toString();
            }
            return json;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void doPublish() {
        String pkg = sModulePackage;
        if (pkg == null || pkg.isEmpty() || sItems.isEmpty()) {
            return;
        }
        String json = buildJson();
        if (json == null) {
            return;
        }
        try {
            Context ctx = targetContext();
            if (ctx == null) {
                if (!sContextWarningLogged) {
                    sContextWarningLogged = true;
                    Log.w(TAG, "experiments not published: target Context unavailable");
                }
                return;
            }
            Bundle extras = new Bundle();
            extras.putString(StatusProvider.KEY_TYPE, StatusProvider.TYPE_EXPERIMENTS);
            extras.putString(StatusProvider.KEY_JSON, json);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + pkg + ".status"),
                    "report", null, extras);
        } catch (Throwable t) {
            if (!sPublishFailureLogged) {
                sPublishFailureLogged = true;
                Log.w(TAG, "experiments publish failed: " + t.getMessage());
            }
        }
    }

    /** 反射 ActivityThread.currentApplication()，成功一次后缓存（同 HookState）。 */
    private static Context targetContext() {
        Context cached = sTargetContext;
        if (cached != null) {
            return cached;
        }
        synchronized (ExperimentState.class) {
            if (sTargetContext != null) {
                return sTargetContext;
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) {
                    return null;
                }
                Object app = at.getMethod("getApplication").invoke(thread);
                if (app instanceof Context) {
                    sTargetContext = (Context) app;
                }
            } catch (Throwable t) {
                return null;
            }
            return sTargetContext;
        }
    }
}
