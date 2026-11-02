package app.devicesorter;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating tab bar: 88x52 cells with a 76x44 pill inside, selected state as a tinted
 * fill plus bold label. It owns its own visibility animation because "hide while the
 * content moves" is a property of the bar, not of the page that happens to scroll;
 * when to hide is still decided by the Activity, which is the only place that can
 * tell a fling from a scroll.
 */
final class BottomNav {
    /** Bar height the pages reserve at the bottom; the single owner of this number. */
    static final int HEIGHT = 64;

    interface Host {
        /** A different tab was tapped. */
        void open(int page);

        /** The tab for the page already on screen was tapped again. */
        void scrollToTop(int page);
    }

    private final Activity activity;
    private final Host host;
    private final LinearLayout bar;
    private final List<Integer> pages = new ArrayList<>();
    private final List<FrameLayout> cells = new ArrayList<>();
    private final List<LinearLayout> pills = new ArrayList<>();
    private final List<ImageView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private boolean hidden;
    private int selected = Integer.MIN_VALUE;

    BottomNav(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        bar = Ui.row(activity);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(Ui.outline(Ui.CARD, dp(Ui.R_PILL), Ui.LINE, dp(1)));
        bar.setElevation(dp(10));
    }

    LinearLayout view() {
        return bar;
    }

    void addTab(int iconRes, String label, int page) {
        // 88x52 cell is the tap target; the 76x44 pill inside is what the eye sees, and
        // cells keep 8dp of air between them so expanded targets can never overlap.
        FrameLayout cell = new FrameLayout(activity);
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(dp(88), dp(52));
        cellParams.setMargins(pages.isEmpty() ? 0 : dp(Ui.S2), 0, 0, 0);
        cell.setLayoutParams(cellParams);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(label);

        LinearLayout pill = Ui.column(activity);
        pill.setGravity(Gravity.CENTER);
        pill.setDuplicateParentStateEnabled(true);
        ImageView glyph = new ImageView(activity);
        glyph.setImageResource(iconRes);
        glyph.setColorFilter(Ui.MUTED);
        pill.addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView caption = Ui.label(activity, label, Ui.MICRO, Ui.MUTED, true);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        captionParams.setMargins(0, dp(Ui.S1), 0, 0);
        pill.addView(caption, captionParams);
        cell.addView(pill, new FrameLayout.LayoutParams(dp(76), dp(44), Gravity.CENTER));

        cell.setOnClickListener(v -> {
            if (page == selected) host.scrollToTop(page);
            else host.open(page);
        });

        pages.add(page);
        cells.add(cell);
        pills.add(pill);
        icons.add(glyph);
        labels.add(caption);
        bar.addView(cell);
    }

    void select(int page) {
        selected = page;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = pages.get(i) == page;
            int fill = active ? Ui.SOFT_DARK : Color.TRANSPARENT;
            int foreground = active ? Ui.INK : Ui.MUTED;
            pills.get(i).setBackground(Ui.ripple(Ui.RIPPLE_DARK, Ui.fill(fill, dp(Ui.R_PILL))));
            icons.get(i).setColorFilter(foreground);
            labels.get(i).setTextColor(foreground);
            labels.get(i).setTypeface(Typeface.create("sans-serif-medium",
                    active ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    /** Keeps the bar clear of the gesture inset without the Activity touching its params. */
    void setBottomInset(int marginPx) {
        if (!(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        params.bottomMargin = marginPx;
        bar.setLayoutParams(params);
    }

    void hide() {
        if (hidden) return;
        hidden = true;
        bar.animate().cancel();
        bar.animate().alpha(0f).translationY(dp(HEIGHT)).setDuration(180)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (hidden) bar.setVisibility(View.INVISIBLE);
                }).start();
    }

    void show() {
        boolean wasHidden = hidden;
        hidden = false;
        bar.animate().cancel();
        bar.setVisibility(View.VISIBLE);
        if (!wasHidden && bar.getAlpha() == 1f) return;
        bar.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private int dp(int value) {
        return Ui.dp(activity, value);
    }
}