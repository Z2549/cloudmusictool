package com.cloudmusictool.app;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

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
                p.setSummary(getString(f.summaryRes) + " (" + f.blockedReason + ")");
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
                    status.setSummary(getString(R.string.status_detail_active, api));
                } else {
                    status.setTitle(R.string.status_connected);
                    status.setSummary(R.string.status_detail_not_running);
                }
            }
        }

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
}
