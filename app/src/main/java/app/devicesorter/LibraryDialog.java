package app.devicesorter;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Full-screen media browser with parameter filters and batch actions. */
final class LibraryDialog {
    interface Host {
        void copyToFolder(List<MediaItem> items);
        void recallToCamera(List<MediaItem> items);
        void trash(List<MediaItem> items);
    }

    private static int GREEN = Ui.INK, INK = Ui.INK, MUTED = Ui.MUTED;
    private static final int PAGE = 40;

    private LibraryDialog() {}

    static void show(Activity activity, List<MediaItem> items, boolean recallMode, Host host) {
        // Re-read the palette per launch so a night-mode change is picked up.
        GREEN = Ui.PRIMARY;
        INK = Ui.INK;
        MUTED = Ui.MUTED;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(activity);
        root.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 2));
        root.setBackgroundColor(Ui.BG);

        LinearLayout heading = row(activity);
        heading.addView(title(activity, recallMode ? "找回相册外的媒体" : "媒体库",
                20, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Action close = action(activity, "关闭", Ui.SOFT, INK);
        close.setOnClickListener(v -> Ui.exit(root, dialog::dismiss));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, -2);
        closeParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        heading.addView(close, closeParams);
        root.addView(heading);
        Set<String> loadedFolders = new HashSet<>();
        for (MediaItem item : items) if (!item.parentId.isEmpty()) loadedFolders.add(item.parentId);
        int folderCount = loadedFolders.size();
        root.addView(title(activity, "已载入 " + items.size() + " 个文件 / " + folderCount + " 个目录",
                12, MUTED, false));

        // Filters are the tallest part of this screen and are only needed when
        // narrowing a large library, so they start folded behind one row.
        LinearLayout filterBar = row(activity);
        filterBar.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
        final Ui.Action filterToggle = action(activity, "筛选", Ui.SOFT, INK);
        filterBar.addView(filterToggle);
        final TextView filterSummary = title(activity,
                "关键词 · 设备 · 日期", 12, MUTED, false);
        filterSummary.setPadding(dp(activity, 10), 0, 0, 0);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(0, -2, 1);
        summaryParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        filterBar.addView(filterSummary, summaryParams);
        root.addView(filterBar);

        final LinearLayout filters = column(activity);
        filters.setVisibility(View.GONE);
        root.addView(filters);

        ScrollView page = new ScrollView(activity);
        LinearLayout content = column(activity);

        LinearLayout keywordRow = row(activity);
        EditText keyword = input(activity, "关键词：文件名 / 路径 / 设备", false);
        keywordRow.addView(keyword, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        LinearLayout.LayoutParams keywordParams = new LinearLayout.LayoutParams(-1, -2);
        keywordParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(keywordRow, keywordParams);

        LinearLayout typeRow = row(activity);
        Spinner type = spinner(activity, new String[] {"全部", "照片", "视频"});
        type.setMinimumHeight(dp(activity, 48));
        typeRow.addView(type, new LinearLayout.LayoutParams(-2, -2));
        Spinner model = spinner(activity, modelChoices(items));
        model.setMinimumHeight(dp(activity, 48));
        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(0, -2, 1);
        modelParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        typeRow.addView(model, modelParams);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(-1, -2);
        typeParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(typeRow, typeParams);

        Spinner folder = spinner(activity, folderChoices(items));
        folder.setMinimumHeight(dp(activity, 48));
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(-1, -2);
        folderParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(folder, folderParams);

        LinearLayout dateRow = row(activity);
        EditText from = input(activity, "起始日期", false);
        from.setInputType(InputType.TYPE_CLASS_DATETIME);
        dateRow.addView(from, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        EditText to = input(activity, "结束日期", false);
        LinearLayout.LayoutParams toParams = new LinearLayout.LayoutParams(0, dp(activity, 48), 1);
        toParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        dateRow.addView(to, toParams);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-1, -2);
        dateParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(dateRow, dateParams);

        LinearLayout sizeRow = row(activity);
        EditText min = input(activity, "最小 MB", false);
        min.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeRow.addView(min, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        EditText max = input(activity, "最大 MB", false);
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams maxParams = new LinearLayout.LayoutParams(0, dp(activity, 48), 1);
        maxParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        sizeRow.addView(max, maxParams);
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(-1, -2);
        sizeParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(sizeRow, sizeParams);

        CheckBox outside = new CheckBox(activity);
        outside.setText("只看不在相机目录的文件");
        outside.setTextColor(INK);
        outside.setTextSize(Ui.LABEL);
        outside.setChecked(recallMode);
        outside.setMinHeight(dp(activity, 48));
        outside.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        LinearLayout.LayoutParams outsideParams = new LinearLayout.LayoutParams(-1, -2);
        outsideParams.setMargins(0, 0, 0, dp(activity, Ui.S2));
        filters.addView(outside, outsideParams);

        LinearLayout applyRow = row(activity);
        Ui.Action apply = action(activity, "应用筛选", Ui.SOFT_DARK, INK);
        applyRow.addView(apply);
        Ui.Action reset = action(activity, "重置", Ui.SOFT_DARK, INK);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-2, -2);
        resetParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        applyRow.addView(reset, resetParams);
        filters.addView(applyRow);

        filterToggle.setOnClickListener(v -> {
            boolean open = filters.getVisibility() != View.VISIBLE;
            filters.setVisibility(open ? View.VISIBLE : View.GONE);
            filterToggle.setText(open ? "收起" : "筛选");
        });

        TextView count = title(activity, "", 13, MUTED, false);
        count.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        content.addView(count);

        LinearLayout list = column(activity);
        content.addView(list);

        final int[] pageIndex = {0};
        final List<MediaItem> filtered = new ArrayList<>();
        final Set<String> selected = new HashSet<>();
        final String[] models = modelValues(items);
        final String[] folders = folderValues(items);

        Runnable render = () -> {
            if (pageIndex[0] * PAGE >= filtered.size()) pageIndex[0] = Math.max(0, (filtered.size() - 1) / PAGE);
            list.removeAllViews();
            int start = pageIndex[0] * PAGE;
            int end = Math.min(start + PAGE, filtered.size());
            count.setText("筛选结果 " + filtered.size() + " 个 · 已选 " + selected.size()
                    + (filtered.isEmpty() ? "" : " · " + (start + 1) + "-" + end));
            if (filtered.isEmpty()) {
                list.addView(title(activity, "没有符合条件的文件。可清除关键词或调整筛选。", 14, MUTED, false));
                return;
            }
            for (int index = start; index < end; index++) {
                MediaItem item = filtered.get(index);
                LinearLayout card = column(activity);
                card.setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7));
                GradientDrawable background = new GradientDrawable();
                background.setColor(Ui.CARD);
                background.setCornerRadius(dp(activity, 12));
                card.setBackground(background);
                card.setElevation(dp(activity, 1));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                cardParams.setMargins(0, 0, 0, dp(activity, Ui.S2));

                CheckBox check = new CheckBox(activity);
                check.setButtonTintList(ColorStateList.valueOf(GREEN));
                check.setText(item.name + "\n" + MediaPaths.display(item.parentId) + "\n"
                        + size(item.size) + " · " + (item.video ? "视频" : "照片")
                        + (item.capturedAt.isEmpty() ? "" : " · " + item.capturedAt.substring(0, Math.min(10, item.capturedAt.length())))
                        + (item.model == null ? "" : " · " + item.model)
                        + (item.trashable() ? "" : " · 无回收站索引"));
                check.setTextSize(Ui.LABEL);
                check.setTextColor(INK);
                check.setMinHeight(dp(activity, 48));
                check.setChecked(selected.contains(item.uri));
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selected.add(item.uri); else selected.remove(item.uri);
                    count.setText("筛选结果 " + filtered.size() + " 个 · 已选 " + selected.size()
                            + " · " + (start + 1) + "-" + end);
                });
                LinearLayout line = row(activity);
                line.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
                Ui.Action preview = action(activity, "预览", Ui.SOFT, INK);
                preview.setOnClickListener(v -> MediaViewer.open(activity, item));
                LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-2, -2);
                previewParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
                line.addView(preview, previewParams);
                card.addView(line);
                list.addView(card, cardParams);
            }
        };

        java.util.function.Consumer<Boolean> applyFilter = resetAll -> {
            try {
                LibraryFilter.Query query = new LibraryFilter.Query();
                query.keyword = keyword.getText().toString();
                String kind = type.getSelectedItemPosition() == 1 ? "photo"
                        : type.getSelectedItemPosition() == 2 ? "video" : "all";
                query.type = kind;
                int modelIndex = model.getSelectedItemPosition();
                query.model = modelIndex > 0 && modelIndex - 1 < models.length ? models[modelIndex - 1] : "";
                int folderIndex = folder.getSelectedItemPosition();
                query.parentId = folderIndex > 0 && folderIndex - 1 < folders.length ? folders[folderIndex - 1] : "";
                query.from = from.getText().toString().trim();
                query.to = to.getText().toString().trim();
                query.minSize = min.getText().toString().trim().isEmpty() ? 0
                        : LibraryFilter.megabytes(min.getText().toString());
                query.maxSize = max.getText().toString().trim().isEmpty() ? Long.MAX_VALUE
                        : LibraryFilter.megabytes(max.getText().toString());
                query.outsideCamera = outside.isChecked();
                query.validate();
                filtered.clear();
                filtered.addAll(LibraryFilter.apply(items, query));
                // A changed filter starts a fresh selection, so hidden files cannot be acted on.
                selected.clear();
                if (resetAll) pageIndex[0] = 0;
                render.run();
                boolean any = !query.keyword.trim().isEmpty() || !"all".equals(query.type)
                        || !query.model.isEmpty() || !query.parentId.isEmpty()
                        || !query.from.isEmpty() || !query.to.isEmpty()
                        || query.minSize > 0 || query.maxSize < Long.MAX_VALUE
                        || query.outsideCamera;
                filterSummary.setText(any
                        ? "已启用条件 · " + filtered.size() + " 个结果"
                        : "关键词 · 设备 · 日期");
                filters.setVisibility(View.GONE);
                filterToggle.setText("筛选");
            } catch (IllegalArgumentException e) {
                Ui.toastLong(activity, e.getMessage());
            }
        };

        apply.setOnClickListener(v -> {
            pageIndex[0] = 0;
            applyFilter.accept(false);
        });
        reset.setOnClickListener(v -> {
            keyword.setText("");
            from.setText("");
            to.setText("");
            min.setText("");
            max.setText("");
            type.setSelection(0);
            model.setSelection(0);
            folder.setSelection(0);
            outside.setChecked(recallMode);
            pageIndex[0] = 0;
            applyFilter.accept(true);
        });
        LinearLayout pager = row(activity);
        pager.setGravity(Gravity.END);
        Ui.Action previous = action(activity, "上一页", Ui.SOFT_DARK, INK);
        previous.setOnClickListener(v -> { if (pageIndex[0] > 0) { pageIndex[0]--; render.run(); } });
        pager.addView(previous);
        Ui.Action next = action(activity, "下一页", Ui.SOFT_DARK, INK);
        next.setOnClickListener(v -> {
            if ((pageIndex[0] + 1) * PAGE < filtered.size()) { pageIndex[0]++; render.run(); }
        });
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(-2, -2);
        nextParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        pager.addView(next, nextParams);
        content.addView(pager);

        LinearLayout selectRow = row(activity);
        Ui.Action selectAll = action(activity, "全选筛选结果", Ui.SOFT_DARK, INK);
        selectAll.setOnClickListener(v -> {
            for (MediaItem item : filtered) selected.add(item.uri);
            render.run();
        });
        selectRow.addView(selectAll);
        Ui.Action clear = action(activity, "清空选择", Ui.SOFT, MUTED);
        clear.setOnClickListener(v -> {
            selected.clear();
            render.run();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
        clearParams.setMargins(dp(activity, Ui.S2), 0, 0, 0);
        selectRow.addView(clear, clearParams);

        LinearLayout actions = column(activity);
        Ui.Action copy = action(activity, "复制到其他文件夹",
                recallMode ? Ui.SOFT_DARK : Ui.DARK,
                recallMode ? INK : Ui.onColor(Ui.DARK));
        copy.setOnClickListener(v -> run(activity, dialog, selected, items, host::copyToFolder));
        actions.addView(copy, new LinearLayout.LayoutParams(-2, -2));
        Ui.Action recall = action(activity, "复制回相机目录",
                recallMode ? Ui.DARK : Ui.SOFT_DARK,
                recallMode ? Ui.onColor(Ui.DARK) : INK);
        recall.setOnClickListener(v -> run(activity, dialog, selected, items, host::recallToCamera));
        LinearLayout.LayoutParams recallParams = new LinearLayout.LayoutParams(-2, -2);
        recallParams.setMargins(0, dp(activity, Ui.S2), 0, 0);
        actions.addView(recall, recallParams);
        Ui.Action trash = action(activity, "移入系统回收站（可恢复）", Ui.SOFT_DARK, INK);
        trash.setOnClickListener(v -> run(activity, dialog, selected, items, host::trash));
        LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(-2, -2);
        trashParams.setMargins(0, dp(activity, Ui.S2), 0, 0);
        actions.addView(trash, trashParams);

        page.addView(content);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        // Keep selection and batch actions reachable even when the file list is long.
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(-1, -2);
        selectParams.setMargins(0, dp(activity, Ui.S2), 0, 0);
        root.addView(selectRow, selectParams);
        LinearLayout.LayoutParams actionColumnParams = new LinearLayout.LayoutParams(-1, -2);
        actionColumnParams.setMargins(0, dp(activity, Ui.S2), 0, 0);
        root.addView(actions, actionColumnParams);

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
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Initial filter is applied after the view tree exists, so the first paint is useful.
        applyFilter.accept(true);
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setDimAmount(0f);
        }
        Ui.enter(root);
    }

    private static void run(Activity activity, Dialog dialog, Set<String> selected,
                            List<MediaItem> all, java.util.function.Consumer<List<MediaItem>> action) {
        List<MediaItem> chosen = new ArrayList<>();
        for (MediaItem item : all) if (selected.contains(item.uri)) chosen.add(item);
        if (chosen.isEmpty()) {
            Ui.toast(activity, "请先勾选文件");
            return;
        }
        dialog.dismiss();
        action.accept(chosen);
    }

    private static String[] modelChoices(List<MediaItem> items) {
        List<String> values = LibraryFilter.models(items);
        String[] result = new String[values.size() + 1];
        result[0] = "全部设备";
        for (int i = 0; i < values.size(); i++) result[i + 1] = values.get(i);
        return result;
    }

    private static String[] modelValues(List<MediaItem> items) {
        return LibraryFilter.models(items).toArray(new String[0]);
    }

    private static String[] folderChoices(List<MediaItem> items) {
        List<String> values = LibraryFilter.folders(items);
        String[] result = new String[values.size() + 1];
        result[0] = "全部文件夹";
        for (int i = 0; i < values.size(); i++) result[i + 1] = MediaPaths.display(values.get(i));
        return result;
    }

    private static String[] folderValues(List<MediaItem> items) {
        return LibraryFilter.folders(items).toArray(new String[0]);
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private static LinearLayout row(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static TextView title(Activity activity, String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private static EditText input(Activity activity, String hint, boolean multiline) {
        EditText view = new EditText(activity);
        view.setHint(hint);
        view.setHintTextColor(Ui.MUTED);
        view.setTextSize(Ui.LABEL);
        view.setSingleLine(!multiline);
        view.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        return view;
    }

    private static Spinner spinner(Activity activity, String[] values) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(Ui.LABEL);
                view.setSingleLine(false);
                view.setMaxLines(Integer.MAX_VALUE);
                view.setEllipsize(null);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextSize(Ui.BODY);
                view.setSingleLine(false);
                view.setMaxLines(Integer.MAX_VALUE);
                view.setEllipsize(null);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private static Ui.Action action(Activity activity, String value, int color, int textColor) {
        return new Ui.Action(activity, value, 13, color, textColor);
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