package com.cloudmusictool.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

/**
 * 关于页：展示版本、作用域、API 版本、目标版本、免责声明、GitHub 地址。
 */
public class AboutFragment extends PreferenceFragmentCompat {

    private static final int SELFTEST_TAP_COUNT = 7;

    private int mVersionTaps;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs_about, rootKey);

        Preference verPref = findPreference("about_version");
        if (verPref != null) {
            String versionName = "?";
            long versionCode = 0;
            try {
                PackageInfo pi = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0);
                versionName = pi.versionName;
                versionCode = pi.getLongVersionCode();
            } catch (Throwable ignored) {
            }
            verPref.setSummary(getString(R.string.about_version_format, versionName, versionCode));
            verPref.setOnPreferenceClickListener(preference -> {
                if (++mVersionTaps >= SELFTEST_TAP_COUNT) {
                    revealDexKitSelfTest();
                }
                return true;
            });
        }

        Preference sourcePref = findPreference("about_source");
        if (sourcePref != null) {
            sourcePref.setOnPreferenceClickListener(preference -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_source_url)));
                    startActivity(intent);
                } catch (Throwable ignored) {
                }
                return true;
            });
        }
    }

    /** 连点 7 次版本号后显示隐藏自检开关。 */
    private void revealDexKitSelfTest() {
        if (findPreference(Config.KEY_DEXKIT_SELFTEST) != null) {
            return;
        }
        ConfigStore store = new ConfigStore(requireContext());
        MaterialSwitchPreference sw = new MaterialSwitchPreference(requireContext());
        sw.setKey(Config.KEY_DEXKIT_SELFTEST);
        sw.setTitle(R.string.dexkit_selftest_title);
        sw.setSummary(R.string.dexkit_selftest_summary);
        sw.setIcon(R.drawable.ic_test);
        sw.setCheckedValue(store.get(Config.KEY_DEXKIT_SELFTEST, Config.DEFAULT_DEXKIT_SELFTEST));
        sw.setOnPreferenceChangeListener((preference, value) -> {
            if (value instanceof Boolean) {
                store.put(Config.KEY_DEXKIT_SELFTEST, (Boolean) value);
            }
            return true;
        });
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            screen.addPreference(sw);
        }
    }
}
