package io.github.usama6878786.hypervolumefine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Material 3 settings screen.
 *
 * Third-party apps cannot write custom Settings.System keys (the system throws
 * "You cannot keep your settings in the secure settings").
 * Changes are kept in memory and broadcast to system_server for persistence.
 */
public class SettingsActivity extends Activity {

    private static final String ACTION_CONFIG = "io.github.usama6878786.hypervolumefine.ACTION_CONFIG";

    private static final String KEY_ENABLED = "volume_fine_enabled";
    private static final String KEY_STEPS = "volume_fine_steps";
    private static final String KEY_THRESHOLD = "volume_fine_threshold";
    private static final String KEY_FINE = "volume_fine_fine";
    private static final String KEY_COARSE = "volume_fine_coarse";
    private static final String KEY_SNAP = "volume_fine_snap";
    private static final String KEY_STREAMS = "volume_fine_streams";
    private static final String KEY_SHOW_NUMBER = "volume_fine_show_number";

    private static final int DEF_ENABLED = 1;
    private static final int DEF_STEPS = 100;
    private static final int DEF_THRESHOLD = 30;
    private static final int DEF_FINE = 1;
    private static final int DEF_COARSE = 4;
    private static final int DEF_SNAP = 2;
    private static final int DEF_SHOW_NUMBER = 1;

    private float mDensity;
    private int mColorOnSurface;
    private int mColorOnSurfaceVariant;
    private int mColorPrimary;
    private int mColorOnPrimaryContainer;

    private final Map<String, Integer> mValues = new HashMap<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Toast mToast;
    private String mLastToastText;
    private long mLastToastAt;
    /** Show one reboot reminder after slider changes settle. */
    private boolean mRebootToastPending;
    private final Runnable mRebootToast = () -> {
        if (!mRebootToastPending) {
            return;
        }
        mRebootToastPending = false;
        toast(getString(R.string.saved_reboot));
    };

    private M3Switch mSwitch;
    private TextView mStatusChip;
    private TextView mVolumeValue;
    private TextView mStepsHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDensity = getResources().getDisplayMetrics().density;
        mColorOnSurface = getColor(R.color.vf_on_surface);
        mColorOnSurfaceVariant = getColor(R.color.vf_on_surface_variant);
        mColorPrimary = getColor(R.color.vf_primary);
        mColorOnPrimaryContainer = getColor(R.color.vf_on_primary_container);

