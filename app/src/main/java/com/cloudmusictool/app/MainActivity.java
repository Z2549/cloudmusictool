package com.cloudmusictool.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 模块主入口 Activity。
 * Material 3 DayNight，四 Tab 结构（功能 / 首页定制 / 实验 / 关于）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "tab";

    private BottomNavigationView mNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("v" + versionName());
        toolbar.setOnClickListener(v -> showAboutDialog());

        mNav = findViewById(R.id.bottom_nav);
        int tab = savedInstanceState == null
                ? R.id.nav_features
                : savedInstanceState.getInt(STATE_TAB, R.id.nav_features);

        mNav.setSelectedItemId(tab);
        mNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            showTab(tab);
        }

        applyInsets();

        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
        ServiceBridge.poke(getApplicationContext());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mNav == null ? R.id.nav_features : mNav.getSelectedItemId());
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            mNav.setPadding(0, 0, 0, bars.bottom);
            return windowInsets;
        });
    }

    private void showTab(int id) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (id == R.id.nav_features) {
            if (current instanceof SettingsFragment) return;
            switchFragment(new SettingsFragment());
        } else if (id == R.id.nav_home) {
            if (current instanceof HomeCustomizeFragment) return;
            switchFragment(new HomeCustomizeFragment());
        } else if (id == R.id.nav_experiments) {
            if (current instanceof ExperimentFragment) return;
            switchFragment(new ExperimentFragment());
        } else if (id == R.id.nav_about) {
            if (current instanceof AboutFragment) return;
            switchFragment(new AboutFragment());
        }
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .commit();
    }

    private void refreshCurrent() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        } else if (f instanceof HomeCustomizeFragment) {
            ((HomeCustomizeFragment) f).refresh();
        } else if (f instanceof ExperimentFragment) {
            ((ExperimentFragment) f).refresh();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void showAboutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
