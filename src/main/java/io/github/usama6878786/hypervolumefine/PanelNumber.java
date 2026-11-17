package io.github.usama6878786.hypervolumefine;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Render a volume number inside the system volume-panel window (SystemUI process).
 *
 * Hook {@code WindowManagerImpl / WindowManagerGlobal.addView} to identify the volume window,
 * then add a foreground TextView to its content container. The number has no background
 * and sits below the three-dot icon, aligned to the slider width.
 *
 * Positioning: the window coordinates of the custom MIUI {@code MiuiVolumeSeekBar}
 * drift right on repeated shows (870 -> 882 -> 898). Avoid following that drift:
 *   1) Calibrate once after animation settles; cache size and right/top offsets in dp.
 *   2) Reuse cached coordinates on subsequent shows.
 *   3) Fade with the panel while avoiding interference with its slide animations.
 */
final class PanelNumber {

    private static final String TAG = "VolumeFine";
    /** Volume-panel window types: 2014 on AOSP, 2020 on the tested HyperOS build. */
    private static final int[] VOLUME_WINDOW_TYPES = {2014, 2020};

    private static boolean sInstalled;

    private final VolumeFineModule m;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mNumber;
    private ViewGroup mHost;
    private View mPanelDecor;
    private View mSeekBarRef;      // Cache the slider to avoid traversing the view tree on every tick.
    private AudioManager mAudioManager;
    private boolean mPolling;
    private boolean mFadedOut;
    private boolean mStableThisShow;
    private boolean mTreeDumped;
    /** Stop polling after this many consecutive ticks without a visible host. */
    private int mIdleTicks;

    // Position
    private Rect mPrevBounds;
    private Rect mAppliedBounds;
    private int mCalRightDp = -1;
    private int mCalTopDp = -1;
    private int mCalWidthDp = -1;
    private int mCalHeightDp = -1;

    // Colors
    private Rect mTextBarRect;
    private int mTextTop;
    private int mTextHeight;
    private boolean mDarkText;
    private int mStreamMax = -1;   // Cache the media maximum to avoid an IPC on every tick.
    private int mShownVolume = Integer.MIN_VALUE;   // Last displayed level; avoid redundant setText calls.
    private boolean mSwitchOn = true;               // Cached enabled && showNumber.
    private long mSwitchAt;                         // Last switch refresh time.

    PanelNumber(VolumeFineModule module) {
        m = module;
    }

    // ------------------------------------------------------------------ Installation

    void install(ClassLoader cl) throws Throwable {
        if (sInstalled) {
            return;   // onPackageLoaded may repeat; prevent duplicate number views.
        }
        sInstalled = true;

        int hooked = 0;
        for (String clsName : new String[]{"android.view.WindowManagerImpl", "android.view.WindowManagerGlobal"}) {
            try {
                Class<?> cls = Class.forName(clsName, false, cl);
                for (final Method method : cls.getDeclaredMethods()) {
                    if (!method.getName().equals("addView")) {
                        continue;
                    }
                    Class<?>[] ps = method.getParameterTypes();
                    if (ps.length < 2 || ps[0] != View.class) {
                        continue;
                    }
                    method.setAccessible(true);
                    m.hook(method).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object params = chain.getArg(1);
                            if (params instanceof WindowManager.LayoutParams) {
                                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
                                if (m.debugEnabled()) {
                                    m.log(3, TAG, "[sysui] addView type=" + lp.type
                                            + " title=" + lp.getTitle());
                                }
                                if (isVolumePanel(lp)) {
                                    Object view = chain.getArg(0);
                                    if (view instanceof View) {
                                        onVolumePanelAdded((View) view);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            m.log(5, TAG, "[sysui] panel detect failed: " + t);
                        }
                        return result;
                    });
                    hooked++;
                }
            } catch (Throwable t) {
                m.log(5, TAG, "[sysui] hook " + clsName + " failed: " + t);
            }
        }

        m.log(4, TAG, "[sysui] installed, addView hooks=" + hooked);
    }