        seedValues();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.vf_surface));

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(pad, dp(12) + top, pad, dp(32) + bottom);
            return insets;
        });

        addHeader(root);
        addSwitchCard(root);

        addSectionLabel(root, getString(R.string.section_volume));
        addVolumeCard(root);
        addToggleCard(root, getString(R.string.show_number_title),
                getString(R.string.show_number_summary), KEY_SHOW_NUMBER, DEF_SHOW_NUMBER);

        addSectionLabel(root, getString(R.string.section_parameters));
        addSliderCard(root, getString(R.string.steps_title), 20, 150, 10, KEY_STEPS,
                getString(R.string.steps_summary));
        addSliderCard(root, getString(R.string.threshold_title), 0, 150, 10, KEY_THRESHOLD,
                getString(R.string.threshold_summary));
        addSliderCard(root, getString(R.string.fine_title), 1, 6, 1, KEY_FINE,
                getString(R.string.fine_summary));
        addSliderCard(root, getString(R.string.coarse_title), 1, 8, 1, KEY_COARSE,
                getString(R.string.coarse_summary));
        addSliderCard(root, getString(R.string.snap_title), 1, 6, 1, KEY_SNAP,
                getString(R.string.snap_summary));

        addInfoCard(root);
        addResetButton(root);
        addFooter(root);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------------ Configuration I/O

    private void seedValues() {
        mValues.put(KEY_ENABLED, getInt(KEY_ENABLED, DEF_ENABLED));
        mValues.put(KEY_STEPS, getInt(KEY_STEPS, DEF_STEPS));
        mValues.put(KEY_THRESHOLD, getInt(KEY_THRESHOLD, DEF_THRESHOLD));
        mValues.put(KEY_FINE, getInt(KEY_FINE, DEF_FINE));
        mValues.put(KEY_COARSE, getInt(KEY_COARSE, DEF_COARSE));
        mValues.put(KEY_SNAP, getInt(KEY_SNAP, DEF_SNAP));
        mValues.put(KEY_SHOW_NUMBER, getInt(KEY_SHOW_NUMBER, DEF_SHOW_NUMBER));
    }

    private int value(String key) {
        Integer v = mValues.get(key);
        return v == null ? 0 : v;
    }

    /** Broadcast the in-memory configuration; system_server writes Settings.System. */
    private void pushConfig() {
        Intent intent = new Intent(ACTION_CONFIG);
        for (Map.Entry<String, Integer> e : mValues.entrySet()) {
            intent.putExtra(e.getKey(), e.getValue());
        }
        try {
            sendBroadcast(intent);
        } catch (Throwable t) {
            toast(getString(R.string.send_failed, t.getMessage()));
        }
    }

    /** Suppress identical messages for two seconds and cancel the previous toast. */
    private void toast(String text) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (text.equals(mLastToastText) && now - mLastToastAt < 2000) {
                return;
            }
            mLastToastText = text;
            mLastToastAt = now;
            if (mToast != null) {
                mToast.cancel();
            }
            mToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
            mToast.show();
        } catch (Throwable ignored) {
        }
    }

    /** Debounce reboot reminders by 700ms after the last change. */
    private void requestRebootToast() {
        mRebootToastPending = true;
        mHandler.removeCallbacks(mRebootToast);
        mHandler.postDelayed(mRebootToast, 700);
    }

    private void setValue(String key, int v, boolean needsReboot) {
        mValues.put(key, v);
        pushConfig();
        if (needsReboot) {
            requestRebootToast();
        }
    }

    // ------------------------------------------------------------------ Header

    private void addHeader(LinearLayout root) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackgroundResource(R.drawable.vf_icon_bg);
        iconBox.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.ic_launcher_background)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher_foreground);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(56), dp(56));
        ilp.gravity = Gravity.CENTER;
        iconBox.addView(icon, ilp);
        head.addView(iconBox, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = dp(16);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(mColorOnSurface);
        title.setLetterSpacing(-0.02f);
        titles.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.app_tagline));
        sub.setTextSize(13);
        sub.setTextColor(mColorOnSurfaceVariant);
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub);

        head.addView(titles, titlesLp);
        root.addView(head);

        mStatusChip = new TextView(this);
        mStatusChip.setTextSize(12);
        mStatusChip.setTextColor(mColorOnPrimaryContainer);
        mStatusChip.setBackgroundResource(R.drawable.vf_chip);
        mStatusChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        root.addView(mStatusChip, lp);
    }

    // ------------------------------------------------------------------ Cards

    private void addSwitchCard(LinearLayout root) {
        LinearLayout card = newCard(root);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.enable_title));
        title.setTextSize(16);
        title.setTextColor(mColorOnSurface);
        texts.addView(title);
        TextView desc = new TextView(this);
        desc.setText(getString(R.string.enable_summary));
        desc.setTextSize(12);
        desc.setTextColor(mColorOnSurfaceVariant);
        desc.setPadding(0, dp(4), 0, 0);
        texts.addView(desc);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mSwitch = new M3Switch(this);
        mSwitch.setContentDescription(getString(R.string.enable_title));
        mSwitch.setChecked(value(KEY_ENABLED) != 0, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16);
        row.addView(mSwitch, slp);

        View.OnClickListener toggle = v -> {
            mSwitch.toggle();
            boolean on = mSwitch.isChecked();
            setValue(KEY_ENABLED, on ? 1 : 0, false);
            toast(on ? getString(R.string.enabled_reboot) : getString(R.string.disabled_reboot));
        };
        row.setOnClickListener(toggle);
        mSwitch.setOnClickListener(toggle);

        card.addView(row);
    }

    /** A card with a toggle switch. */
    private void addToggleCard(LinearLayout root, String title, String desc, String key, int def) {
        LinearLayout card = newCard(root);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(mColorOnSurface);
        texts.addView(tv);
        TextView sub = new TextView(this);
        sub.setText(desc);
        sub.setTextSize(12);
        sub.setTextColor(mColorOnSurfaceVariant);
        sub.setPadding(0, dp(4), 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        M3Switch sw = new M3Switch(this);
        sw.setContentDescription(title);
        sw.setChecked(value(key) != 0, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16);
        row.addView(sw, slp);

        View.OnClickListener toggle = v -> {
            sw.toggle();
            setValue(key, sw.isChecked() ? 1 : 0, false);
        };
        row.setOnClickListener(toggle);
        sw.setOnClickListener(toggle);

        card.addView(row);
    }

    /** Use the volume-key path, including configured fine steps at low volume. */
    private void addVolumeCard(LinearLayout root) {
        LinearLayout card = newCard(root);

        mVolumeValue = new TextView(this);
        mVolumeValue.setTextSize(15);
        mVolumeValue.setTextColor(mColorOnSurface);
        card.addView(mVolumeValue);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);

        TextView minus = roundButton("−");
        minus.setContentDescription(getString(R.string.decrease_volume));
        minus.setOnClickListener(v -> adjust(-1));
        btns.addView(minus, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View spacer = new View(this);
        btns.addView(spacer, new LinearLayout.LayoutParams(dp(12), dp(1)));

        TextView plus = roundButton("+");
        plus.setContentDescription(getString(R.string.increase_volume));
        plus.setOnClickListener(v -> adjust(1));
        btns.addView(plus, new LinearLayout.LayoutParams(0, dp(44), 1f));

        card.addView(btns, blp);
    }

    private TextView roundButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(20);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(getColor(R.color.vf_on_secondary_container));
        btn.setBackgroundResource(R.drawable.vf_button_tonal);
        btn.setClickable(true);
        return btn;
    }

    private void adjust(int direction) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) {
            return;
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
        mVolumeValue.postDelayed(this::refreshStatus, 150);
    }

    private void addSliderCard(LinearLayout root, String title, int min, int max, int grid, String key,
                               String helper) {
        LinearLayout card = newCard(root);

        LinearLayout header = new LinearLayout(this);
        boolean stackControls = getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale > 1.15f;
        header.setOrientation(stackControls ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTextColor(mColorOnSurface);
        header.addView(label, stackControls
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView chip = new TextView(this);
        chip.setTextSize(12);
        chip.setTextColor(mColorOnPrimaryContainer);
        chip.setBackgroundResource(R.drawable.vf_chip);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(52));

        TextView minus = stepButton("−");
        TextView plus = stepButton("+");
        minus.setContentDescription(getString(R.string.decrease_setting, title));
        plus.setContentDescription(getString(R.string.increase_setting, title));
        LinearLayout stepper = new LinearLayout(this);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.addView(minus);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(6);
        chipLp.rightMargin = dp(6);
        stepper.addView(chip, chipLp);
        stepper.addView(plus);
        LinearLayout.LayoutParams stepperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (stackControls) {
            stepperLp.topMargin = dp(10);
        }
        header.addView(stepper, stepperLp);

        card.addView(header);

        // Divide the slider into grid-sized increments.
        SeekBar bar = new SeekBar(this);
        bar.setContentDescription(title);
        bar.setMax((max - min) / grid);
        bar.setProgress(clamp(toProgress(value(key), min, max, grid), 0, (max - min) / grid));
        bar.setProgressDrawable(getDrawable(R.drawable.vf_slider_track));
        bar.setThumb(getDrawable(R.drawable.vf_slider_thumb));
        bar.setThumbOffset(0);
        bar.setSplitTrack(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        blp.topMargin = dp(12);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            int v = clamp(value(key), min, max);
            chip.setText(formatValue(key, v));
            int p = clamp(toProgress(v, min, max, grid), 0, (max - min) / grid);
            if (bar.getProgress() != p) {
                bar.setProgress(p);
            }
        };

        bar.setOnSeekBarChangeListener(new SeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int v = min + progress * grid;
                boolean needsReboot = KEY_STEPS.equals(key) && v != currentAppliedSteps();
                setValue(key, v, needsReboot);
                render[0].run();
                if (KEY_STEPS.equals(key)) {
                    updateStepsHint();
                }
            }
        });

        minus.setOnClickListener(v -> {
            setValueFor(key, clamp(value(key) - 1, min, max));
            render[0].run();
            if (KEY_STEPS.equals(key)) {
                updateStepsHint();
            }
        });
        plus.setOnClickListener(v -> {
            setValueFor(key, clamp(value(key) + 1, min, max));
            render[0].run();
            if (KEY_STEPS.equals(key)) {
                updateStepsHint();
            }
        });

        card.addView(bar, blp);
        render[0].run();

        TextView hint = new TextView(this);
        hint.setText(helper);
        hint.setTextSize(12);
        hint.setTextColor(mColorOnSurfaceVariant);
        hint.setLineSpacing(dp(3), 1f);
        card.addView(hint);

        if (KEY_STEPS.equals(key)) {
            mStepsHint = hint;
        }
    }

    private void setValueFor(String key, int v) {
        boolean needsReboot = KEY_STEPS.equals(key) && v != currentAppliedSteps();
        setValue(key, v, needsReboot);
    }

    private static int toProgress(int value, int min, int max, int grid) {
        int v = clamp(value, min, max);
        return Math.round((v - min) / (float) grid);
    }

    private TextView stepButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(getColor(R.color.vf_on_secondary_container));
        btn.setBackgroundResource(R.drawable.vf_step_button);
        btn.setClickable(true);
        btn.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return btn;
    }

    private String formatValue(String key, int v) {
        switch (key) {
            case KEY_THRESHOLD:
                return getResources().getQuantityString(R.plurals.level_count, v, v);
            case KEY_FINE:
            case KEY_COARSE:
                return getResources().getQuantityString(R.plurals.levels_per_press, v, v);
            case KEY_SNAP:
                return v <= 1 ? getString(R.string.off) : getResources().getQuantityString(R.plurals.level_count, v, v);
            default:
                return String.valueOf(v);
        }
    }

    private void addSectionLabel(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(mColorOnSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(2);
        root.addView(tv, lp);
    }

    private void addInfoCard(LinearLayout root) {
        LinearLayout card = newCard(root);

        TextView title = new TextView(this);
        title.setText(getString(R.string.info_title));
        title.setTextSize(15);
        title.setTextColor(mColorOnSurface);
        card.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(13);
        body.setTextColor(mColorOnSurfaceVariant);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(10), 0, 0);
        body.setText(getString(R.string.info_body, getString(KEY_STREAMS, "3")));
        card.addView(body);

        TextView adb = new TextView(this);
        adb.setTextSize(11);
        adb.setTextColor(mColorOnSurfaceVariant);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setLineSpacing(dp(4), 1f);
        adb.setPadding(dp(12), dp(10), dp(12), dp(10));
        adb.setBackgroundResource(R.drawable.vf_chip);
        adb.setText(R.string.adb_examples);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        card.addView(adb, alp);
    }

    private void addResetButton(LinearLayout root) {
        TextView btn = new TextView(this);
        btn.setText(getString(R.string.reset));
        btn.setTextSize(14);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(getColor(R.color.vf_on_secondary_container));
        btn.setBackgroundResource(R.drawable.vf_button_tonal);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            mValues.put(KEY_ENABLED, DEF_ENABLED);
            mValues.put(KEY_STEPS, DEF_STEPS);
            mValues.put(KEY_THRESHOLD, DEF_THRESHOLD);
            mValues.put(KEY_FINE, DEF_FINE);
            mValues.put(KEY_COARSE, DEF_COARSE);
            mValues.put(KEY_SNAP, DEF_SNAP);
            mValues.put(KEY_SHOW_NUMBER, DEF_SHOW_NUMBER);
            pushConfig();
            toast(getString(R.string.reset_saved));
            recreate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.topMargin = dp(4);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        btn.setPadding(dp(24), 0, dp(24), 0);
        root.addView(btn, lp);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setTextSize(11);
        footer.setTextColor(mColorOnSurfaceVariant);
        footer.setGravity(Gravity.CENTER);
        footer.setText(getString(R.string.footer));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        root.addView(footer, lp);
    }

    private LinearLayout newCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.vf_card);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(card, lp);
        return card;
    }

    // ------------------------------------------------------------------ Status

    private int currentAppliedSteps() {
        AudioManager am = getSystemService(AudioManager.class);
        return am == null ? 0 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private void refreshStatus() {
        AudioManager am = getSystemService(AudioManager.class);
        int cur = am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am == null ? 0 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mStatusChip.setText(getString(R.string.status_level, cur, max));
        if (mVolumeValue != null) {
            mVolumeValue.setText(getString(R.string.media_level, cur, max));
        }
        if (mSwitch != null) {
            mSwitch.setChecked(value(KEY_ENABLED) != 0, false);
        }
        updateStepsHint();
    }

    private void updateStepsHint() {
        if (mStepsHint == null) {
            return;
        }
        int running = currentAppliedSteps();
        int wanted = value(KEY_STEPS);
        if (running > 0 && wanted != running) {
            mStepsHint.setText(getString(R.string.steps_pending, running, wanted));
            mStepsHint.setTextColor(mColorPrimary);
        } else {
            mStepsHint.setText(getString(R.string.steps_summary));
            mStepsHint.setTextColor(mColorOnSurfaceVariant);
        }
    }

    // ------------------------------------------------------------------ Utilities

    private int dp(int v) {
        return Math.round(mDensity * v);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private int getInt(String key, int def) {
        try {
            return Settings.System.getInt(getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private String getString(String key, String def) {
        try {
            String v = Settings.System.getString(getContentResolver(), key);
            return v == null || v.trim().isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private abstract static class SeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
