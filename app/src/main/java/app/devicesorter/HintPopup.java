package app.devicesorter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * Tap-to-explain bubble. It anchors to the control that opened it, flips above the
 * finger when there is no room below, and dismisses itself after 4.6s so it can
 * never block the next tap. One bubble at a time, one pending auto-dismiss: that
 * state used to sit in the Activity and is the only reason it needed a PopupWindow.
 */
final class HintPopup {
    private static final long AUTO_DISMISS_MS = 4600L;

    private final Activity activity;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoDismiss = this::dismiss;
    private PopupWindow popup;

    HintPopup(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
    }

    void show(View anchor, CharSequence message) {
        if (root == null || anchor == null || message == null || message.length() == 0) return;
        handler.removeCallbacks(autoDismiss);
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Math.min(dp(310), screenWidth - dp(40));
        TextView bubble = new TextView(activity);
        bubble.setText(message);
        bubble.setTextSize(13);
        bubble.setTextColor(Ui.ON_SCRIM);
        bubble.setLineSpacing(dp(4), 1f);
        bubble.setPadding(dp(15), dp(12), dp(15), dp(12));
        bubble.setBackground(Ui.fill(Ui.SCRIM, dp(Ui.R_CONTROL)));
        bubble.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = bubble.getMeasuredWidth();
        int bubbleHeight = bubble.getMeasuredHeight();
        int pointerHeight = dp(7);
        int totalHeight = bubbleHeight + pointerHeight;

        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        Rect visible = new Rect();
        root.getWindowVisibleDisplayFrame(visible);
        int gap = dp(10);
        boolean above = location[1] - visible.top >= totalHeight + gap + dp(8);

        LinearLayout shell = Ui.column(activity);
        PopupWindow window = new PopupWindow(shell, width, totalHeight, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setElevation(dp(14));
        window.setOnDismissListener(() -> {
            if (popup == window) popup = null;
            handler.removeCallbacks(autoDismiss);
        });

        int dx = anchor.getWidth() / 2 - width / 2;
        int bubbleLeft = location[0] + dx;
        int minLeft = dp(16);
        int maxLeft = screenWidth - dp(16) - width;
        if (bubbleLeft < minLeft) dx += minLeft - bubbleLeft;
        else if (bubbleLeft > maxLeft) dx -= bubbleLeft - maxLeft;
        int pointerWidth = dp(14);
        int anchorCenter = location[0] + anchor.getWidth() / 2;
        int pointerLeft = anchorCenter - (location[0] + dx) - pointerWidth / 2;
        pointerLeft = Math.max(dp(12), Math.min(width - dp(12) - pointerWidth, pointerLeft));
        LinearLayout.LayoutParams pointerParams = new LinearLayout.LayoutParams(pointerWidth, pointerHeight);
        pointerParams.leftMargin = pointerLeft;
        if (!above) {
            shell.addView(new HintPointer(activity, Ui.SCRIM, true), pointerParams);
            shell.addView(bubble, new LinearLayout.LayoutParams(width, bubbleHeight));
        } else {
            shell.addView(bubble, new LinearLayout.LayoutParams(width, bubbleHeight));
            shell.addView(new HintPointer(activity, Ui.SCRIM, false), pointerParams);
        }
        int dy = above ? -(anchor.getHeight() + gap + totalHeight) : gap;
        window.showAsDropDown(anchor, dx, dy);
        popup = window;
        shell.setAlpha(0f);
        shell.setTranslationY(above ? dp(6) : -dp(6));
        shell.animate().alpha(1f).translationY(0f).setDuration(140).start();
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
    }

    void dismiss() {
        handler.removeCallbacks(autoDismiss);
        if (popup == null) return;
        final PopupWindow current = popup;
        popup = null;
        View content = current.getContentView();
        content.animate().alpha(0f).setDuration(110)
                .withEndAction(current::dismiss).start();
    }

    private int dp(int value) {
        return Ui.dp(activity, value);
    }

    /** The tooltip sits above the finger; this pointer says which control it belongs to. */
    private static final class HintPointer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean up;

        HintPointer(Context context, int color, boolean up) {
            super(context);
            paint.setColor(color);
            this.up = up;
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            Path path = new Path();
            if (up) {
                path.moveTo(0, h);
                path.lineTo(w, h);
                path.lineTo(w / 2f, 0);
            } else {
                path.moveTo(0, 0);
                path.lineTo(w, 0);
                path.lineTo(w / 2f, h);
            }
            path.close();
            canvas.drawPath(path, paint);
        }
    }
}