    /** Identify a volume panel by window type or title. */
    private static boolean isVolumePanel(WindowManager.LayoutParams lp) {
        for (int type : VOLUME_WINDOW_TYPES) {
            if (lp.type == type) {
                return true;
            }
        }
        CharSequence title = lp.getTitle();
        return title != null && title.toString().contains("VolumePanel");
    }

    // ------------------------------------------------------------------ Attachment

    private void onVolumePanelAdded(View decor) {
        try {
            View content = decor.findViewById(android.R.id.content);
            ViewGroup host = content instanceof ViewGroup ? (ViewGroup) content : null;
            if (host == null && decor instanceof ViewGroup) {
                host = (ViewGroup) decor;
            }
            if (host == null) {
                m.log(5, TAG, "[sysui] no content container");
                return;
            }
            mHost = host;
            mPanelDecor = decor;
            m.log(4, TAG, "[sysui] volume panel window detected");

            if (mNumber == null) {
                mNumber = buildView(decor.getContext());
            }
            if (mNumber.getParent() instanceof ViewGroup) {
                ((ViewGroup) mNumber.getParent()).removeView(mNumber);
            }

            mPrevBounds = null;
            mAppliedBounds = null;
            mSeekBarRef = null;
            mFadedOut = false;
            mStableThisShow = false;
            mDarkText = false;
            mTextBarRect = null;
            mShownVolume = Integer.MIN_VALUE;   // Force a text refresh for a new panel.
            // Attach invisibly: the initial anchor is temporary and must not flash onscreen.
            mNumber.animate().cancel();
            mNumber.setAlpha(0f);

            host.addView(mNumber, anchorParams(decor.getContext()));
            mNumber.bringToFront();     // Raise once here; repeated remove/add operations would trigger redraws.
            refreshText();

            // Use calibrated coordinates immediately; fade in once the slider is visible.
            Rect cached = cachedBarRect(decor.getContext());
            if (cached != null) {
                mAppliedBounds = cached;
                applyPosition(cached);
            }

            final ViewTreeObserver.OnGlobalLayoutListener listener =
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                applyExactPosition(decor);
                            } catch (Throwable ignored) {
                            } finally {
                                // Remove this one-shot listener to avoid accumulating callbacks on repeated shows.
                                try {
                                    decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.postDelayed(() -> {
                try {
                    applyExactPosition(decor);
                } catch (Throwable ignored) {
                }
            }, 60);

            startPolling(decor);
        } catch (Throwable t) {
            m.log(5, TAG, "[sysui] attach failed: " + t);
        }
    }

    private FrameLayout.LayoutParams anchorParams(Context ctx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 56), dp(ctx, 22));
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.rightMargin = dp(ctx, 19);
        lp.topMargin = dp(ctx, 100);
        return lp;
    }

    private void startPolling(View decor) {
        mPanelDecor = decor;
        if (mPolling) {
            return;   // The existing loop reads mPanelDecor and follows the new window.
        }
        mPolling = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mNumber == null) {
                    mPolling = false;
                    return;
                }
                // If the host is no longer visible, fade the number with the slider,
                // then stop after several idle ticks to avoid background work.
                boolean hostAlive = mHost != null && mHost.isShown() && mHost.isAttachedToWindow();
                if (!hostAlive) {
                    if (mStableThisShow && !mFadedOut) {
                        fadeOut();               // Fade immediately without waiting for the idle timeout.
                    }
                    mIdleTicks++;
                    if (mIdleTicks >= 10) {      // Confirm closure after roughly 600ms.
                        mPolling = false;
                        mIdleTicks = 0;
                        return;
                    }
                    mHandler.postDelayed(this, 60);
                    return;
                }
                mIdleTicks = 0;

                // The enabled/show-number switches control the in-panel number here.
                if (!numberEnabled()) {
                    if (!mFadedOut) {
                        fadeOut();
                    }
                    mHandler.postDelayed(this, 60);
                    return;
                }

                if (mNumber.getParent() == null) {
                    // Reattach if a reused window rebuilds its contents and removes our view.
                    try {
                        mNumber.setAlpha(0f);
                        mAppliedBounds = null;
                        mPrevBounds = null;
                        mDarkText = false;
                        mTextBarRect = null;
                        mHost.addView(mNumber, anchorParams(mNumber.getContext()));
                        m.log(4, TAG, "[sysui] re-attached number view");
                    } catch (Throwable t) {
                        m.log(5, TAG, "[sysui] re-attach failed: " + t);
                    }
                }
                try {
                    refreshText();

                    // After calibration, use cached bounds but always check slider visibility;
                    // cached bounds alone never become null and would delay fading after the slider disappears.
                    Rect now = null;
                    boolean seekbarVisible = mPanelDecor != null && hasVisibleSeekBar(mPanelDecor);
                    if (seekbarVisible) {
                        if (mStableThisShow && mAppliedBounds != null) {
                            now = cachedBarRect(mNumber.getContext());
                        } else {
                            now = findSliderBounds(mPanelDecor);
                        }
                    }

                    if (now == null) {
                        if (mAppliedBounds != null && mStableThisShow) {
                            fadeOut();          // The slider is hidden: fade out with the panel.
                        }
                    } else if (mPrevBounds != null && mPrevBounds.equals(now)) {
                        mStableThisShow = true;
                        if (mAppliedBounds == null || !mAppliedBounds.equals(now)) {
                            mAppliedBounds = now;
                            applyPosition(now);
                        }
                        fadeIn();
                    } else {
                        mPrevBounds = now;
                        // Cached placement is ready; show as soon as the slider appears.
                        if (mAppliedBounds != null) {
                            mStableThisShow = true;
                            fadeIn();
                        } else if (mStableThisShow) {
                            fadeOut();          // Movement after stability indicates the exit animation.
                        }
                    }
                } catch (Throwable ignored) {
                }
                mHandler.postDelayed(this, 60);
            }
        }, 60);
    }

    /** Cache enabled/show-number switches for one second to reduce settings reads. */
    private boolean numberEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - mSwitchAt > 1000L) {
            mSwitchAt = now;
            mSwitchOn = true;
            try {
                Context ctx = mNumber != null ? mNumber.getContext() : null;
                if (ctx != null) {
                    ContentResolver cr = ctx.getContentResolver();
                    boolean enabled = Settings.System.getInt(cr, "volume_fine_enabled", 1) != 0;
                    boolean show = Settings.System.getInt(cr, "volume_fine_show_number", 1) != 0;
                    mSwitchOn = enabled && show;
                }
            } catch (Throwable ignored) {
            }
        }
        return mSwitchOn;
    }

    /** Refresh text/color only; cache AudioManager to avoid repeated service lookups. */
    private void refreshText() {
        try {
            Context ctx = mNumber.getContext();
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (mAudioManager == null) {
                return;
            }
            int vol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            // Only setText when the volume changes; each call triggers measure/layout and invalidation.
            // Unconditional 60ms updates would cause redundant redraws and visible flicker.
            if (vol != mShownVolume || mShownVolume == Integer.MIN_VALUE) {
                mShownVolume = vol;
                mNumber.setText(String.valueOf(vol));
            }
            updateTextColor(ctx, vol);   // Reuse the volume already read to avoid another IPC.
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ Positioning

    /** Restore slider bounds from calibration; return null before calibration. */
    private Rect cachedBarRect(Context ctx) {
        if (mCalWidthDp <= 0 || mCalHeightDp <= 0) {
            return null;
        }
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int left = screenW - dp(ctx, mCalRightDp) - dp(ctx, mCalWidthDp);
        int top = dp(ctx, mCalTopDp);
        return new Rect(left, top, left + dp(ctx, mCalWidthDp), top + dp(ctx, mCalHeightDp));
    }

    /** Align to the slider when available; otherwise keep the dp anchor. */
    private void applyExactPosition(View decor) {
        if (mNumber == null || mHost == null) {
            return;
        }
        if (m.debugEnabled() && !mTreeDumped) {
            mTreeDumped = true;
            dumpTree(mHost, 0);
        }
        Rect bounds = findSliderBounds(decor);
        if (bounds == null) {
            if (m.debugEnabled()) {
                m.log(3, TAG, "[sysui] slider bounds not found, keep anchor; view="
                        + mNumber.getWidth() + "x" + mNumber.getHeight());
            }
            return;
        }
        if (mAppliedBounds == null || !mAppliedBounds.equals(bounds)) {
            mAppliedBounds = bounds;
            applyPosition(bounds);
        }
    }

    /** Align the number to the slider inside the panel, below the three-dot icon. */
    private void applyPosition(Rect measured) {
        try {
            if (mNumber == null || mHost == null || measured == null) {
                return;
            }
            if (measured.left <= 0 || measured.top <= 0
                    || measured.width() <= 0 || measured.height() <= 0) {
                return;   // Keep the current position until layout settles.
            }
            Context ctx = mNumber.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;

            if (mCalWidthDp <= 0) {
                mCalRightDp = Math.round((screenW - measured.right) / density);
                mCalTopDp = Math.round(measured.top / density);
                mCalWidthDp = Math.round(measured.width() / density);
                mCalHeightDp = Math.round(measured.height() / density);
                if (m.debugEnabled()) {
                    m.log(3, TAG, "[sysui] calibrated right=" + mCalRightDp + "dp top=" + mCalTopDp
                            + "dp size=" + mCalWidthDp + "x" + mCalHeightDp + "dp");
                }
            }

            Rect bar = cachedBarRect(ctx);
            if (bar == null) {
                bar = measured;
            }

            int h = dp(ctx, 22);
            int top = bar.top + dp(ctx, 40);          // Leave space for the three-dot icon.
            top = Math.min(top, bar.bottom - h - dp(ctx, 8));

            int[] hostLoc = new int[2];
            mHost.getLocationInWindow(hostLoc);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bar.width(), h);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = Math.max(0, bar.left - hostLoc[0]);
            lp.topMargin = Math.max(0, top - hostLoc[1]);
            mHost.updateViewLayout(mNumber, lp);

            mTextBarRect = new Rect(bar);
            mTextTop = top;
            mTextHeight = h;
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
            int vol = mAudioManager == null ? 0
                    : mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            updateTextColor(ctx, vol);

            if (m.debugEnabled()) {
                m.log(3, TAG, "[sysui] place bar=" + bar.toShortString()
                        + " host=" + hostLoc[0] + "," + hostLoc[1]
                        + " lp=" + lp.width + "x" + lp.height
                        + " at " + lp.leftMargin + "," + lp.topMargin);
            }
        } catch (Throwable t) {
            m.log(5, TAG, "[sysui] applyPosition failed: " + t);
        }
    }

    // ------------------------------------------------------------------ Colors

    /** Use dark text over white fill and white over glass; hysteresis prevents color flicker. */
    private void updateTextColor(Context ctx, int vol) {
        try {
            if (mNumber == null || mTextBarRect == null || mTextHeight <= 0) {
                return;
            }
            if (mStreamMax <= 0) {
                if (mAudioManager == null) {
                    mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                }
                if (mAudioManager != null) {
                    mStreamMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                }
            }
            int max = mStreamMax;
            if (max <= 0) {
                return;
            }
            float ratio = Math.max(0f, Math.min(1f, vol / (float) max));
            int fillTop = Math.round(mTextBarRect.bottom - ratio * mTextBarRect.height());
            int textBottom = mTextTop + mTextHeight;

            boolean dark;
            if (fillTop <= textBottom - dp(ctx, 1)) {
                dark = true;                        // Fill has reached the text.
            } else if (fillTop >= textBottom + dp(ctx, 6)) {
                dark = false;                       // Fill is below the text.
            } else {
                dark = mDarkText;                   // Keep the previous color within the hysteresis band.
            }
            if (dark != mDarkText) {
                mDarkText = dark;
                applyTextStyle(ctx, dark);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyTextStyle(Context ctx, boolean dark) {
        try {
            if (mNumber == null) {
                return;
            }
            mNumber.setTextColor(dark ? 0xE6202024 : 0xFFF7F7FA);
            mNumber.setShadowLayer(dark ? 0f : dp(ctx, 2), 0f, dp(ctx, 1), 0x66000000);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ View lookup

    /**
     * Reuse the last visible slider after visibility/bounds checks;
     * only traverse the tree when invalid. This avoids most traversal at the 60ms polling rate.
     */
    private View activeSeekBar(View decor) {
        View cached = mSeekBarRef;
        if (cached != null && isValidSeekBar(cached)) {
            return cached;
        }
        View found = findActiveSeekBar(decor, "MiuiVolumeSeekBar");
        if (found == null) {
            found = findActiveSeekBar(decor, "VolumeSeekBar");
        }
        if (found == null) {
            found = findActiveSeekBar(decor, "MiuiVolumeDialogView");
        }
        mSeekBarRef = found;
        return found;
    }

    private boolean isValidSeekBar(View v) {
        if (!v.isShown() || !v.isAttachedToWindow()
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        int screenH = v.getResources().getDisplayMetrics().heightPixels;
        return loc[0] >= 0 && loc[1] >= 0
                && loc[0] + v.getWidth() <= screenW
                && loc[1] + v.getHeight() <= screenH;
    }

    private Rect findSliderBounds(View decor) {
        View bar = activeSeekBar(decor);
        if (bar == null) {
            return null;
        }
        int[] loc = new int[2];
        bar.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1], loc[0] + bar.getWidth(), loc[1] + bar.getHeight());
    }

    /** Choose the largest visible, laid-out slider fully contained within the screen. */
    private View findActiveSeekBar(View v, String simpleName) {
        if (v == null) {
            return null;
        }
        List<View> out = new ArrayList<>();
        collectByClassName(v, simpleName, 0, out);
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        int screenH = v.getResources().getDisplayMetrics().heightPixels;
        View best = null;
        long bestArea = -1;
        for (View cand : out) {
            if (!cand.isShown() || cand.getWidth() <= 0 || cand.getHeight() <= 0) {
                continue;
            }
            int[] loc = new int[2];
            cand.getLocationInWindow(loc);
            if (loc[0] < 0 || loc[1] < 0
                    || loc[0] + cand.getWidth() > screenW
                    || loc[1] + cand.getHeight() > screenH) {
                continue;
            }
            long area = (long) cand.getWidth() * cand.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = cand;
            }
        }
        return best;
    }

    /** Check slider visibility to drive fade-out without allocating bounds. */
    private boolean hasVisibleSeekBar(View decor) {
        if (decor == null) {
            return false;
        }
        return activeSeekBar(decor) != null;
    }

    private void collectByClassName(View v, String simpleName, int depth, List<View> out) {
        if (v == null || depth > 14) {
            return;
        }
        if (v.getClass().getSimpleName().equals(simpleName)) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByClassName(g.getChildAt(i), simpleName, depth + 1, out);
            }
        }
    }

    /** Dump the panel view tree for diagnostics. */
    private void dumpTree(View v, int depth) {
        if (v == null || depth > 10) {
            return;
        }
        try {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(v.getClass().getSimpleName()).append(" [")
                    .append(loc[0]).append(',').append(loc[1]).append(' ')
                    .append(v.getWidth()).append('x').append(v.getHeight()).append(']');
            m.log(4, TAG, "[sysui] tree " + sb);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    dumpTree(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ Other helpers

    private void fadeIn() {
        if (mNumber == null) {
            return;
        }
        mFadedOut = false;
        // Do not call bringToFront on every tick: it removes/readds the view and redraws it,
        // which causes flicker. Raise the view only when attaching.
        if (mNumber.getAlpha() < 1f) {
            mNumber.animate().cancel();
            mNumber.animate().alpha(1f).setDuration(120).start();
        }
    }

    private void fadeOut() {
        if (mNumber == null || mFadedOut) {
            return;
        }
        mFadedOut = true;
        mNumber.animate().cancel();
        mNumber.animate().alpha(0f).setDuration(110).start();
    }

    private TextView buildView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(0xFFF7F7FA);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tv.setShadowLayer(dp(ctx, 2), 0f, dp(ctx, 1), 0x66000000);
        tv.setBackground(null);
        return tv;
    }

    private static int dp(Context ctx, int value) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * value);
    }

}
