package com.cloudmusictool.app;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Material 3 风格的多选对话框偏好项。
 * 摆脱 PreferenceDialogFragmentCompat 繁琐的注入，直接使用 MaterialAlertDialogBuilder 渲染。
 */
public class MultiSelectPreference extends Preference {

    private CharSequence[] mEntries;
    private CharSequence[] mEntryValues;
    private Set<String> mValues = new HashSet<>();

    public MultiSelectPreference(Context context) {
        this(context, null);
    }

    public MultiSelectPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    public void setEntries(CharSequence[] entries) {
        mEntries = entries;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        mEntryValues = entryValues;
    }

    public void setValues(Set<String> values) {
        mValues = values != null ? new HashSet<>(values) : new HashSet<>();
        notifyChanged();
    }

    public Set<String> getValues() {
        return Collections.unmodifiableSet(mValues);
    }

    @Override
    protected void onClick() {
        if (!isEnabled() || mEntries == null || mEntryValues == null) {
            return;
        }

        boolean[] checkedItems = new boolean[mEntryValues.length];
        for (int i = 0; i < mEntryValues.length; i++) {
            checkedItems[i] = mValues.contains(mEntryValues[i].toString());
        }

        Set<String> pendingSelection = new HashSet<>(mValues);

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(getTitle())
                .setMultiChoiceItems(mEntries, checkedItems, (dialog, which, isChecked) -> {
                    String val = mEntryValues[which].toString();
                    if (isChecked) {
                        pendingSelection.add(val);
                    } else {
                        pendingSelection.remove(val);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (callChangeListener(pendingSelection)) {
                        setValues(pendingSelection);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
