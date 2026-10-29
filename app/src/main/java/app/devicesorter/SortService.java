package app.devicesorter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SortService extends Service {
    static final String CHANNEL = "operations";
    private Thread worker;
    private long lastNotification;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "整理进度", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("cancel".equals(intent.getAction())) {
            Session.cancel = true;
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (Session.running) return START_NOT_STICKY;
        Session.cancel = false;
        Session.running = true;
        startForeground(1, notification("正在准备"));
        worker = new Thread(() -> {
            try {
                if ("scan".equals(intent.getAction())) scan(intent);
                else execute(intent);
            } catch (Exception e) {
                Session.status = (Session.cancel ? "已取消" : "未完成") + "：" + e.getMessage();
            } finally {
                Session.running = false;
                getSharedPreferences("settings", 0).edit().putBoolean("jobStarted", false).apply();
                Session.revision++;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "device-sorter");
        worker.start();
        return START_NOT_STICKY;
    }

    private void scan(Intent intent) throws Exception {
        Session.saveScan(this, java.util.Collections.emptyList());
        ArrayList<String> sources = intent.getStringArrayListExtra("sources");
        if (sources == null || sources.isEmpty()) throw new IOException("请先选择来源目录");
        List<MediaItem> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int[] nonMedia = {0};
        for (String source : sources) {
            Uri tree = Uri.parse(source);
            Storage.id(tree);
            walk(Storage.root(tree), Storage.stat(getContentResolver(), Storage.root(tree)).name(),
                    found, visited, nonMedia);
        }
        cancelled();
        Session.saveScan(this, found);
        long recognized = found.stream().filter(item -> item.model != null).count();
        Session.status = "扫描完成 · " + found.size() + " 个媒体文件 · "
                + recognized + " 个已识别 · " + nonMedia[0] + " 个非媒体文件未处理";
    }

    private void walk(Uri directory, String path, List<MediaItem> found, Set<String> visited,
                      int[] nonMedia) throws Exception {
        cancelled();
        if (!visited.add(directory.getAuthority() + ":" + DocumentsContract.getDocumentId(directory))) return;
        for (Storage.Entry entry : Storage.children(getContentResolver(), directory)) {
            cancelled();
            if (entry.directory()) {
                walk(entry.uri(), path + "/" + entry.name(), found, visited, nonMedia);
                continue;
            }
            String identity = entry.uri().getAuthority() + ":" + DocumentsContract.getDocumentId(entry.uri());
            if (!visited.add(identity)) continue;
            String name = entry.name().toLowerCase(Locale.ROOT);
            String mime = entry.mime() == null ? "" : entry.mime();
            boolean video = mime.startsWith("video/")
                    || name.matches(".*\\.(mp4|mov|m4v|3gp|3g2|mkv|webm|avi)$");
            boolean photo = mime.startsWith("image/")
                    || name.matches(".*\\.(jpe?g|heic|heif|avif|png|webp|gif|bmp|tiff?|dng|cr2|cr3|nef|arw|raf|rw2|orf|pef|srw|3fr)$");
            if (!video && !photo) { nonMedia[0]++; continue; }
            progress("扫描 " + (found.size() + 1) + " · " + entry.name());
            String model = null;
            String issue = "";
            try {
                model = Metadata.read(getContentResolver(), entry.uri(), video);
                if (model == null) issue = "未找到可识别的设备型号";
            } catch (Exception e) {
                issue = "元数据读取失败：" + e.getClass().getSimpleName();
            }
            found.add(new MediaItem(entry.uri().toString(), entry.name(), path + "/" + entry.name(),
                    mime.isEmpty() ? (video ? "video/mp4" : "image/jpeg") : mime,
                    entry.size(), entry.modified(), video, model, issue));
        }
    }

    private void execute(Intent intent) throws Exception {
        ArrayList<String> models = intent.getStringArrayListExtra("models");
        ArrayList<String> sources = intent.getStringArrayListExtra("sources");
        String target = intent.getStringExtra("target");
        if (models == null || sources == null || target == null) throw new IOException("任务参数不完整");
        Uri tree = Uri.parse(target);
        String targetId = Storage.id(tree);
        for (String source : sources) {
            if (Naming.overlaps(Storage.id(Uri.parse(source)), targetId)) {
                throw new IOException("目标目录不能与来源目录相同、包含或被包含");
            }
        }
        boolean move = intent.getBooleanExtra("move", false);
        int perModel = intent.getIntExtra("limit", 0);
        Set<String> selected = new HashSet<>(models);
        Map<String, Integer> counts = new HashMap<>();
        List<MediaItem> plan = new ArrayList<>();
        for (MediaItem item : Session.items) {
            if (item.model == null || !selected.contains(item.model)) continue;
            String countKey = item.model + "|" + item.video;
            int count = counts.getOrDefault(countKey, 0);
            if (perModel > 0 && count >= perModel) continue;
            plan.add(item);
            counts.put(countKey, count + 1);
        }
        if (plan.isEmpty()) throw new IOException("没有选中的文件，请先扫描");
        Session.saveScan(this, java.util.Collections.emptyList());
        int completed = 0, failed = 0;
        Map<String, Uri> folders = new HashMap<>();
        try (Session.Journal journal = new Session.Journal(this)) {
            journal.write(Session.event("START", null, target, "", move ? "MOVE" : "COPY"));
            int index = 0;
            for (MediaItem item : plan) {
                if (Session.cancel) break;
                progress(++index + "/" + plan.size() + " · " + item.name);
                Uri folder = null;
                try {
                    folder = folders.get(item.model);
                    if (folder == null) {
                        folder = Storage.folder(getContentResolver(), Storage.root(tree), Naming.folder(item.model));
                        folders.put(item.model, folder);
                    }
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, "", "", e.toString()));
                    continue;
                }
                AndroidFiles files = new AndroidFiles(item, folder, journal);
                try {
                    Transfer.Outcome outcome = Transfer.run(files, move, item.size, item.modified);
                    journal.write(Session.event(outcome.status(), item, files.targetText(), outcome.hash(), ""));
                    completed++;
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, files.targetText(), "", e.toString()));
                }
            }
            journal.write(Session.event(Session.cancel ? "CANCELLED" : "FINISHED", null, target, "",
                    "completed=" + completed + ";failed=" + failed + ";planned=" + plan.size()));
        } finally {
            // An old preview must never silently be re-used after a partly completed operation.
            Session.saveScan(this, java.util.Collections.emptyList());
        }
        Session.status = (Session.cancel ? "已取消" : "整理结束") + " · 成功 " + completed
                + " · 未完成 " + failed + " · 未开始 " + (plan.size() - completed - failed)
                + "；详细结果见操作记录。再次整理请重新扫描。";
    }

    private final class AndroidFiles implements Transfer.Files {
        private final MediaItem item;
        private final Uri folder;
        private final Session.Journal journal;
        private Uri target;

        AndroidFiles(MediaItem item, Uri folder, Session.Journal journal) {
            this.item = item; this.folder = folder; this.journal = journal;
        }
        String targetText() { return target == null ? "" : target.toString(); }
        @Override public InputStream readSource() throws IOException { return read(Uri.parse(item.uri)); }
        @Override public InputStream readTarget() throws IOException { return read(target); }
        private InputStream read(Uri uri) throws IOException {
            InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) throw new IOException("无法打开文件");
            return stream;
        }
        @Override public OutputStream createTarget() throws IOException {
            String name = ".devicesorter-" + java.util.UUID.randomUUID() + ".partial";
            target = DocumentsContract.createDocument(getContentResolver(), folder, "application/octet-stream", name);
            if (target == null) throw new IOException("无法建立目标文件");
            journal.write(Session.event("CREATED", item, targetText(), "", name));
            OutputStream stream = getContentResolver().openOutputStream(target, "wt");
            if (stream == null) throw new IOException("无法写入目标文件");
            return stream;
        }
        @Override public long sourceSize() throws IOException {
            return Storage.stat(getContentResolver(), Uri.parse(item.uri)).size();
        }
        @Override public long sourceModified() throws IOException {
            return Storage.stat(getContentResolver(), Uri.parse(item.uri)).modified();
        }
        @Override public void deleteSource() throws IOException {
            if (!DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(item.uri))) {
                throw new IOException("目标已校验，但原文件未能删除；两份均保留");
            }
        }
        @Override public void deletePartialTarget() throws IOException {
            if (target != null && !DocumentsContract.deleteDocument(getContentResolver(), target)) {
                throw new IOException("部分目标文件未清除，请检查操作记录");
            }
        }
        @Override public void record(String status, String hash) throws IOException {
            if ("VERIFIED_COPY".equals(status)) {
                int suffix = 0;
                String name = item.name;
                while (Storage.find(getContentResolver(), folder, name) != null) {
                    name = Naming.collisionName(item.name, ++suffix);
                }
                journal.write(Session.event("RENAME_PENDING", item, targetText(), hash, name));
                Uri renamed = DocumentsContract.renameDocument(getContentResolver(), target, name);
                if (renamed == null) throw new IOException("已校验但无法恢复目标文件名，原件保留");
                target = renamed;
            }
            journal.write(Session.event(status, item, targetText(), hash, ""));
        }
        @Override public boolean cancelled() { return Session.cancel; }
    }

    private void cancelled() throws IOException {
        if (Session.cancel) throw new IOException("扫描已取消，没有移动文件");
    }

    private void progress(String text) {
        Session.status = text;
        if (System.currentTimeMillis() - lastNotification > 1000) {
            lastNotification = System.currentTimeMillis();
            getSystemService(NotificationManager.class).notify(1, notification(text));
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, SortService.class).setAction("cancel"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(app.devicesorter.R.drawable.ic_app)
                .setContentTitle("设备归档").setContentText(text).setContentIntent(open)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "取消", cancel).build()).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
