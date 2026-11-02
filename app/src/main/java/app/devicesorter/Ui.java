package app.devicesorter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Design tokens for the whole app. Three surfaces (page, card, sunken), ink text,
 * one chroma (teal accent) and one type scale. Screens read these values instead
 * of inventing colours, sizes or radii; the palette is also resolved for night
 * mode, so every screen follows the system theme without per-view checks.
 */
final class Ui {
    // Surfaces and text, resolved from resources in {@link #init(Context)}.
    static int BG, CARD, SOFT, SOFT_DARK;
    /** FAINT is 3.15:1 on the light page: decoration, disabled labels or 18.66sp+ bold only. */
    static int INK, INK_SOFT, MUTED, FAINT, LINE, OUTLINE_STRONG;
    static int DARK, WHITE;
    // Roles: primary is ink, accent is the only colour.
    static int PRIMARY, ON_PRIMARY, ACCENT, ON_ACCENT, ACCENT_SOFT, ON_ACCENT_SOFT;
    static int DANGER, SCRIM, ON_SCRIM;
    static int RIPPLE_DARK, RIPPLE_LIGHT;
    static boolean NIGHT;

    /** Type scale: 28 / 20 / 16 / 14 / 13 / 12 / 11 sp, weights 400/500/700 only. */
    static final float H1 = 28f, H2 = 20f, H3 = 16f, BODY = 14f, LABEL = 13f, CAPTION = 12f, MICRO = 11f;
    /** One radius set: chip 8, control 12, card 16, sheet 20, pill 28. */
    static final int R_CHIP = 8, R_CONTROL = 12, R_CARD = 16, R_SHEET = 20, R_PILL = 28;
    /** Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 dp. */
    static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 24, S6 = 32;

    private Ui() { }

