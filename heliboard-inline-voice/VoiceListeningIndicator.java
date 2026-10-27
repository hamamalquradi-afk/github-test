/*
 * KEYNOVA SMART: in-place live voice strip for the existing keyboard.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One persistent microphone/stop control and an RMS-driven waveform.
 * Covers only the existing strip while voice is enabled, not the keyboard.
 * Restores the old strip untouched when dictation stops.
 */
final class VoiceListeningIndicator extends LinearLayout {
    private static final int RECONNECTING = 1;
    private static final int LISTENING = 2;
    private static final int GREEN = Color.rgb(29, 171, 101);
    private static final int AMBER = Color.rgb(230, 153, 37);

    private final ImageButton microphone;
    private final TextView status;
    private final Waveform waveform;

    VoiceListeningIndicator(Context context, Runnable onStop) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setPadding(dp(3), dp(2), dp(8), dp(2));
        final boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setBackgroundColor(dark ? Color.rgb(43, 48, 54) : Color.rgb(241, 237, 231));

        microphone = new ImageButton(context);
        microphone.setImageResource(android.R.drawable.ic_btn_speak_now);
        microphone.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        microphone.setPadding(dp(11), dp(8), dp(11), dp(8));
        microphone.setOnClickListener(v -> onStop.run());
        microphone.setFocusable(true);
        microphone.setContentDescription("إيقاف الميكروفون");
        addView(microphone, new LayoutParams(dp(46), LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setTextDirection(View.TEXT_DIRECTION_RTL);
        status.setTextColor(dark ? Color.WHITE : Color.rgb(38, 43, 49));
        status.setSingleLine(true);
        addView(status, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));

        waveform = new Waveform(context);
        addView(waveform, new LayoutParams(dp(80), LayoutParams.MATCH_PARENT));
        setState(RECONNECTING);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void showIn(FrameLayout container) {
        if (getParent() == container) return;
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        container.addView(this, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        bringToFront();
    }

    void hide() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        waveform.reset();
    }

    void setState(int state) {
        final boolean ready = state == LISTENING;
        status.setText(ready ? "جار الاستماع..." : "جار إعادة الاستماع...");
        microphone.setColorFilter(ready ? GREEN : AMBER);
        waveform.setActive(ready);
        if (!ready) waveform.reset();
        microphone.setContentDescription(ready
                ? "الميكروفون يستمع، اضغط لإيقافه"
                : "الميكروفون يعيد الاتصال، اضغط لإيقافه");
    }

    void setRms(float rms) {
        waveform.setLevel(normalizeRms(rms));
    }

    /** No fabricated motion: only actual recognition-service RMS moves bars. */
    static float normalizeRms(float rms) {
        if (Float.isNaN(rms) || Float.isInfinite(rms)) return 0f;
        return Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
    }

    private static final class Waveform extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active;
        private float level;

        Waveform(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setActive(boolean value) {
            active = value;
            invalidate();
        }

        void setLevel(float value) {
            if (!active) return;
            level = value;
            invalidate();
        }

        void reset() {
            level = 0f;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(active ? GREEN : AMBER);
            final float density = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(Math.max(2f * density, getWidth() / 28f));
            final float[] rhythm = {0.42f, 0.65f, 0.85f, 1f, 0.85f, 0.65f, 0.42f};
            final float centerY = getHeight() * 0.5f;
            final float base = 2f * density;
            final float dynamic = Math.max(0, getHeight() * 0.39f - base) * level;
            for (int i = 0; i < rhythm.length; i++) {
                final float x = getWidth() * (i + 1f) / (rhythm.length + 1f);
                final float half = base + (active ? dynamic * rhythm[i] : 0f);
                canvas.drawLine(x, centerY - half, x, centerY + half, paint);
            }
        }
    }
}
