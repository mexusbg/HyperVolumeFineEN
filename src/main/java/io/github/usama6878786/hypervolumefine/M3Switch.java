package io.github.usama6878786.hypervolumefine;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Material 3 switch.
 * Off: 16dp thumb, 32dp track with a 2dp outline. On: 24dp thumb and solid primary track.
 */
public class M3Switch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(M3Switch view, boolean checked);
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrack = new RectF();

    private boolean mChecked;
    private float mFraction;
    private ValueAnimator mAnimator;
    private OnCheckedChangeListener mListener;

    private int mColorPrimary;
    private int mColorOnPrimary;
    private int mColorTrackOff;
    private int mColorThumbOff;
    private int mColorOutline;

    private final float mTrackWidth;
    private final float mTrackHeight;
    private final float mThumbOn;
    private final float mThumbOff;

    public M3Switch(Context context) {
        this(context, null);
    }

    public M3Switch(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        mTrackWidth = 52 * d;
        mTrackHeight = 32 * d;
        mThumbOn = 24 * d;
        mThumbOff = 16 * d;

        mColorPrimary = context.getColor(R.color.vf_primary);
        mColorOnPrimary = context.getColor(R.color.vf_on_primary);
        mColorTrackOff = context.getColor(R.color.vf_switch_track_off);
        mColorThumbOff = context.getColor(R.color.vf_switch_thumb_off);
        mColorOutline = context.getColor(R.color.vf_outline);

        setClickable(true);
        setFocusable(true);
    }

    public void setChecked(boolean checked, boolean animate) {
        if (mChecked == checked && mFraction == (checked ? 1f : 0f)) {
            return;
        }
        mChecked = checked;
        float target = checked ? 1f : 0f;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        if (!animate) {
            mFraction = target;
            invalidate();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mFraction, target);
        mAnimator.setDuration(180);
        mAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        mAnimator.addUpdateListener(a -> {
            mFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        mListener = l;
    }

    /** Toggle from the control click handler. */
    public void toggle() {
        setChecked(!mChecked, true);
        if (mListener != null) {
            mListener.onCheckedChanged(this, mChecked);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize((int) Math.ceil(mTrackWidth), widthMeasureSpec);
        int h = resolveSize((int) Math.ceil(mTrackHeight), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float f = mFraction;
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;

        mTrack.set(0, 0, w, h);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(blend(mColorTrackOff, mColorPrimary, f));
        canvas.drawRoundRect(mTrack, radius, radius, mPaint);

        if (f < 1f) {
            float stroke = Math.max(1f, 2f * getResources().getDisplayMetrics().density);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(stroke);
            mPaint.setColor(withAlpha(mColorOutline, (int) (255 * (1f - f))));
            float inset = stroke / 2f;
            mTrack.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(mTrack, radius - inset, radius - inset, mPaint);
        }

        float thumb = mThumbOff + (mThumbOn - mThumbOff) * f;
        float marginOff = 8f * getResources().getDisplayMetrics().density;
        float marginOn = 4f * getResources().getDisplayMetrics().density;
        float cx = (marginOff + mThumbOff / 2f) + ((w - marginOn - mThumbOn / 2f) - (marginOff + mThumbOff / 2f)) * f;
        float cy = h / 2f;

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(blend(mColorThumbOff, mColorOnPrimary, f));
        canvas.drawCircle(cx, cy, thumb / 2f, mPaint);
    }

    private static int blend(int from, int to, float t) {
        int a = (int) (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t);
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
