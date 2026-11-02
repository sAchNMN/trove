package app.devicesorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Operation journal: the latest run, the archive picker and the export hand-off.
 * Which entry is open and what gets exported used to be Activity state; keeping it
 * here means the Activity only has to route the document-picker result back.
 */
final class LogDialog {
    /** Request code the Activity sends to {@link #export(Uri)}. */
    static final int EXPORT_REQUEST = 12;

    private static final int MAX_ENTRIES = 100;

    private final Activity activity;
    private File exporting;

    LogDialog(Activity activity) {
        this.activity = activity;
    }

    void restore(Bundle state) {
        if (state == null) return;
        String path = state.getString("exporting");
        if (path != null) exporting = new File(path);
    }

    void save(Bundle state) {
        if (exporting != null) state.putString("exporting", exporting.getAbsolutePath());
    }

    void openLatest() {
        File[] files = journalFiles();
        if (files == null) return;
        exporting = files[0];
        show(exporting);
    }

    /** Older journals stay one tap away without hiding the latest details behind a date list. */
    void openPicker() {
        File[] files = journalFiles();
        if (files == null) return;
        String[] labels = new String[files.length];
        DateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < files.length; i++) {
            labels[i] = format.format(new Date(files[i].lastModified()));
        }
        new AlertDialog.Builder(activity).setTitle("历史操作记录").setItems(labels, (dialog, which) -> {
            exporting = files[which];
            show(exporting);
        }).setNegativeButton("关闭", null).show();
    }

    private void show(final File file) {
        List<JSONObject> entries = new ArrayList<>();
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                entries.add(new JSONObject(line));
                if (entries.size() > MAX_ENTRIES) entries.remove(0);
                count++;
            }
        } catch (Exception e) {
            entries.add(new JSONObject());
        }
        LinearLayout body = Ui.column(activity);
        body.setPadding(dp(16), dp(24), dp(16), dp(8));
        body.setBackgroundColor(Ui.BG);
        body.addView(text("操作详情", 18, Ui.INK, true));
        body.addView(text(count > MAX_ENTRIES
                ? "显示最近 100 条，共 " + count + " 条；完整记录可导出"
                : "共 " + count + " 条事件", 12, Ui.MUTED, false));
        Ui.space(body, 8);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout cards = Ui.column(activity);
        for (JSONObject entry : entries) {
            LinearLayout card = Ui.column(activity);
            card.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Ui.CARD);
            background.setCornerRadius(dp(12));
            card.setBackground(background);
            String status = entry.optString("status");
            JSONObject source = entry.optJSONObject("source");
            String name = source == null ? "" : source.optString("name");
            String sourcePath = source == null ? "" : readable(source.optString("path"));
            String target = readable(entry.optString("target"));
            card.addView(text(statusLabel(status) + (name.isEmpty() ? "" : " · " + name), 14, Ui.INK, true));
            if (!sourcePath.isEmpty()) card.addView(text("原始： " + sourcePath, 12, Ui.MUTED, false));
            if (!target.isEmpty()) card.addView(text("目标： " + target, 12, Ui.PRIMARY, false));
            String detail = entry.optString("detail");
            if (!detail.isEmpty()) card.addView(text(detail, 12, Ui.MUTED, false));
            String time = entry.optString("time");
            if (time.length() > 19) time = time.substring(0, 19).replace('T', ' ');
            if (!time.isEmpty()) card.addView(text(time, 12, Ui.MUTED, false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(6));
            cards.addView(card, params);
        }
        scroll.addView(cards);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(body)
                .setNegativeButton("关闭", null)
                .setNeutralButton("历史记录", (d, which) -> openPicker())
                .setPositiveButton("导出完整记录", (d, which) -> activity.startActivityForResult(
                        new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                                .setType("application/octet-stream")
                                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                .putExtra(android.content.Intent.EXTRA_TITLE, file.getName()),
                        EXPORT_REQUEST))
                .create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.9));
        }
    }

    /** Writes the open journal to the document the user picked. */
    void export(Uri uri) {
        final File file = exporting;
        if (file == null) {
            error("记录未选择，请重新导出");
            return;
        }
        new Thread(() -> {
            try (InputStream in = Files.newInputStream(file.toPath());
                 OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("无法写入导出文件");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                activity.runOnUiThread(() -> Ui.toast(activity, "记录已导出"));
            } catch (Exception e) {
                activity.runOnUiThread(() -> error(e.getMessage()));
            }
        }, "export-log").start();
    }

    private File[] journalFiles() {
        File[] files = new File(activity.getFilesDir(), "logs")
                .listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            error("暂无操作记录");
            return null;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private String readable(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            if (value.startsWith("content://" + Storage.LOCAL + "/tree/")) {
                return MediaPaths.display(DocumentsContract.getDocumentId(Uri.parse(value)));
            }
            if (value.startsWith("content://" + Storage.LOCAL + "/document/")) {
                return MediaPaths.display(DocumentsContract.getDocumentId(Uri.parse(value)));
            }
            if (value.matches("^[A-Za-z0-9_-]+:.*")) return MediaPaths.display(value);
        } catch (RuntimeException ignored) { }
        return value;
    }

    private String statusLabel(String status) {
        switch (status == null ? "" : status) {
            case "START": return "开始";
            case "FINISHED": return "结束";
            case "CANCELLED": return "已取消";
            case "CREATED": return "已建立临时文件";
            case "RENAME_PENDING": return "准备恢复文件名";
            case "VERIFIED_COPY": return "已校验复制";
            case "COPIED": return "已复制";
            case "MOVED": return "已移动";
            case "FAILED": return "失败";
            case "FAILED_FINAL_VERIFY": return "最终校验失败";
            case "MOVE_UNCERTAIN": return "移动结果不确定";
            case "NATIVE_MOVE_PENDING": return "准备系统移动";
            case "NATIVE_MOVE_RETURNED": return "系统移动已返回";
            case "NATIVE_MOVED": return "系统移动完成";
            case "CAMERA_EXISTS": return "DCIM/Camera 已有相同文件";
            case "TRASHED": return "已移入回收站";
            case "TRASH_CANCELLED": return "取消移入回收站";
            case "TRASH_FAILED": return "回收站操作失败";
            case "TRASH_BATCH": return "回收站批次";
            default: return status == null || status.isEmpty() ? "事件" : status;
        }
    }

    private TextView text(CharSequence value, float sp, int color, boolean bold) {
        return Ui.label(activity, value, sp, color, bold);
    }

    private void error(String message) {
        new AlertDialog.Builder(activity).setTitle("提示").setMessage(message)
                .setPositiveButton("确定", null).show();
    }

    private int dp(int value) {
        return Ui.dp(activity, value);
    }
}
