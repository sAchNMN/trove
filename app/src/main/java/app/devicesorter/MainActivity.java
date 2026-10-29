package app.devicesorter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MainActivity extends Activity {
    private static final int SOURCE = 10, TARGET = 11, EXPORT = 12;
    private static final int GREEN = 0xff137c66, INK = 0xff182c2b, MUTED = 0xff60716f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> sources = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final List<View> controls = new ArrayList<>();
    private String target = "";
    private boolean move;
    private SharedPreferences prefs;
    private LinearLayout sourceList, groupList;
    private TextView targetLabel, summary, status, selection;
    private Button scan, trial, execute;
    private RadioButton copyMode;
    private ProgressBar progress;
    private ImageButton cancel;
    private int revision = -1;
    private File exporting;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(Session.status);
            boolean busy = Session.running;
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
            for (View view : controls) view.setEnabled(!busy);
            scan.setEnabled(!busy && !sources.isEmpty());
            boolean ready = !busy && !target.isEmpty() && !selected.isEmpty() && !Session.items.isEmpty();
            trial.setEnabled(ready);
            execute.setEnabled(ready);
            scan.setAlpha(scan.isEnabled() ? 1f : .4f);
            trial.setAlpha(ready ? 1f : .4f);
            execute.setAlpha(ready ? 1f : .4f);
            if (revision != Session.revision) {
                revision = Session.revision;
                renderGroups();
            }
            handler.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", 0);
        try {
            JSONArray saved = new JSONArray(prefs.getString("sources", "[]"));
            for (int i = 0; i < saved.length(); i++) sources.add(saved.getString(i));
        } catch (Exception ignored) { sources.clear(); }
        target = prefs.getString("target", "");
        Session.load(this);
        build();
        renderSources();
        renderGroups();
        if (!Session.running && prefs.getBoolean("jobStarted", false)) {
            Session.status = "上次任务可能被中断，请检查操作记录和目标目录；不会自动继续删除文件";
            prefs.edit().putBoolean("jobStarted", false).apply();
        }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff5f7f7);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header = row();
        LinearLayout titles = column();
        titles.addView(text("设备归档", 26, INK, true));
        titles.addView(text("本地照片与视频", 13, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton history = icon(android.R.drawable.ic_menu_recent_history, "操作记录", v -> showLogs());
        controls.add(history);
        header.addView(history);
        page.addView(header);
        space(page, 16);

        summary = text("照片 0     视频 0     未识别 0", 15, INK, true);
        summary.setPadding(0, dp(12), 0, dp(12));
        page.addView(summary);
        line(page);
        section(page, "01", "整理目录");
        LinearLayout sourceHeader = row();
        sourceHeader.addView(text("来源目录", 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton add = icon(android.R.drawable.ic_input_add, "添加来源目录", v -> pick(SOURCE));
        controls.add(add);
        sourceHeader.addView(add);
        page.addView(sourceHeader);
        sourceList = column();
        page.addView(sourceList);
        space(page, 8);
        LinearLayout targetRow = row();
        LinearLayout targetText = column();
        targetText.addView(text("目标目录", 14, MUTED, false));
        targetLabel = text(target.isEmpty() ? "未选择" : label(target), 15, INK, true);
        targetText.addView(targetLabel);
        targetRow.addView(targetText, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton choose = icon(android.R.drawable.ic_menu_save, "选择目标目录", v -> pick(TARGET));
        controls.add(choose);
        targetRow.addView(choose);
        page.addView(targetRow);
        scan = button("扫描设备", GREEN, v -> startScan());
        page.addView(scan);
        space(page, 12);
        line(page);
        section(page, "02", "设备分类");
        groupList = column();
        page.addView(groupList);
        selection = text("", 13, MUTED, false);
        selection.setPadding(0, dp(10), 0, dp(12));
        page.addView(selection);
        line(page);
        section(page, "03", "执行方式");
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton copy = new RadioButton(this);
        copyMode = copy;
        copy.setSaveEnabled(false);
        copy.setText("复制，保留原件");
        copy.setId(View.generateViewId());
        RadioButton moving = new RadioButton(this);
        moving.setSaveEnabled(false);
        moving.setText("移动");
        moving.setId(View.generateViewId());
        modes.addView(copy);
        modes.addView(moving);
        copy.setChecked(true);
        modes.setOnCheckedChangeListener((group, checked) -> {
            move = checked == moving.getId();
            execute.setText(move ? "批量移动" : "批量复制");
        });
        controls.add(copy);
        controls.add(moving);
        page.addView(modes);
        LinearLayout actions = row();
        trial = button("小批量试跑", 0xffe3ecea, v -> confirm(false, 3));
        trial.setTextColor(GREEN);
        execute = button("批量复制", GREEN, v -> confirm(move, 0));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(52), 1);
        half.setMargins(0, dp(10), dp(8), dp(8));
        actions.addView(trial, half);
        actions.addView(execute, new LinearLayout.LayoutParams(0, dp(52), 1));
        page.addView(actions);
        space(page, 12);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress);
        LinearLayout statusRow = row();
        status = text(Session.status, 13, MUTED, false);
        status.setPadding(0, dp(8), 0, dp(8));
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        cancel = icon(android.R.drawable.ic_menu_close_clear_cancel, "取消任务", v -> {
            Session.cancel = true;
            status.setText("正在取消，请等待当前读写结束");
        });
        cancel.setVisibility(View.GONE);
        statusRow.addView(cancel);
        page.addView(statusRow);
    }

    private void renderSources() {
        sourceList.removeAllViews();
        if (sources.isEmpty()) sourceList.addView(text("未选择", 15, MUTED, false));
        for (String source : sources) {
            LinearLayout row = row();
            row.addView(text(label(source), 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton remove = icon(android.R.drawable.ic_menu_close_clear_cancel, "移除来源目录", v -> {
                if (Session.running) return;
                sources.remove(source);
                persist();
                invalidateScan();
                renderSources();
            });
            row.addView(remove);
            sourceList.addView(row);
        }
    }

    private void renderGroups() {
        groupList.removeAllViews();
        Map<String, List<MediaItem>> groups = new TreeMap<>();
        long photos = 0, videos = 0, unknown = 0;
        for (MediaItem item : Session.items) {
            if (item.video) videos++; else photos++;
            if (item.model == null) { unknown++; continue; }
            groups.computeIfAbsent(item.model, key -> new ArrayList<>()).add(item);
        }
        selected.retainAll(groups.keySet());
        if (selected.isEmpty()) selected.addAll(groups.keySet());
        summary.setText("照片 " + photos + "     视频 " + videos + "     未识别 " + unknown);
        if (Session.items.isEmpty()) {
            groupList.addView(text("暂无扫描结果", 14, MUTED, false));
        }
        for (Map.Entry<String, List<MediaItem>> entry : groups.entrySet()) {
            String model = entry.getKey();
            List<MediaItem> items = entry.getValue();
            LinearLayout row = row();
            CheckBox check = new CheckBox(this);
            check.setChecked(selected.contains(model));
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
            long v = items.stream().filter(item -> item.video).count();
            check.setText(model + "\n" + (items.size() - v) + " 张照片 · " + v + " 段视频");
            check.setTextSize(14);
            check.setTextColor(INK);
            check.setPadding(0, dp(6), 0, dp(6));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(model); else selected.remove(model);
                updateSelection();
            });
            check.setEnabled(!Session.running);
            row.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton info = icon(android.R.drawable.ic_menu_info_details, "查看文件", view -> {
                if (!Session.running) showFiles(model, items);
            });
            row.addView(info);
            groupList.addView(row);
        }
        if (unknown > 0) {
            Button unmatched = button("未识别 " + unknown + " 个 · 保留原处", 0xffeef0f1, v -> {
                List<MediaItem> items = new ArrayList<>();
                for (MediaItem item : Session.items) if (item.model == null) items.add(item);
                showFiles("未识别文件", items);
            });
            unmatched.setTextColor(MUTED);
            groupList.addView(unmatched);
        }
        updateSelection();
    }

    private void updateSelection() {
        if (selection == null) return;
        long bytes = 0;
        int count = 0;
        for (MediaItem item : Session.items) {
            if (item.model != null && selected.contains(item.model)) { count++; bytes += Math.max(0, item.size); }
        }
        selection.setText("已选 " + selected.size() + " 台设备 · " + count + " 个文件 · " + size(bytes));
    }

    private void showFiles(String title, List<MediaItem> items) {
        List<String> labels = new ArrayList<>();
        for (MediaItem item : items.subList(0, Math.min(200, items.size()))) {
            labels.add(item.path + "\n" + size(Math.max(0, item.size))
                    + (item.model == null ? " · " + item.issue : "\n→ " + Naming.folder(item.model) + "/" + item.name));
        }
        new AlertDialog.Builder(this).setTitle(title + " · " + items.size())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {})
                .setPositiveButton("关闭", null).show();
    }

    private void pick(int request) {
        if (Session.running) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT) { export(uri); return; }
        try {
            Storage.id(uri);
            if (Session.running) throw new java.io.IOException("请等待当前任务完成");
            if (request == TARGET) {
                for (String source : sources) ensureSeparate(Uri.parse(source), uri);
            } else {
                if (!target.isEmpty()) ensureSeparate(uri, Uri.parse(target));
                for (String source : sources) {
                    if (Naming.overlaps(Storage.id(uri), Storage.id(Uri.parse(source)))) {
                        throw new java.io.IOException("该目录与已有来源重复或互相包含");
                    }
                }
            }
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new java.io.IOException("未获得读取授权");
            if (request == TARGET && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                throw new java.io.IOException("目标目录不可写");
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (request == SOURCE) { sources.add(uri.toString()); invalidateScan(); renderSources(); }
            else { target = uri.toString(); targetLabel.setText(label(target)); }
            copyMode.setChecked(true);
            persist();
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void ensureSeparate(Uri source, Uri destination) throws java.io.IOException {
        if (Naming.overlaps(Storage.id(source), Storage.id(destination))) {
            throw new java.io.IOException("来源和目标不能相同或互相包含，请选择独立目录");
        }
    }

    private void startScan() {
        if (Session.running) return;
        copyMode.setChecked(true);
        selected.clear();
        launch(new Intent(this, SortService.class).setAction("scan").putStringArrayListExtra("sources", sources));
    }

    private void confirm(boolean moving, int limit) {
        if (Session.running || selected.isEmpty() || target.isEmpty()) return;
        String message = "目标：" + label(target) + "\n设备：" + selected.size()
                + "\n\n" + (limit > 0 ? "每台设备最多复制 3 张照片和 3 段视频，原件保留。"
                : moving ? "逐文件复制并校验后删除原件。请先完成试跑，并备份不可替代的文件。"
                : "复制所选文件，原件保留。请确保目标有足够空间。")
                + "\n\n重名自动加序号；未识别文件不处理。";
        new AlertDialog.Builder(this).setTitle(limit > 0 ? "小批量试跑" : moving ? "确认移动原文件" : "确认批量复制")
                .setMessage(message).setNegativeButton("取消", null).setPositiveButton("开始", (dialog, which) -> {
                    Intent intent = new Intent(this, SortService.class).setAction("execute")
                            .putExtra("target", target).putExtra("move", moving).putExtra("limit", limit)
                            .putStringArrayListExtra("models", new ArrayList<>(selected))
                            .putStringArrayListExtra("sources", new ArrayList<>(sources));
                    launch(intent);
                }).show();
    }

    private void launch(Intent intent) {
        if (Session.running) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        try {
            prefs.edit().putBoolean("jobStarted", true).apply();
            startForegroundService(intent);
        } catch (RuntimeException e) { error("任务无法启动：" + e.getMessage()); }
    }

    private void persist() {
        prefs.edit().putString("sources", new JSONArray(sources).toString()).putString("target", target).apply();
    }

    private void invalidateScan() {
        try { Session.saveScan(this, java.util.Collections.emptyList()); Session.status = "目录已更新，请重新扫描"; }
        catch (Exception e) { error("无法清除旧预览：" + e.getMessage()); }
        selected.clear();
    }

    private void showLogs() {
        File[] files = new File(getFilesDir(), "logs").listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null || files.length == 0) { error("暂无操作记录"); return; }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[files.length];
        java.text.DateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < files.length; i++) labels[i] = format.format(new java.util.Date(files[i].lastModified()));
        new AlertDialog.Builder(this).setTitle("操作记录").setItems(labels, (dialog, which) -> {
            exporting = files[which];
            showLog(exporting);
        }).setNegativeButton("关闭", null).show();
    }

    private void showLog(File file) {
        StringBuilder details = new StringBuilder();
        try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
            while ((line = reader.readLine()) != null) {
                org.json.JSONObject entry = new org.json.JSONObject(line);
                String event = entry.optString("status");
                org.json.JSONObject source = entry.optJSONObject("source");
                last.add(event + " · " + (source == null ? "" : source.optString("path"))
                        + "\n" + entry.optString("target") + "\n" + entry.optString("detail") + "\n\n");
                if (last.size() > 100) last.removeFirst();
                count++;
            }
            if (count > 100) details.append("显示最近 100 条事件，共 ").append(count).append(" 条。完整记录可导出。\n\n");
            for (String entry : last) details.append(entry);
        } catch (Exception e) { details.append("日志可能因中断不完整：").append(e.getMessage()); }
        TextView text = text(details.toString(), 13, INK, false);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("操作详情").setView(scroll).setNegativeButton("关闭", null)
                .setPositiveButton("导出完整记录", (dialog, which) -> startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, file.getName()), EXPORT)).show();
    }

    private void export(Uri uri) {
        File file = exporting;
        if (file == null) { error("记录未选择，请重新导出"); return; }
        new Thread(() -> {
            try (InputStream in = Files.newInputStream(file.toPath()); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("无法写入导出文件");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                runOnUiThread(() -> Toast.makeText(this, "记录已导出", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> error(e.getMessage())); }
        }, "export-log").start();
    }

    private String label(String uri) {
        try { return DocumentsContract.getTreeDocumentId(Uri.parse(uri)).replace("primary:", "内部存储 / "); }
        catch (Exception e) { return uri; }
    }
    private static String size(long bytes) {
        if (bytes >= 1073741824) return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1048576) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLetterSpacing(0);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    private Button button(String value, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value); button.setTextSize(14); button.setAllCaps(false); button.setLetterSpacing(0);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(6));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(0, dp(8), 0, dp(8));
        button.setLayoutParams(params);
        button.setOnClickListener(listener); return button;
    }
    private ImageButton icon(int drawable, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable); button.setColorFilter(GREEN);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description); button.setTooltipText(description);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(listener); return button;
    }
    private void section(LinearLayout parent, String number, String title) {
        TextView label = text(number + "   " + title, 16, INK, true);
        label.setPadding(0, dp(18), 0, dp(12)); parent.addView(label);
    }
    private void line(LinearLayout parent) {
        View line = new View(this); line.setBackgroundColor(0xffdce3e1);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void error(String message) { new AlertDialog.Builder(this).setTitle("提示").setMessage(message).setPositiveButton("确定", null).show(); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }
}
