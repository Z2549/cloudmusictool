package com.cloudmusictool.app.hooks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 首页 / MMKV 缓存过滤的纯函数实现（无 Android 依赖，便于 JVM 单测）。
 */
final class JsonFilter {

    private JsonFilter() {
    }

    /**
     * blockCodeOrderList 是形如 ["PAGE_RECOMMEND_X", ...] 的 JSON 数组字符串。
     * 返回过滤后的字符串；removedOut[0] 写入删除数量。
     */
    static String filterCodeListString(String json, Set<String> hideSet, int[] removedOut) {
        if (hideSet == null || hideSet.isEmpty() || json == null || json.isEmpty()) {
            return json;
        }
        try {
            JSONArray arr = new JSONArray(json);
            JSONArray out = new JSONArray();
            int removed = 0;
            for (int i = 0; i < arr.length(); i++) {
                String code = arr.optString(i, "");
                if (code != null && hideSet.contains(code)) {
                    removed++;
                    continue;
                }
                out.put(code);
            }
            if (removedOut != null) {
                removedOut[0] = removed;
            }
            return removed > 0 ? out.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    /**
     * 递归过滤 JSON 树：删除 blockCode 命中 hideSet 的对象。
     * 原地修改 JSONObject/JSONArray；removed[0] 累计删除数。
     */
    static Object filterValue(Object node, Set<String> hideSet, int[] removed, int depth) {
        if (depth > 6) {
            return node;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            for (String key : keys) {
                Object val = obj.opt(key);
                if (val instanceof JSONObject || val instanceof JSONArray) {
                    try {
                        obj.put(key, filterValue(val, hideSet, removed, depth + 1));
                    } catch (Throwable ignored) {
                    }
                } else if (val instanceof String) {
                    String s = (String) val;
                    String t = s.trim();
                    if (t.startsWith("{") || t.startsWith("[")) {
                        int before = removed[0];
                        Object nested = filterNestedString(s, hideSet, removed, depth + 1);
                        if (removed[0] > before) {
                            try {
                                obj.put(key, nested);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            return obj;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    String code = ((JSONObject) item).optString("blockCode", null);
                    if (code != null && hideSet.contains(code)) {
                        removed[0]++;
                        continue;
                    }
                    out.put(filterValue(item, hideSet, removed, depth + 1));
                } else if (item instanceof JSONArray) {
                    out.put(filterValue(item, hideSet, removed, depth + 1));
                } else if (item instanceof String) {
                    String s = (String) item;
                    String t = s.trim();
                    int before = removed[0];
                    if (t.startsWith("{") || t.startsWith("[")) {
                        Object nested = filterNestedString(s, hideSet, removed, depth + 1);
                        out.put(removed[0] > before ? nested : item);
                    } else {
                        out.put(item);
                    }
                } else {
                    out.put(item);
                }
            }
            return out;
        }
        return node;
    }

    private static Object filterNestedString(String s, Set<String> hideSet, int[] removed, int depth) {
        try {
            String t = s.trim();
            Object root = t.startsWith("{") ? new JSONObject(t) : new JSONArray(t);
            Object filtered = filterValue(root, hideSet, removed, depth);
            return filtered.toString();
        } catch (Throwable ignored) {
            return s;
        }
    }
}
