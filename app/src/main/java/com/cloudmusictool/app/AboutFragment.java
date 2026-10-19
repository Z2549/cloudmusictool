package com.cloudmusictool.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * 关于页：展示版本、作用域、API 版本、目标版本、免责声明、GitHub 地址。
 */
public class AboutFragment extends PreferenceFragmentCompat {

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
}
