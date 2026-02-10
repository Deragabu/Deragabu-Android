package com.limelight.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends Preference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private final String dialogMessage;
    private final String suffix;
    private final String dialogTitle;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    public SeekBarPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Read the title from XML
        int titleId = attrs != null ? attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "title", 0) : 0;
        if (titleId == 0) {
            dialogTitle = attrs != null ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, "title") : null;
        } else {
            dialogTitle = context.getString(titleId);
        }

        // Read the message from XML
        int dialogMessageId = attrs != null ? attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0) : 0;
        if (dialogMessageId == 0) {
            dialogMessage = attrs != null ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage") : null;
        } else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs != null ? attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0) : 0;
        if (suffixId == 0) {
            suffix = attrs != null ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text") : null;
        } else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs != null ? attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context)) : PreferenceConfiguration.getDefaultBitrate(context);
        maxValue = attrs != null ? attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100) : 100;
        minValue = attrs != null ? attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1) : 1;
        stepSize = attrs != null ? attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1) : 1;
        divisor = attrs != null ? attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1) : 1;
        keyStepSize = attrs != null ? attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0) : 0;
    }

    public int getValue() {
        return currentValue;
    }

    public void setValue(int value) {
        currentValue = value;
        persistInt(value);
        notifyChanged();
    }

    @Override
    protected Object onGetDefaultValue(@NonNull TypedArray a, int index) {
        return a.getInteger(index, defaultValue);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        int def = defaultValue != null ? (Integer) defaultValue : this.defaultValue;
        currentValue = getPersistedInt(def);
    }

    public void setProgress(int progress) {
        setValue(progress);
    }

    public int getProgress() {
        return getValue();
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        if (dialogMessage != null) {
            TextView splashText = new TextView(context);
            splashText.setPadding(0, 10, 0, 20);
            splashText.setText(dialogMessage);
            layout.addView(splashText);
        }

        final TextView valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueText, params);

        final SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue);

        // Update initial value text
        updateValueText(valueText, currentValue);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (value < minValue) {
                    seekBar.setProgress(minValue);
                    return;
                }

                int roundedValue = ((value + (stepSize - 1)) / stepSize) * stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue);
                    return;
                }

                updateValueText(valueText, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(context)
                .setTitle(dialogTitle != null ? dialogTitle : getTitle())
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int value = seekBar.getProgress();
                    if (callChangeListener(value)) {
                        setValue(value);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateValueText(TextView valueText, int value) {
        String t;
        if (divisor != 1) {
            float floatValue = value / (float) divisor;
            t = String.format(Locale.getDefault(), "%.1f", floatValue);
        } else {
            t = String.valueOf(value);
        }
        valueText.setText(suffix == null ? t : t.concat(suffix.length() > 1 ? " " + suffix : suffix));
    }
}
