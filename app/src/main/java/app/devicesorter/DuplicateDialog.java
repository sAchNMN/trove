package app.devicesorter;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Shows byte-identical groups and lets the user pick which copies go to the recycle bin. */
final class DuplicateDialog {
    private static int GREEN = Ui.INK, INK = Ui.INK, MUTED = Ui.MUTED;

    private DuplicateDialog() {}

    static void show(Activity activity, List<Duplicates.Group> groups,
                     Consumer<List<MediaItem>> trash) {
        GREEN = Ui.PRIMARY;
        INK = Ui.INK;
        MUTED = Ui.MUTED;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 24), dp(activity, 16), dp(activity, 8));
        root.setBackgroundColor(Ui.BG);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title(activity, "重复照片 / 视频", 20, INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Action closeButton = new Ui.Action(activity, "关闭", 13, Ui.SOFT, INK);
        closeButton.setOnClickListener(v -> Ui.exit(root, dialog::dismiss));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, -2);
        closeParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        heading.addView(closeButton, closeParams);
        root.addView(heading);

        long duplicateFiles = 0, wasted = 0;
        for (Duplicates.Group group : groups) {
            duplicateFiles += Math.max(0, group.items().size() - 1);
            wasted += group.wasted();
        }
        root.addView(title(activity, groups.isEmpty()
                ? "没有发现内容完全相同的文件。"
                : groups.size() + " 组 · 多余 " + duplicateFiles + " 个 · 可释放 " + size(wasted),
                12, MUTED, false));
        root.addView(title(activity, "内容完全相同才列为一组。", 12, MUTED, false));

        final Set<String> selected = new HashSet<>();
        final Map<String, List<CheckBox>> groupChecks = new LinkedHashMap<>();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.VERTICAL);
        int number = 0;
        for (Duplicates.Group group : groups) {
            number++;
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Ui.CARD);
            background.setCornerRadius(dp(activity, 12));
            card.setBackground(background);
            card.setElevation(dp(activity, 1));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(0, 0, 0, dp(activity, 8));

            card.addView(title(activity, "组 " + number + " · " + group.items().size() + " 个",
                    14, INK, true));
            card.addView(title(activity, "每个 " + size(group.size())
                    + " · 可释放 " + size(group.wasted()), 12, MUTED, false));

            List<CheckBox> checks = new ArrayList<>();
            for (int index = 0; index < group.items().size(); index++) {
                MediaItem item = group.items().get(index);
                if (index > 0) {
                    View divider = new View(activity);
                    divider.setBackgroundColor(Ui.LINE);
                    LinearLayout.LayoutParams dividerParams =
                            new LinearLayout.LayoutParams(-1, dp(activity, 1));
                    dividerParams.setMargins(dp(activity, Ui.S1), dp(activity, Ui.S1),
                            dp(activity, Ui.S1), dp(activity, Ui.S1));
                    card.addView(divider, dividerParams);
                }
                CheckBox check = new CheckBox(activity);
                check.setButtonTintList(ColorStateList.valueOf(GREEN));
                check.setText(item.name + "\n" + MediaPaths.display(item.parentId)
                        + "\n" + size(item.size) + " · " + (item.video ? "视频" : "照片")
                        + (item.capturedAt.isEmpty() ? "" : " · " + item.capturedAt.substring(0, Math.min(10, item.capturedAt.length())))
                        + (item.trashable() ? "" : " · 未索引，无法移入回收站"));
                check.setTextSize(12);
                check.setTextColor(INK);
                check.setEnabled(item.trashable());
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selected.add(item.uri); else selected.remove(item.uri);
                });
                LinearLayout fileRow = Ui.row(activity);
                fileRow.addView(check, new LinearLayout.LayoutParams(0, -2, 1));

                Ui.Action preview = new Ui.Action(activity,
                        item.video ? "播放" : "查看", Ui.BODY, Ui.SOFT, INK);
                preview.setContentDescription((item.video ? "播放视频：" : "查看照片：") + item.name);
                preview.setOnClickListener(v -> MediaViewer.open(activity, item));
                LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-2, -2);
                previewParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
                fileRow.addView(preview, previewParams);
                card.addView(fileRow);
                checks.add(check);
            }
            Ui.Action keepFirst = new Ui.Action(activity, "保留第一个，勾选其余", 13, Ui.SOFT, INK);
            keepFirst.setOnClickListener(v -> {
                for (int i = 0; i < checks.size(); i++) {
                    CheckBox check = checks.get(i);
                    boolean choose = i > 0 && check.isEnabled();
                    check.setChecked(choose);
                }
            });
            LinearLayout.LayoutParams keepParams = new LinearLayout.LayoutParams(-2, -2);
            keepParams.setMargins(0, dp(activity, Ui.S2), 0, 0);
            card.addView(keepFirst, keepParams);
            groupChecks.put(group.hash(), checks);
            cards.addView(card, cardParams);
        }
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(activity, 8), 0, 0);
        Ui.Action all = new Ui.Action(activity, "全选重复项", 13, Ui.SOFT, INK);
        all.setOnClickListener(v -> {
            for (Duplicates.Group group : groups) {
                List<CheckBox> checks = groupChecks.get(group.hash());
                if (checks == null) continue;
                for (int i = 0; i < checks.size(); i++) {
                    if (i > 0 && checks.get(i).isEnabled()) checks.get(i).setChecked(true);
                }
            }
        });
        actions.addView(all);
        Ui.Action clear = new Ui.Action(activity, "清空", 13, Ui.SOFT, MUTED);
        clear.setOnClickListener(v -> {
            for (List<CheckBox> checks : groupChecks.values()) {
                for (CheckBox check : checks) check.setChecked(false);
            }
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
        clearParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        actions.addView(clear, clearParams);
        root.addView(actions);

        Button trashButton = new Button(activity);
        trashButton.setText("移入系统回收站（可恢复）");
        trashButton.setTextSize(14);
        trashButton.setAllCaps(false);
        trashButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        trashButton.setMinWidth(0);
        trashButton.setMinHeight(dp(activity, 52));
        trashButton.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        trashButton.setTextColor(Ui.onColor(Ui.DARK));
        trashButton.setBackground(Ui.ripple(Ui.rippleFor(Ui.DARK), Ui.fill(Ui.DARK, dp(activity, 12))));
        trashButton.setStateListAnimator(null);
        trashButton.setStateListAnimator(null);
        trashButton.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                Ui.toast(activity, "请先勾选要移入回收站的副本");
                return;
            }
            List<MediaItem> chosen = new ArrayList<>();
            for (Duplicates.Group group : groups) {
                for (MediaItem item : group.items()) if (selected.contains(item.uri)) chosen.add(item);
            }
            dialog.dismiss();
            trash.accept(chosen);
        });
        LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(-1, -2);
        trashParams.setMargins(0, dp(activity, Ui.S3), 0, 0);
        root.addView(trashButton, trashParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Ui.BG));
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Ui.BG);
            window.setNavigationBarColor(Ui.BG);
            int barFlags = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (!Ui.NIGHT) barFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            window.getDecorView().setSystemUiVisibility(barFlags);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.98));
        }
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setDimAmount(0f);
        }
        Ui.enter(root);
    }

    private static TextView title(Activity activity, String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private static String size(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes >= 1073741824) return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1048576) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}