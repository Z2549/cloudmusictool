package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * UI-02 顶部频道管理：解锁拖拽与删除限制，并按保留列表过滤频道。
 */
public final class TopTabHooks {

    private TopTabHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.TOP_CHANNELS;

        // 1. HomeTopTabInfo 解锁删除与拖拽
        try {
            Class<?> tabInfoCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.module.hometoptab.model.HomeTopTabInfo",
                    "HomeTopTabInfo", "canDelete");
            if (tabInfoCls != null) {
                Method getCanDelete = HookKit.findMethodQuiet(tabInfoCls, "getCanDelete");
                if (getCanDelete != null) {
                    HookKit.install(module, f, "HomeTopTabInfo#getCanDelete", getCanDelete,
                            HookKit.constBool(module, f, "HomeTopTabInfo.getCanDelete -> true", true));
                }
                Method getCanDrag = HookKit.findMethodQuiet(tabInfoCls, "getCanDrag");
                if (getCanDrag != null) {
                    HookKit.install(module, f, "HomeTopTabInfo#getCanDrag", getCanDrag,
                            HookKit.constBool(module, f, "HomeTopTabInfo.getCanDrag -> true", true));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " HomeTopTabInfo hook failed", t);
        }

        // 2. TabExtra.getNowChannelItems
        String[] tabExtraClasses = {
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.TabExtra",
                "com.netease.cloudmusic.discovery.view.newframework.model.TabExtra"
        };
        for (String clsName : tabExtraClasses) {
            try {
                Class<?> tabExtraCls = HookKit.findClass(module, f, cl, clsName, "TabExtra", "nowChannelItems");
                if (tabExtraCls != null) {
                    Method getNowChannelItems = HookKit.findMethodQuiet(tabExtraCls, "getNowChannelItems");
                    if (getNowChannelItems == null) {
                        getNowChannelItems = HookKit.findByNameQuiet(tabExtraCls, "getNowChannelItems");
                    }
                    if (getNowChannelItems != null) {
                        HookKit.install(module, f, tabExtraCls.getSimpleName() + "#getNowChannelItems", getNowChannelItems, chain -> {
                            Object res = chain.proceed();
                            try {
                                return filterChannelList(module, f, config, res, tabExtraCls.getSimpleName() + "#getNowChannelItems");
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error getNowChannelItems", t);
                            }
                            return res;
                        });
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + clsName + " hook failed", t);
            }
        }

        installTabDataList(module, cl, config);
        installTabConfigText(module, cl, config);



        // 3. UserSettingDTO.getCurrentRes
        String[] dtoNames = {
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.UserSettingDTO",
                "com.netease.cloudmusic.discovery.view.module.hometoptab.v2.UserSettingDTO"
        };
        for (String dtoName : dtoNames) {
            try {
                Class<?> dtoCls = HookKit.findClass(module, f, cl, dtoName, "UserSettingDTO");
                if (dtoCls != null) {
                    Method getCurrentRes = HookKit.findMethodQuiet(dtoCls, "getCurrentRes");
                    if (getCurrentRes != null) {
                        HookKit.install(module, f, dtoCls.getSimpleName() + "#getCurrentRes", getCurrentRes, chain -> {
                            Object res = chain.proceed();
                            try {
                                return filterChannelList(module, f, config, res, dtoCls.getSimpleName() + "#getCurrentRes");
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error getCurrentRes", t);
                            }
                            return res;
                        });
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + dtoName + " hook failed", t);
            }
        }
    }

    // 6. BaseArkV3Fragment.getTabDataList（9.6.08 顶部 tab 真实数据列表，setText 栈考古确认）

    public static void installTabDataList(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.TOP_CHANNELS;
        try {
            Class<?> fragCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                    "BaseArkV3Fragment", "tabDataList");
            if (fragCls == null) {
                return;
            }
            Method getTabDataList = HookKit.findMethodQuiet(fragCls, "getTabDataList");
            if (getTabDataList == null) {
                return;
            }
            HookKit.install(module, f, "BaseArkV3Fragment#getTabDataList", getTabDataList, chain -> {
                Object res = chain.proceed();
                try {
                    if (!(res instanceof List)) {
                        return res;
                    }
                    List<?> list = (List<?>) res;
                    for (Object item : list) {
                        String t = firstStringGetter(item);
                        if (t != null) {
                            SEEN_TITLES.add(t);
                        }
                    }
                    Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
                    if (keepSet == null || keepSet.isEmpty()) {
                        return res;
                    }
                    List<Object> out = new ArrayList<>();
                    for (Object item : list) {
                        if (shouldKeepItem(item, keepSet)) {
                            out.add(item);
                        }
                    }
                    if (out.isEmpty()) {
                        return res;
                    }
                    if (out.size() < list.size()) {
                        HookKit.hit(module, f, "getTabDataList filtered from " + list.size()
                                + " to " + out.size());
                        try {
                            list.clear();
                            ((java.util.List) list).addAll((java.util.List) out);
                            return list;
                        } catch (Throwable ignored) {
                            return out;
                        }
                    }
                    return res;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error getTabDataList", t);
                }
                return res;
            });
            // 渲染入口：hc(List, ViewPager2, TabLayout) / Tc(List) / Lc(List) 过滤入参
            String[] listMethods = {"hc", "Tc", "Lc"};
            for (String mName : listMethods) {
                for (Method m : fragCls.getDeclaredMethods()) {
                    if (!m.getName().equals(mName)) {
                        continue;
                    }
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 0 || pts[0] != List.class) {
                        continue;
                    }
                    m.setAccessible(true);
                    final String label = mName + "(" + pts.length + ")";
                    HookKit.install(module, f, "BaseArkV3Fragment#" + label, m, chain -> {
                        try {
                            java.util.List<Object> args = chain.getArgs();
                            if (args != null && !args.isEmpty() && args.get(0) instanceof List) {
                                List<?> list = (List<?>) args.get(0);
                                Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
                                if (keepSet != null && !keepSet.isEmpty()) {
                                    List<Object> out = new ArrayList<>();
                                    for (Object item : list) {
                                        if (shouldKeepItem(item, keepSet)) {
                                            out.add(item);
                                        }
                                    }
                                    if (!out.isEmpty() && out.size() < list.size()) {
                                        HookKit.hit(module, f, label + " filtered from "
                                                + list.size() + " to " + out.size());
                                        try {
                                            list.clear();
                                            ((java.util.List) list).addAll((java.util.List) out);
                                        } catch (Throwable ignored) {
                                            Object[] newArgs = args.toArray();
                                            newArgs[0] = out;
                                            return chain.proceed(newArgs);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error " + label, t);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " BaseArkV3Fragment hook failed", t);
        }
    }

    private static final java.util.Set<String> SEEN_TITLES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 取 tab 数据对象的标题（getTitle/getName/getTabName）。 */
    /** 把 k 关联的 TabView 整体隐藏并禁用，防止空槽位仍可点击切换。 */
    private static void hideTabView(Object k) {
        if (k == null) {
            return;
        }
        try {
            for (java.lang.reflect.Field fld : k.getClass().getDeclaredFields()) {
                if (!android.view.View.class.isAssignableFrom(fld.getType())) {
                    continue;
                }
                fld.setAccessible(true);
                Object v = fld.get(k);
                if (v instanceof android.view.View) {
                    android.view.View view = (android.view.View) v;
                    view.setVisibility(android.view.View.GONE);
                    view.setClickable(false);
                    view.setEnabled(false);
                    view.setAlpha(0f);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拦截向被隐藏频道的切换请求（onTabSelected/selectSubTabById/setTabByCode）。
     * 数据与文本层已过滤后，这里兜底防止空槽位/旧索引仍能切进隐藏频道。
     */
    public static void installSelectGuard(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.TOP_CHANNELS;
        try {
            Class<?> fragCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                    "BaseArkV3Fragment", "selectSubTab");
            if (fragCls == null) {
                return;
            }
            for (Method m : fragCls.getDeclaredMethods()) {
                String n = m.getName();
                boolean target = n.equals("selectSubTabById") || n.equals("setTabByCode")
                        || n.equals("selectSubTabInner");
                if (!target) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 0 || pts[0] != String.class) {
                    continue;
                }
                m.setAccessible(true);
                final String label = "BaseArkV3Fragment#" + n;
                HookKit.install(module, f, label, m, chain -> {
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        String code = (args != null && !args.isEmpty() && args.get(0) instanceof String)
                                ? (String) args.get(0) : null;
                        Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
                        if (code != null && keepSet != null && !keepSet.isEmpty()) {
                            // code 可能是频道 code（rcmd 等）也可能是标题，均不在保留清单则拒绝
                            if (!keepSet.contains(code) && !SEEN_TITLES.contains(code)) {
                                HookKit.hit(module, f, label + " blocked '" + code + "'");
                                return null;
                            }
                            if (SEEN_TITLES.contains(code) && !keepSet.contains(code)) {
                                HookKit.hit(module, f, label + " blocked title '" + code + "'");
                                return null;
                            }
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error " + label, t);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " select guard hook failed", t);
        }
    }

    private static String firstStringGetter(Object item) {
        if (item == null) {
            return null;
        }
        String[] names = {"getTitle", "getName", "getTabName"};
        for (String n : names) {
            try {
                Method m = item.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(item);
                if (v != null) {
                    return v.toString().trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * MusicTabLayout$k.B/K(String)：tab 文本注入点（fluent 链式 setter）。
     * 命中隐藏清单时把文本置空，配合 TabView 隐藏完成频道过滤。
     */
    public static void installTabConfigText(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.TOP_CHANNELS;
        try {
            Class<?> kCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.theme.ui.tab.MusicTabLayout$k",
                    "MusicTabLayout", "newTab");
            if (kCls == null) {
                return;
            }
            int installed = 0;
            for (Method m : kCls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                boolean textSetter = (pts.length == 1 && pts[0] == CharSequence.class)
                        || (pts.length == 2 && pts[0] == CharSequence.class);
                if (!textSetter || m.getReturnType() != kCls) {
                    continue;
                }
                m.setAccessible(true);
                final String label = "MusicTabLayout$k#" + m.getName();
                HookKit.install(module, f, label, m, chain -> {
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        String t = (args != null && !args.isEmpty() && args.get(0) instanceof CharSequence)
                                ? args.get(0).toString().trim() : null;
                        Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
                        if (t != null && keepSet != null && !keepSet.isEmpty()
                                && !keepSet.contains(t)) {
                            HookKit.hit(module, f, label + " cleared '" + t + "'");
                            Object[] newArgs = args.toArray();
                            newArgs[0] = "";
                            hideTabView(chain.getThisObject());
                            return chain.proceed(newArgs);
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error " + label, t);
                    }
                    return chain.proceed();
                });
                installed++;
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MusicTabLayout$k text hook failed", t);
        }
    }



    private static Object filterChannelList(XposedModule module, Feature f, Config config, Object res, String source) {
        if (!(res instanceof List)) {
            return res;
        }
        Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
        if (keepSet == null || keepSet.isEmpty()) {
            return res;
        }
        List<?> list = (List<?>) res;
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            if (shouldKeepItem(item, keepSet)) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            return res;
        }
        HookKit.hit(module, f, source + " filtered from " + list.size() + " to " + out.size() + " items");
        return out;
    }

    private static boolean shouldKeepItem(Object item, Set<String> keepSet) {
        if (item == null) {
            return false;
        }
        String[] methodNames = {"getTitle", "getChannelCode", "getCode", "getName"};
        for (String name : methodNames) {
            try {
                Method m = item.getClass().getMethod(name);
                m.setAccessible(true);
                Object val = m.invoke(item);
                if (val != null && keepSet.contains(val.toString().trim())) {
                    return true;
                }
            } catch (Throwable t1) {
                try {
                    Method m = item.getClass().getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object val = m.invoke(item);
                    if (val != null && keepSet.contains(val.toString().trim())) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }
}
