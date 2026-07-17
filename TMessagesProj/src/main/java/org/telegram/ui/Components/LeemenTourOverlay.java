package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Spotlight coach-mark overlay: dims the whole screen, punches a rounded "hole" around a target
 * area, and shows a tooltip bubble (text + action button) with an arrow pointing at that area.
 *
 * Reusable across tour steps: call {@link #setStep} to move the spotlight + retarget the bubble.
 * The hole animates between steps. All touches are swallowed except the bubble's button, so the
 * underlying screen can't be interacted with mid-tour.
 */
public class LeemenTourOverlay extends FrameLayout {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF currentHole = new RectF();
    private final RectF fromHole = new RectF();
    private final RectF toHole = new RectF();
    private float holeRadius;
    private float fromRadius, toRadius;
    private boolean hasHole;
    private ValueAnimator holeAnimator;

    private final Bubble bubble;
    private final Runnable positionBubble = this::positionBubble;

    public LeemenTourOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true); // swallow taps that miss the button so the list stays locked during the tour

        scrimPaint.setColor(0xB8000000);
        holePaint.setColor(Color.BLACK);
        holePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        holeStrokePaint.setStyle(Paint.Style.STROKE);
        holeStrokePaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
        holeStrokePaint.setColor(0x4DFFFFFF); // soft white rim so the cut-out reads as "highlighted"

        bubble = new Bubble(context);
        addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Let a tap that lands inside the spotlight reach the highlighted control underneath, so the user
        // can perform the step's action directly (not only via the bubble's button). The scrim still
        // swallows everything else, and the bubble's button keeps working (it sits outside the hole).
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && hasHole
                && (holeAnimator == null || !holeAnimator.isRunning())
                && currentHole.contains(ev.getX(), ev.getY())) {
            return false;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Move the spotlight to {@code target} (in this overlay's coordinates) and show the bubble.
     *
     * @param target     area to highlight
     * @param radius     corner radius of the hole
     * @param text       explanation shown in the bubble
     * @param counter    short progress label (e.g. "Step 1 of 4"); pass null/empty to hide it
     * @param buttonText label for the advance button (e.g. "Next" / "Done")
     * @param onNext     invoked when the button is tapped
     */
    public void setStep(RectF target, float radius, CharSequence text, CharSequence counter,
                        CharSequence buttonText, OnClickListener onNext) {
        bubble.setContent(text, counter, buttonText, onNext);
        if (!hasHole) {
            currentHole.set(target);
            holeRadius = radius;
            hasHole = true;
            invalidate();
        } else {
            animateHoleTo(target, radius);
        }
        // Hide the bubble until it's repositioned for the new target, then fade it in.
        bubble.setAlpha(0f);
        AndroidUtilities.cancelRunOnUIThread(positionBubble);
        AndroidUtilities.runOnUIThread(positionBubble);
    }

    private void animateHoleTo(RectF target, float radius) {
        if (holeAnimator != null) {
            holeAnimator.cancel();
        }
        fromHole.set(currentHole);
        toHole.set(target);
        fromRadius = holeRadius;
        toRadius = radius;
        holeAnimator = ValueAnimator.ofFloat(0f, 1f);
        holeAnimator.setDuration(260);
        holeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        holeAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            currentHole.left = AndroidUtilities.lerp(fromHole.left, toHole.left, t);
            currentHole.top = AndroidUtilities.lerp(fromHole.top, toHole.top, t);
            currentHole.right = AndroidUtilities.lerp(fromHole.right, toHole.right, t);
            currentHole.bottom = AndroidUtilities.lerp(fromHole.bottom, toHole.bottom, t);
            holeRadius = AndroidUtilities.lerp(fromRadius, toRadius, t);
            invalidate();
        });
        holeAnimator.start();
    }

    private void positionBubble() {
        if (getWidth() == 0 || getHeight() == 0) {
            AndroidUtilities.runOnUIThread(positionBubble, 16);
            return;
        }
        int sideMargin = AndroidUtilities.dp(16);
        int maxW = getWidth() - 2 * sideMargin;
        // Wrap to content (capped at maxW) so the bubble is snug and centered on the target, instead of a
        // full-width card that strands the button in the far corner.
        bubble.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
        int bw = bubble.getMeasuredWidth();
        int bh = bubble.getMeasuredHeight();

        int gap = AndroidUtilities.dp(10);
        float belowY = toTargetBottom() + gap;
        float aboveY = toTargetTop() - gap - bh;
        boolean below = belowY + bh <= getHeight() - AndroidUtilities.dp(16) || aboveY < AndroidUtilities.dp(16);
        float y = below ? belowY : aboveY;

        // Center horizontally on the target, clamped to the screen's side margins.
        float left = targetCenterX() - bw / 2f;
        left = Math.max(sideMargin, Math.min(getWidth() - sideMargin - bw, left));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        lp.width = bw;
        lp.height = bh;
        lp.leftMargin = (int) left;
        lp.topMargin = 0;
        bubble.setLayoutParams(lp);
        bubble.setTranslationX(0);
        bubble.setTranslationY(y);

        float arrowCx = targetCenterX() - left;
        bubble.setArrow(below ? Bubble.ARROW_TOP : Bubble.ARROW_BOTTOM, arrowCx);

        bubble.animate().alpha(1f).setDuration(160).start();
    }

    private float targetCenterX() { return currentTarget().centerX(); }
    private float toTargetTop() { return currentTarget().top; }
    private float toTargetBottom() { return currentTarget().bottom; }
    private RectF currentTarget() {
        // While animating, point the bubble at the destination so it doesn't chase the hole.
        return holeAnimator != null && holeAnimator.isRunning() ? toHole : currentHole;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasHole) {
            canvas.drawColor(scrimPaint.getColor());
            return;
        }
        int saved = canvas.saveLayer(null, null);
        canvas.drawColor(scrimPaint.getColor());
        canvas.drawRoundRect(currentHole, holeRadius, holeRadius, holePaint);
        canvas.restoreToCount(saved);
        canvas.drawRoundRect(currentHole, holeRadius, holeRadius, holeStrokePaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (hasHole) {
            AndroidUtilities.cancelRunOnUIThread(positionBubble);
            AndroidUtilities.runOnUIThread(positionBubble);
        }
    }

    /** Rounded card with text + advance button and a triangular arrow on one edge. */
    private static class Bubble extends LinearLayout {
        static final int ARROW_NONE = 0, ARROW_TOP = 1, ARROW_BOTTOM = 2;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private final RectF rect = new RectF();
        private final float radius = AndroidUtilities.dp(12);
        private final int arrowH = AndroidUtilities.dp(8);
        private final int arrowHalfW = AndroidUtilities.dp(8);
        private final int padH = AndroidUtilities.dp(16);
        private final int padV = AndroidUtilities.dp(14);

        private int arrowEdge = ARROW_NONE;
        private float arrowCenterX;

        private final TextView text;
        private final TextView counter;
        private final TextView button;

        Bubble(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setMinimumWidth(AndroidUtilities.dp(240)); // keep the counter + button footer comfortable
            setWillNotDraw(false);
            bgPaint.setColor(Theme.getColor(Theme.key_dialogBackground));
            applyPadding();

            text = new TextView(context);
            text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams flp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            flp.topMargin = AndroidUtilities.dp(10);
            addView(footer, flp);

            // Step counter on the left, advance button on the right (weight pushes them apart).
            counter = new TextView(context);
            counter.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            footer.addView(counter, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            button = new TextView(context);
            button.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            button.setTypeface(AndroidUtilities.bold());
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            button.setGravity(Gravity.CENTER);
            button.setBackground(Theme.createRadSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector), AndroidUtilities.dp(8), AndroidUtilities.dp(8)));
            button.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
            footer.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        void setContent(CharSequence body, CharSequence counterText, CharSequence buttonText, OnClickListener onNext) {
            text.setText(body);
            counter.setText(counterText);
            counter.setVisibility(counterText == null || counterText.length() == 0 ? GONE : VISIBLE);
            button.setText(buttonText);
            button.setOnClickListener(onNext);
        }

        void setArrow(int edge, float centerX) {
            arrowEdge = edge;
            arrowCenterX = centerX;
            applyPadding();
            invalidate();
        }

        private void applyPadding() {
            setPadding(padH, padV + (arrowEdge == ARROW_TOP ? arrowH : 0),
                    padH, padV + (arrowEdge == ARROW_BOTTOM ? arrowH : 0));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int top = arrowEdge == ARROW_TOP ? arrowH : 0;
            int bottom = getHeight() - (arrowEdge == ARROW_BOTTOM ? arrowH : 0);
            rect.set(0, top, getWidth(), bottom);
            canvas.drawRoundRect(rect, radius, radius, bgPaint);

            if (arrowEdge != ARROW_NONE) {
                float cx = Math.max(radius + arrowHalfW, Math.min(getWidth() - radius - arrowHalfW, arrowCenterX));
                arrowPath.reset();
                if (arrowEdge == ARROW_TOP) {
                    arrowPath.moveTo(cx - arrowHalfW, top);
                    arrowPath.lineTo(cx + arrowHalfW, top);
                    arrowPath.lineTo(cx, 0);
                } else {
                    arrowPath.moveTo(cx - arrowHalfW, bottom);
                    arrowPath.lineTo(cx + arrowHalfW, bottom);
                    arrowPath.lineTo(cx, getHeight());
                }
                arrowPath.close();
                canvas.drawPath(arrowPath, bgPaint);
            }
        }
    }
}