    static void init(Context context) {
        Resources res = context.getResources();
        NIGHT = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        BG = res.getColor(R.color.surface);
        CARD = res.getColor(R.color.surface_container);
        SOFT = res.getColor(R.color.surface_container_low);
        SOFT_DARK = res.getColor(R.color.surface_container_high);
        INK = res.getColor(R.color.on_surface);
        INK_SOFT = res.getColor(R.color.on_surface_variant);
        MUTED = res.getColor(R.color.on_surface_variant);
        FAINT = res.getColor(R.color.on_surface_faint);
        LINE = res.getColor(R.color.outline);
        OUTLINE_STRONG = res.getColor(R.color.outline_strong);
        PRIMARY = res.getColor(R.color.primary);
        ON_PRIMARY = res.getColor(R.color.on_primary);
        ACCENT = res.getColor(R.color.accent);
        ON_ACCENT = res.getColor(R.color.on_accent);
        ACCENT_SOFT = res.getColor(R.color.accent_container);
        ON_ACCENT_SOFT = res.getColor(R.color.on_accent_container);
        DANGER = res.getColor(R.color.error);
        SCRIM = res.getColor(R.color.scrim);
        ON_SCRIM = res.getColor(R.color.on_scrim);
        DARK = NIGHT ? res.getColor(R.color.on_surface) : res.getColor(R.color.primary);
        WHITE = Color.WHITE;
        RIPPLE_DARK = NIGHT ? 0x33ffffff : 0x1f000000;
        RIPPLE_LIGHT = 0x33ffffff;
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Black or white, whichever reads on top of {@code fill}. */
    static int onColor(int fill) {
        double luminance = 0.2126 * channel(Color.red(fill))
                + 0.7152 * channel(Color.green(fill))
                + 0.0722 * channel(Color.blue(fill));
        return luminance > 0.42 ? 0xff111111 : 0xffffffff;
    }

    static int rippleFor(int fill) {
        // A transparent container has no contrast of its own: a white ripple would be
        // invisible on the light page, so fall back to the dark one there.
        if (Color.alpha(fill) == 0) return NIGHT ? RIPPLE_LIGHT : RIPPLE_DARK;
        return onColor(fill) == 0xffffffff ? RIPPLE_LIGHT : RIPPLE_DARK;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static GradientDrawable fill(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    static GradientDrawable outline(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable drawable = fill(color, radiusPx);
        drawable.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        return drawable;
    }

    static GradientDrawable gradient(int top, int bottom, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    static RippleDrawable ripple(int rippleColor, GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /**
     * Settle-in for a surface that appears over existing content: an 8dp rise plus a fade.
     * 180ms stays under the 200ms threshold where a dismissible animation starts to feel late;
     * the system animator duration scale still applies, so reduced-motion users see it instantly.
     */
    static void enter(View view) {
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), S2));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * Exit for the same surface: faster than the 180ms entry, because waiting to leave is
     * what feels slow. {@code after} runs when the fade finishes so dismissal is never skipped.
     */
    static void exit(View view, Runnable after) {
        view.animate()
                .alpha(0f)
                .translationY(dp(view.getContext(), S1))
                .setDuration(120)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(after)
                .start();
    }

    static TextView label(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return view;
    }

    static TextView mono(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = label(context, value, sp, color, bold);
        view.setTypeface(Typeface.create("monospace", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    /** Uppercase-style overline; never smaller than 11sp so it stays legible. */
    static TextView track(Context context, CharSequence value, float sp, int color) {
        TextView view = label(context, value, Math.max(sp, MICRO), color, true);
        view.setLetterSpacing(0.08f);
        return view;
    }

    static TextView title(Context context, CharSequence value) {
        return label(context, value, H1, INK, true);
    }

    static TextView headline(Context context, CharSequence value) {
        return label(context, value, H2, INK, true);
    }

    static TextView body(Context context, CharSequence value, int color) {
        TextView view = label(context, value, BODY, color, false);
        view.setLineSpacing(0f, 1.5f);
        return view;
    }

    static TextView caption(Context context, CharSequence value) {
        return label(context, value, CAPTION, MUTED, false);
    }

    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static View spacer(Context context, float heightDp) {
        android.view.View view = new android.view.View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return view;
    }

    /**
     * Short text action with the visual and the tap area kept apart: the visible pill is
     * sized by the label (36dp minimum, 12dp side padding), while the transparent slot
     * around it is always at least 48x48dp and owns the click. Neighbours are separated
     * by layout margins, never by overlapping expanded targets.
     */
    static final class Action extends FrameLayout {
        private final TextView label;
        private final int fill;
        private int textColor;
        private boolean customDescription;

        Action(Context context, CharSequence text, float sp, int fill, int textColor) {
            super(context);
            this.fill = fill;
            this.textColor = textColor;
            setMinimumWidth(dp(context, 48));
            setMinimumHeight(dp(context, 48));
            setClickable(true);
            setFocusable(true);

            label = label(context, text, sp, textColor, false);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setMinHeight(dp(context, 36));
            label.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            label.setBackground(ripple(rippleFor(fill), fill(fill, dp(context, R_CONTROL))));
            // The slot receives the touch; the pill only mirrors the state so the ripple
            // stays inside the visual instead of flooding the whole 48dp slot.
            label.setDuplicateParentStateEnabled(true);
            label.setClickable(false);
            label.setFocusable(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            super.setContentDescription(text);
        }

        void setText(CharSequence value) {
            label.setText(value);
            if (!customDescription) super.setContentDescription(value);
        }

        CharSequence getText() {
            return label.getText();
        }

        void setTextColor(int color) {
            textColor = color;
            label.setTextColor(color);
        }

        void setTextSize(float sp) {
            label.setTextSize(sp);
        }

        /** Left-align the pill when the slot is wider than the label (e.g. a weighted row).
         *  A start-aligned inline action has no visible pill, so drop the leading inset to
         *  keep the text on the same axis as the surrounding copy. */
        void alignStart() {
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setPadding(0, dp(getContext(), 8), dp(getContext(), 12), dp(getContext(), 8));
            LayoutParams params = (LayoutParams) label.getLayoutParams();
            params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            label.setLayoutParams(params);
        }

        @Override public void setContentDescription(CharSequence value) {
            customDescription = true;
            super.setContentDescription(value);
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            int background = enabled ? fill : (Color.alpha(fill) == 0 ? fill : SOFT_DARK);
            label.setBackground(ripple(rippleFor(background), fill(background, dp(getContext(), R_CONTROL))));
            label.setTextColor(enabled ? textColor : FAINT);
        }
    }

    /**
     * Whole-row switch. The 56dp row is the only click target; the Switch keeps its
     * default 52x32 visual and is made non-interactive so there is never a second,
     * overlapping 48dp target inside the row.
     */
    static final class SwitchRow extends LinearLayout {
        final Switch toggle;
        private CompoundButton.OnCheckedChangeListener listener;

        SwitchRow(Context context, CharSequence title, CharSequence subtitle, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 56));
            setPadding(0, dp(context, 4), 0, dp(context, 4));
            setBackground(ripple(rippleFor(Color.TRANSPARENT),
                    fill(Color.TRANSPARENT, dp(context, R_CONTROL))));
            setClickable(true);
            setFocusable(true);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            TextView name = label(context, title, LABEL, INK, true);
            TextView detail = label(context, subtitle, CAPTION, MUTED, false);
            detail.setPadding(0, dp(context, 2), 0, 0);
            texts.addView(name);
            texts.addView(detail);
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            toggle = new Switch(context);
            toggle.setChecked(checked);
            toggle.setClickable(false);
            toggle.setFocusable(false);
            toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout slot = new FrameLayout(context);
            slot.setMinimumWidth(dp(context, 48));
            slot.setMinimumHeight(dp(context, 48));
            slot.addView(toggle, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            LayoutParams slotParams = new LayoutParams(dp(context, 52), dp(context, 48));
            slotParams.setMargins(dp(context, S3), 0, 0, 0);
            addView(slot, slotParams);

            super.setContentDescription(title + "。" + subtitle);
            setOnClickListener(v -> {
                if (!isEnabled()) return;
                toggle.setChecked(!toggle.isChecked());
                if (listener != null) listener.onCheckedChanged(toggle, toggle.isChecked());
            });
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener value) {
            listener = value;
        }

        boolean isChecked() {
            return toggle.isChecked();
        }
    }

    /** In-app message using the app palette instead of the system toast chrome. */
    static void toast(Context context, CharSequence message) {
        toast(context, message, false);
    }

    static void toastLong(Context context, CharSequence message) {
        toast(context, message, true);
    }

    private static void toast(Context context, CharSequence message, boolean longDuration) {
        Toast toast = new Toast(context);
        TextView view = label(context, message, LABEL, ON_SCRIM, false);
        view.setLineSpacing(0f, 1.4f);
        view.setPadding(dp(context, S4), dp(context, S3), dp(context, S4), dp(context, S3));
        view.setBackground(fill(SCRIM, dp(context, R_CONTROL)));
        toast.setView(view);
        toast.setGravity(Gravity.BOTTOM, 0, dp(context, 96));
        toast.setDuration(longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
    }

    // ---- Shared building blocks -------------------------------------------------
    // These used to live inside MainActivity, where every screen grew its own copy
    // and the numbers drifted (48dp primary here, 52dp there). One implementation
    // means the card outline, the two button weights and the row heights can only
    // be changed in one place.

    /** Card surface: 16dp padding, card radius, 1px hairline — the only card look. */
    static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setPadding(dp(context, S4), dp(context, S4), dp(context, S4), dp(context, S4));
        card.setBackground(outline(CARD, dp(context, R_CARD), LINE, dp(context, 1)));
        return card;
    }

    /** Adds a card to a page with the 16dp gap the spec asks between cards. */
    static void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(parent.getContext(), S4));
        parent.addView(card, params);
    }

    /**
     * Primary action. Height is a minimum, never a fixed height, so a long label at
     * fontScale 1.3 wraps into a taller button instead of being clipped.
     */
    static Button primaryButton(Context context, CharSequence value, int fill,
                                View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(BODY);
        button.setAllCaps(false);
        button.setLetterSpacing(0f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setPadding(dp(context, S4), dp(context, S3), dp(context, S4), dp(context, S3));
        button.setTextColor(onColor(fill));
        button.setBackground(ripple(rippleFor(fill), fill(fill, dp(context, R_CONTROL))));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setOnClickListener(listener);
        return button;
    }

    /** Secondary action: hairline outline, no fill, so one solid button keeps the hierarchy. */
    static Button quietButton(Context context, CharSequence value, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(BODY);
        button.setAllCaps(false);
        button.setLetterSpacing(0f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setPadding(dp(context, S4), dp(context, S3), dp(context, S4), dp(context, S3));
        button.setTextColor(INK);
        button.setBackground(ripple(RIPPLE_DARK,
                outline(Color.TRANSPARENT, dp(context, R_CONTROL), OUTLINE_STRONG, dp(context, 1))));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setOnClickListener(listener);
        return button;
    }

    /** Disabled primary actions go quiet instead of fading to an ambiguous grey. */
    static void setPrimaryEnabled(Button button, boolean enabled) {
        if (button == null || button.isEnabled() == enabled) return;
        button.setEnabled(enabled);
        int fillColor = enabled ? PRIMARY : SOFT_DARK;
        button.setBackground(ripple(rippleFor(fillColor), fill(fillColor,
                dp(button.getContext(), R_CONTROL))));
        button.setTextColor(enabled ? onColor(fillColor) : FAINT);
        button.setAlpha(1f);
    }

    /** Compact 48dp-tall text action for headers and inline links. */
    static Action smallAction(Context context, CharSequence value, int fill,
                              View.OnClickListener listener) {
        Action action = new Action(context, value, LABEL, fill, onColor(fill));
        action.setOnClickListener(listener);
        return action;
    }

    /** 48x48 icon target; the glyph stays 24dp so the tap area never shrinks with it. */
    static ImageButton iconButton(Context context, int drawable, String description,
                                  View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setColorFilter(INK);
        button.setPadding(dp(context, S3), dp(context, S3), dp(context, S3), dp(context, S3));
        button.setBackground(ripple(RIPPLE_DARK, fill(Color.TRANSPARENT, dp(context, R_PILL))));
        button.setStateListAnimator(null);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        button.setOnClickListener(listener);
        return button;
    }

    /** Numbered section header: mono chip, 16sp title. */
    static View sectionHeader(Context context, LinearLayout parent, String number, String title) {
        LinearLayout header = row(context);
        header.setPadding(0, dp(context, S4), 0, dp(context, S3));
        TextView badge = mono(context, number, MICRO, ACCENT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(fill(ACCENT_SOFT, dp(context, R_CHIP)));
        header.addView(badge, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 24)));
        TextView label = label(context, title, H3, INK, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(context, S3), 0, 0, 0);
        header.addView(label, titleParams);
        parent.addView(header);
        return header;
    }

    /** Quiet group label with a hairline rule; 12sp, used above a card cluster. */
    static void sectionLabel(Context context, LinearLayout parent, CharSequence value) {
        LinearLayout row = row(context);
        row.setPadding(0, dp(context, S5), 0, dp(context, S3));
        row.addView(label(context, value, CAPTION, MUTED, true));
        View rule = new View(context);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(0, dp(context, 1), 1);
        ruleParams.setMargins(dp(context, S3), 0, 0, 0);
        row.addView(rule, ruleParams);
        parent.addView(row);
    }

    /**
     * Small "?" that opens a bubble above the finger. The caller owns the bubble,
     * so this stays a plain view factory with no popup state of its own.
     */
    static View hintButton(Context context, final CharSequence message,
                           final View.OnClickListener onHint) {
        FrameLayout wrap = new FrameLayout(context);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        TextView mark = label(context, "?", CAPTION, MUTED, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(fill(SOFT, dp(context, 13)));
        wrap.addView(mark, new FrameLayout.LayoutParams(dp(context, 26), dp(context, 26),
                Gravity.CENTER));
        wrap.setClickable(true);
        wrap.setFocusable(true);
        wrap.setContentDescription("提示：" + message);
        wrap.setOnClickListener(onHint);
        wrap.setOnLongClickListener(v -> {
            onHint.onClick(v);
            return true;
        });
        return wrap;
    }

    /** List row: 44dp icon plate, 16sp title, 13sp subtitle, chevron; whole row is the target. */
    static View toolRow(Context context, int iconRes, CharSequence title, CharSequence subtitle,
                        View.OnClickListener listener) {
        LinearLayout item = row(context);
        item.setPadding(dp(context, S3), dp(context, S3), dp(context, S3), dp(context, S3));
        item.setBackground(ripple(RIPPLE_DARK, fill(Color.TRANSPARENT, dp(context, R_CONTROL))));
        FrameLayout iconBox = new FrameLayout(context);
        iconBox.setBackground(fill(SOFT, dp(context, R_CONTROL)));
        ImageView glyph = new ImageView(context);
        glyph.setImageResource(iconRes);
        glyph.setColorFilter(INK);
        iconBox.addView(glyph, new FrameLayout.LayoutParams(dp(context, 22), dp(context, 22),
                Gravity.CENTER));
        item.addView(iconBox, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        LinearLayout texts = column(context);
        texts.setPadding(dp(context, S3), 0, dp(context, S2), 0);
        texts.addView(label(context, title, H3, INK, true));
        TextView caption = label(context, subtitle, LABEL, MUTED, false);
        caption.setPadding(0, dp(context, 4), 0, 0);
        texts.addView(caption);
        item.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(FAINT);
        item.addView(chevron, new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(title + "。" + subtitle);
        item.setOnClickListener(listener);
        return item;
    }

    /** 1px divider; {@code insetDp} is the leading gap, the trailing gap is always 12dp. */
    static void hairline(LinearLayout parent, int insetDp) {
        Context context = parent.getContext();
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1));
        params.setMargins(dp(context, insetDp), 0, dp(context, S3), 0);
        parent.addView(divider, params);
    }

    /** Vertical breathing room without a spacer view class. */
    static void space(LinearLayout parent, float heightDp) {
        Context context = parent.getContext();
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(context, heightDp)));
    }
}