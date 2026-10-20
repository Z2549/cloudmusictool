package com.cloudmusictool.app;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.json.JSONObject;

import java.util.List;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 功能设置页。
 * 顶部状态卡展示：连接中 / 未激活 / 已激活 / 未适配。
 * 包含广告、界面与启动、隐私与上报三个分类。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TARGET = "com.netease.cloudmusic";

    private ConfigStore mStore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                continue;
            }
            p.setTitle(f.titleRes);
            if (f.blocked()) {
                p.setSummary(f.summaryRes);
                p.setEnabled(false);
            } else {
                p.setSummary(f.summaryRes);
                p.setOnPreferenceChangeListener((pref, value) -> {
                    if (value instanceof Boolean) {
                        mStore.put(f.key, (Boolean) value);
                    }
                    return true;
                });
            }
        }

        Preference limits = findPreference("footer_limits");
        if (limits != null) {
            limits.setSummary(R.string.footer_limits);
        }
        Preference hint = findPreference("footer_hint");
        if (hint != null) {
            hint.setSummary(R.string.footer_hint);
        }
    }

    /** 重新读取框架状态与开关值。service 绑定后由 Activity 调一次。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        mStore = new ConfigStore(requireContext());
        boolean writable = mStore.remote();

        Preference status = findPreference("status");
        if (status != null) {
            XposedService service = ServiceBridge.get();
            if (service == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(R.string.status_detail_connecting);
            } else {
                int api = 0;
                boolean scoped = false;
                try {
                    api = service.getApiVersion();
                } catch (Throwable ignored) {
                }
                try {
                    List<String> scope = service.getScope();
                    scoped = scope != null && scope.contains(TARGET);
                } catch (Throwable ignored) {
                }

                String targetVersion = null;
                try {
                    PackageInfo pi = requireContext().getPackageManager().getPackageInfo(TARGET, 0);
                    targetVersion = pi.versionName;
                } catch (Throwable ignored) {
                }

                boolean versionSupported = targetVersion != null && VersionGate.SUPPORTED_VERSION.equals(targetVersion);
                boolean allowUnsupported = mStore.get(VersionGate.KEY_ALLOW_UNSUPPORTED, VersionGate.DEFAULT_ALLOW_UNSUPPORTED);
                boolean injected = isTargetRunning(service);

                if (!scoped) {
                    status.setTitle(R.string.status_inactive);
                    status.setSummary(R.string.status_detail_no_scope);
                } else if (targetVersion != null && !versionSupported && !allowUnsupported) {
                    status.setTitle(R.string.status_unsupported);
                    status.setSummary(getString(R.string.status_detail_unsupported, targetVersion));
                } else if (injected) {
                    status.setTitle(R.string.status_active);
                    String base = getString(R.string.status_detail_active, api);
                    String injection = injectionSummary();
                    status.setSummary(injection == null ? base : base + "\n" + injection);
                } else {
                    status.setTitle(R.string.status_connected);
                    status.setSummary(R.string.status_detail_not_running);
                }
            }
        }

        updateDiagnostics();

        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                if (f.blocked()) {
                    sw.setCheckedValue(false);
                    sw.setEnabled(false);
                } else {
                    sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                    sw.setEnabled(writable);
                }
            }
        }
    }

    private boolean isTargetRunning(XposedService service) {
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets == null) {
                return false;
            }
            for (HookedTarget t : targets) {
                String name = t.getProcessName();
                if (name != null && (name.equals(TARGET) || name.startsWith(TARGET + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 状态卡上的“本次注入 N/M 挂载点，K 项未命中”。 */
    private String injectionSummary() {
        JSONObject root = readStatus();
        if (root == null) {
            return null;
        }
        int enabled = root.optInt("enabledFeatures", 0);
        if (enabled == 0) {
            return null;
        }
        String summary = getString(R.string.status_detail_injected,
                root.optInt("expected", 0),
                root.optInt("noHitFeatures", 0));
        String health = healthSummary(root);
        return health == null ? summary : summary + "\n" + health;
    }

    /**
     * Profile 降级/不可用摘要。
     * 旧版 HookState 没有 degradedFeatures/unavailableFeatures 字段，
     * optInt 默认 0，此时返回 null，状态卡保持旧文案。
     */
    private String healthSummary(JSONObject root) {
        int degraded = root.optInt("degradedFeatures", 0);
        int unavailable = root.optInt("unavailableFeatures", 0);
        if (degraded == 0 && unavailable == 0) {
            return null;
        }
        return getString(R.string.status_detail_health, degraded, unavailable);
    }

    /** 诊断卡：每项 installed/expected/hit/最近失败原因。 */
    private void updateDiagnostics() {
        Preference diag = findPreference("status_diagnostics");
        if (diag == null) {
            return;
        }
        JSONObject root = readStatus();
        if (root == null) {
            diag.setSummary(R.string.status_diagnostics_empty);
            return;
        }
        int enabled = root.optInt("enabledFeatures", 0);
        StringBuilder sb = new StringBuilder();
        if (enabled == 0) {
            sb.append(getString(R.string.status_diagnostics_none));
        } else {
            sb.append(getString(R.string.status_detail_injected,
                    root.optInt("expected", 0),
                    root.optInt("noHitFeatures", 0)));
            String health = healthSummary(root);
            if (health != null) {
                sb.append('\n').append(health);
            }
        }
        JSONObject features = root.optJSONObject("features");
        if (features != null) {
            for (Feature f : Feature.values()) {
                JSONObject o = features.optJSONObject(f.name());
                if (o == null || !o.optBoolean("enabled", false)) {
                    continue;
                }
                sb.append('\n').append(f.id).append("  ")
                        .append(o.optInt("installed", 0)).append('/')
                        .append(o.optInt("expected", 0))
                        .append(o.optBoolean("hit", false) ? " · 已命中" : " · 未命中");
                if (o.optBoolean("degraded", false)) {
                    sb.append(" · ").append(getString(R.string.status_mark_degraded));
                }
                if (o.optBoolean("unavailable", false)) {
                    sb.append(" · ").append(getString(R.string.status_mark_unavailable));
                }
                String failure = o.optString("lastFailure", null);
                if (failure != null && !failure.isEmpty()) {
                    sb.append(" · ").append(failure);
                }
            }
        }
        diag.setSummary(sb.toString());
    }

    private JSONObject readStatus() {
        String json = StatusProvider.readState(requireContext());
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }
    }
}
