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
    /** Observer-triggered sync: no foreground notification and no jobStarted flag. */
    private boolean quiet;
    private boolean foreground;

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
        quiet = intent.getBooleanExtra("quiet", false);
        if (!quiet) {
            startForeground(1, notification("正在准备"));
            foreground = true;
        }
        worker = new Thread(() -> {
            try {
                if ("scan_all".equals(intent.getAction())) {
                    scanAll(intent.getBooleanExtra("rebuild", false));
                } else if ("scan".equals(intent.getAction())) scan(intent);
                else if ("copy_direct".equals(intent.getAction())) copyDirect(intent);
                else if ("recall_camera".equals(intent.getAction())) recallCamera(intent);
                else if ("duplicates".equals(intent.getAction())) duplicates();
                else execute(intent);
            } catch (Exception e) {
                Session.status = (Session.cancel ? "已取消" : "未完成") + "：" + e.getMessage();
            } finally {
                // Restore the media list inside this service instance. Starting a second
                // service from the Activity here can race with stopSelf() and lose the scan.
                if (Session.rescanRequested) {
                    Session.rescanRequested = false;
                    String result = Session.status == null ? "" : Session.status;
                    try {
                        MediaLibrary.Result sync = MediaLibrary.sync(this, false, includeArchived(), this::progress);
                        Session.saveScan(this, sync.items());
                        Session.status = result + (result.endsWith("。") ? "" : "；")
                                + "已增量同步（复用 " + sync.reused() + " 个缓存，新增 "
                                + sync.added() + "，更新 " + sync.updated() + "，移除 "
                                + sync.removed() + "）";
                    } catch (Exception e) {
                        Session.status = result + (result.endsWith("。") ? "" : "；")
                                + "自动同步失败：" + e.getMessage();
                    }
                }
                Session.running = false;
                getSharedPreferences("settings", 0).edit().putBoolean("jobStarted", false).apply();
                Session.revision++;
                if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
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
        boolean[] skippedArchiveDir = {false};
        boolean archives = includeArchived();
        List<String> unreadable = new ArrayList<>();
        List<String> emptyDirs = new ArrayList<>();
        for (String source : sources) {
            Uri tree = Uri.parse(source);
            String treeId = Storage.id(tree);
            // A dropped grant and an empty folder both end as "0 files"; tell them apart
            // instead of reporting a successful scan of nothing.
            if (!Storage.readable(getContentResolver(), treeId)) {
                unreadable.add(MediaPaths.display(treeId));
                continue;
            }
            Uri root = Storage.root(tree);
            Storage.Entry rootEntry;
            try {
                rootEntry = Storage.stat(getContentResolver(), root);
            } catch (IOException e) {
                unreadable.add(MediaPaths.display(treeId));
                continue;
            }
            int before = found.size();
            walk(root, rootEntry.name(), found, visited, nonMedia, archives, skippedArchiveDir);
            if (found.size() == before) emptyDirs.add(MediaPaths.display(treeId));
        }
        cancelled();
        Session.saveScan(this, found);
        long recognized = found.stream().filter(item -> item.model != null).count();
        if (found.isEmpty()) {
            StringBuilder reason = new StringBuilder();
            if (!unreadable.isEmpty()) {
                reason.append("授权已失效：").append(String.join("、", unreadable)).append("。");
            }
            if (skippedArchiveDir[0]) {
                reason.append("目录里只有「分类」文件夹（已整理过）；开启“包含已归档的文件”可重新整理。");
            } else if (!emptyDirs.isEmpty()) {
                reason.append("没有照片或视频：").append(String.join("、", emptyDirs)).append("。");
            }
            if (reason.length() == 0) reason.append("这些目录里没有照片或视频。");
            Session.status = "没有读到可整理的媒体 · " + reason;
            return;
        }
        Session.status = "扫描完成 · " + found.size() + " 个文件 · 已识别 " + recognized
                + (nonMedia[0] > 0 ? " · 跳过 " + nonMedia[0] + " 个非媒体文件" : "")
                + (emptyDirs.isEmpty() ? "" : " · " + emptyDirs.size() + " 个目录为空");
    }

    private void walk(Uri directory, String path, List<MediaItem> found, Set<String> visited,
                      int[] nonMedia, boolean includeArchived, boolean[] skippedArchiveDir) throws Exception {
        cancelled();
        String directoryId = DocumentsContract.getDocumentId(directory);
        if (MediaPaths.hidden(directoryId)) return;
        if (!includeArchived && MediaPaths.archived(directoryId)) {
            skippedArchiveDir[0] = true;
            return;
        }
        if (!visited.add(directory.getAuthority() + ":" + directoryId)) return;
        for (Storage.Entry entry : Storage.children(getContentResolver(), directory)) {
            cancelled();
            if (entry.directory()) {
                walk(entry.uri(), path + "/" + entry.name(), found, visited, nonMedia,
                        includeArchived, skippedArchiveDir);
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
            String capturedAt = "", metadataSource = "", parameters = "";
            try {
                Metadata.Info info = Metadata.inspect(getContentResolver(), entry.uri(), video);
                model = info.model();
                capturedAt = info.capturedAt();
                metadataSource = info.source();
                parameters = info.parameters();
                issue = info.issue();
            } catch (Exception e) {
                issue = "元数据读取失败：" + e.getClass().getSimpleName();
            }
            found.add(new MediaItem(entry.uri().toString(), entry.name(), path + "/" + entry.name(),
                    mime.isEmpty() ? (video ? "video/mp4" : "image/jpeg") : mime,
                    entry.size(), entry.modified(), video, model, issue,
                    capturedAt, metadataSource, parameters, "", DocumentsContract.getDocumentId(directory)));
        }
    }

    /**
     * Ask MediaStore what exists now, but reuse the cached EXIF/QuickTime result for
     * files whose size and date_modified did not change. This is what runs after
     * every copy/move/trash, so a task no longer forces a metadata read of the whole
     * library.
     */
    private void scanAll(boolean rebuild) throws Exception {
        MediaLibrary.Result result = MediaLibrary.sync(this, rebuild, includeArchived(), this::progress);
        cancelled();
        Session.saveScan(this, result.items());
        if (result.items().isEmpty()) {
            if (result.skippedArchived() > 0) {
                Session.status = "没有待整理的文件 · " + result.skippedArchived()
                        + " 个在「分类」里（已整理过）；开启“包含已归档的文件”可重新整理";
            } else {
                Session.status = "系统媒体库中没有找到照片或视频 · " + MediaDiscovery.scope(this)
                        + "；系统相册找不到的文件夹，请在“补充扫描目录”里添加";
            }
            return;
        }
        String elapsed = result.elapsedMs() < 100 ? "<0.1"
                : String.format(Locale.ROOT, "%.1f", result.elapsedMs() / 1000.0);
        // Keep the header line short: the full scope already lives on the scan card,
        // and a 60-character status was clipped on a 347dp screen.
        Session.status = "媒体库查找完成 · 待整理 " + result.items().size() + " 个文件"
                + (result.skippedArchived() > 0 ? " · 已归档跳过 " + result.skippedArchived() + " 个" : "")
                + (result.added() > 0 ? " · 新增 " + result.added() : "")
                + " · " + elapsed + " 秒";
    }

    /** The scan card's “包含已归档的文件” switch, read at scan time. */
    private boolean includeArchived() {
        return getSharedPreferences("settings", 0).getBoolean("includeArchived", false);
    }

    private void execute(Intent intent) throws Exception {
        ArrayList<String> groups = intent.getStringArrayListExtra("groups");
        String rule = intent.getStringExtra("rule");
        ArrayList<String> sources = intent.getStringArrayListExtra("sources");
        String target = intent.getStringExtra("target");
        boolean inPlace = intent.getBooleanExtra("inPlace", true);
        if (groups == null || sources == null || (!inPlace && target == null) || !Classification.valid(rule)) {
            throw new IOException("任务参数不完整，请重新预览");
        }
        Uri tree = inPlace ? null : Uri.parse(target);
        if (!inPlace) {
            String targetId = Storage.id(tree);
            for (String source : sources) {
                if (Storage.id(Uri.parse(source)).equals(targetId)) {
                    throw new IOException("目标目录不能就是来源目录；请换一个文件夹，或使用就地分类");
                }
            }
        }
        boolean move = intent.getBooleanExtra("move", false);
        boolean trash = intent.getBooleanExtra("trash", false);
        if (move && !inPlace) throw new IOException("系统移动仅用于来源目录内分类，不支持跨目标移动");
        if (move && trash) throw new IOException("不能同时选择系统移动和回收站");
        if (trash && !Trash.supported()) {
            throw new IOException("Android 11 以下没有可恢复的系统回收站；请选择“复制，保留原件”");
        }
        Set<String> excluded = new HashSet<>();
        ArrayList<String> excludedExtra = intent.getStringArrayListExtra("excludedFolders");
        if (excludedExtra != null) excluded.addAll(excludedExtra);
        int perModel = intent.getIntExtra("limit", 0);
        Set<String> selected = new HashSet<>(groups);
        Map<String, Integer> counts = new HashMap<>();
        List<MediaItem> plan = new ArrayList<>();
        boolean archives = includeArchived();
        for (MediaItem item : Session.items) {
            if (excluded.contains(item.parentId) || MediaPaths.hidden(item.parentId)) continue;
            if (MediaPaths.archived(item.parentId) && !archives) continue;
            String destination = Classification.folder(item, rule);
            if (destination == null || !selected.contains(destination)) continue;
            String countKey = (inPlace ? item.parentId + "/" : "") + destination + "|" + item.video;
            int count = counts.getOrDefault(countKey, 0);
            if (perModel > 0 && count >= perModel) continue;
            plan.add(item);
            counts.put(countKey, count + 1);
        }
        if (plan.isEmpty()) throw new IOException("没有选中的文件，请先扫描");
        Session.saveScan(this, java.util.Collections.emptyList());
        int completed = 0, failed = 0, skipped = 0;
        boolean trashPending = false;
        List<VerifiedCopy> verified = new ArrayList<>();
        Map<String, Uri> folders = new HashMap<>();
        try (Session.Journal journal = new Session.Journal(this)) {
            journal.write(Session.eventName("START", null, inPlace ? "SOURCE/" + MediaPaths.ARCHIVE : target, "",
                    (move ? "SYSTEM_MOVE" : trash ? "COPY_TRASH" : "COPY") + ";rule=" + rule));
            int index = 0;
            for (MediaItem item : plan) {
                if (Session.cancel) break;
                progress(++index + "/" + plan.size() + " · " + item.name);
                Uri folder = null;
                try {
                    String destination = Classification.folder(item, rule);
                    if (!inPlace && item.parentId.equals(Storage.id(tree))) {
                        throw new IOException("目标目录就是来源目录；换一个文件夹，或在“就地分类”中保留原件");
                    }
                    // Re-sorting an archived file keeps the same 分类 root: only the
                    // sub-folders under it are replaced, so 分类/分类 can never appear.
                    String baseId = MediaPaths.archived(item.parentId)
                            ? MediaPaths.archiveBase(item.parentId) : item.parentId;
                    String key = inPlace ? baseId + "/" + destination : destination;
                    folder = folders.get(key);
                    if (folder == null) {
                        if (inPlace) {
                            if (baseId.isEmpty()) throw new IOException("旧扫描不包含来源目录，请重新扫描");
                            folder = Storage.authorizedDocument(getContentResolver(), baseId);
                            folder = Storage.folder(getContentResolver(), folder, MediaPaths.ARCHIVE);
                        } else folder = Storage.root(tree);
                        if (!destination.isEmpty()) {
                            for (String component : destination.split("/")) {
                                folder = Storage.folder(getContentResolver(), folder, component);
                            }
                        }
                        folders.put(key, folder);
                    }
                    if (inPlace && DocumentsContract.getDocumentId(folder).equals(item.parentId)) {
                        // Already filed under the same rule; moving it would be a no-op.
                        skipped++;
                        continue;
                    }
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, "", "", e.toString()));
                    continue;
                }
                AndroidFiles files = new AndroidFiles(item, folder, journal);
                try {
                    if (move) {
                        if (!Storage.LOCAL.equals(Uri.parse(item.uri).getAuthority())) {
                            throw new IOException("请授权来源目录并重新扫描后再移动：" + item.parentId);
                        }
                        NativeMove.run(new AndroidMove(item, folder, journal), item.size, item.modified);
                    } else {
                        Transfer.Outcome outcome = Transfer.run(files, false, item.size, item.modified);
                        journal.write(Session.event(outcome.status(), item, files.targetText(), outcome.hash(), ""));
                        verified.add(new VerifiedCopy(item, files.targetUri(), files.targetName(), outcome.hash()));
                    }
                    completed++;
                } catch (NativeMove.UncertainException e) {
                    try {
                        journal.write(Session.event("MOVE_UNCERTAIN", item, folder.toString(), "", e.toString()));
                    } catch (IOException logFailure) {
                        e.addSuppressed(logFailure);
                    }
                    throw e;
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, files.targetText(), "", e.toString()));
                }
            }
            if (trash) {
                int broken = verifyTargets(verified, journal);
                if (broken > 0) {
                    failed += broken;
                    completed -= broken;
                }
            }
            boolean allCopied = !Session.cancel && failed == 0 && completed == plan.size();
            if (trash && allCopied && perModel == 0 && !verified.isEmpty()) {
                trashPending = savePending("整理后移入回收站",
                        inPlace ? "各来源目录/" + MediaPaths.ARCHIVE : targetText(tree),
                        plan.size(), completed, failed, verified);
            }
            journal.write(Session.eventName(Session.cancel ? "CANCELLED" : "FINISHED", null, target, "",
                    "completed=" + completed + ";failed=" + failed + ";planned=" + plan.size()
                            + ";trashPending=" + trashPending));
        } finally {
            // The empty snapshot was persisted before the first file operation.
            Session.items = java.util.Collections.emptyList();
            Session.revision++;
            Session.rescanRequested = true;
        }
        Session.status = (Session.cancel ? "已取消" : "整理结束") + " · 成功 " + completed
                + " · 未完成 " + failed + " · 未开始 " + (plan.size() - completed - failed - skipped)
                + (skipped > 0 ? " · 已在目标位置跳过 " + skipped : "")
                + (trashPending
                ? "；全部复制并校验完成，回到 App 确认是否将原件移入回收站"
                : trash && perModel > 0
                ? "；试跑不询问回收站，原件保留"
                : trash && !Session.cancel && failed == 0 && completed == plan.size()
                ? "；复制完成，但源文件没有媒体库索引，无法生成回收站清单，原件保留"
                : "；详细结果见操作记录。");
    }

    private record VerifiedCopy(MediaItem item, Uri target, String name, String hash) {}

    /** Re-stat every final target after the batch, so a trash prompt needs a complete manifest. */
    private int verifyTargets(List<VerifiedCopy> copies, Session.Journal journal) throws IOException {
        int broken = 0;
        for (VerifiedCopy copy : copies) {
            try {
                if (copy.target() == null) throw new IOException("目标位置缺失");
                Storage.Entry entry = Storage.stat(getContentResolver(), copy.target());
                if (entry.directory()) throw new IOException("目标不是文件");
                if (copy.item().size >= 0 && entry.size() != copy.item().size) {
                    throw new IOException("目标大小与源不一致");
                }
                if (!copy.name().equals(entry.name())) throw new IOException("目标文件名与预期不一致");
            } catch (IOException | RuntimeException e) {
                broken++;
                journal.write(Session.event("FAILED_FINAL_VERIFY", copy.item(),
                        copy.target() == null ? "" : copy.target().toString(), copy.hash(), e.toString()));
            }
        }
        return broken;
    }

    private boolean savePending(String action, String targetLabel, int planned, int completed, int failed,
                                List<VerifiedCopy> copies) {
        List<Trash.Item> items = new ArrayList<>();
        for (VerifiedCopy copy : copies) {
            if (!copy.item().trashable()) continue;
            items.add(new Trash.Item(copy.item().mediaUri, copy.item().name,
                    MediaPaths.display(copy.item().parentId) + "/" + copy.item().name,
                    targetLabel + "/" + copy.name(), copy.item().size, copy.hash()));
        }
        if (items.isEmpty()) return false;
        try {
            Trash.save(this, new Trash.Pending(action, targetLabel, planned, completed, failed,
                    java.util.Collections.unmodifiableList(items)));
            return true;
        } catch (IOException e) {
            Session.status = "复制已完成，但无法保存回收站清单：" + e.getMessage();
            return false;
        }
    }

    private String targetText(Uri tree) {
        if (tree == null) return "";
        try { return MediaPaths.display(Storage.id(tree)); }
        catch (IOException e) { return tree.toString(); }
    }

    /**
     * Copy selected files directly into one chosen SAF folder, without building
     * classification subdirectories. The source folder is never the target.
     */
    private void copyDirect(Intent intent) throws Exception {
        ArrayList<String> uris = intent.getStringArrayListExtra("uris");
        String target = intent.getStringExtra("target");
        boolean trash = intent.getBooleanExtra("trash", true);
        if (uris == null || uris.isEmpty() || target == null) throw new IOException("任务参数不完整，请重新选择");
        if (trash && !Trash.supported()) throw new IOException("Android 11 以下没有可恢复的系统回收站");
        Uri tree = Uri.parse(target);
        String targetId = Storage.id(tree);
        Set<String> wanted = new HashSet<>(uris);
        List<MediaItem> plan = new ArrayList<>();
        for (MediaItem item : Session.items) if (wanted.contains(item.uri)) plan.add(item);
        if (plan.isEmpty()) throw new IOException("选中的文件已不在当前扫描结果中，请重新查找");
        for (MediaItem item : plan) {
            if (targetId.equals(item.parentId)) {
                throw new IOException("目标目录就是这些文件的原目录，直接放入会在同一目录生成副本；请换一个文件夹");
            }
        }
        Session.saveScan(this, java.util.Collections.emptyList());
        int completed = 0, failed = 0;
        boolean trashPending = false;
        List<VerifiedCopy> verified = new ArrayList<>();
        Uri root = Storage.root(tree);
        String targetLabel = MediaPaths.display(targetId);
        try (Session.Journal journal = new Session.Journal(this)) {
            journal.write(Session.eventName("START", null, targetLabel, "",
                    (trash ? "COPY_DIRECT_TRASH" : "COPY_DIRECT") + ";files=" + plan.size()));
            int index = 0;
            for (MediaItem item : plan) {
                if (Session.cancel) break;
                progress(++index + "/" + plan.size() + " · " + item.name);
                AndroidFiles files = new AndroidFiles(item, root, journal);
                try {
                    Transfer.Outcome outcome = Transfer.run(files, false, item.size, item.modified);
                    journal.write(Session.event(outcome.status(), item, files.targetText(), outcome.hash(), "直接复制"));
                    verified.add(new VerifiedCopy(item, files.targetUri(), files.targetName(), outcome.hash()));
                    completed++;
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, files.targetText(), "", e.toString()));
                }
            }
            int broken = verifyTargets(verified, journal);
            if (broken > 0) {
                failed += broken;
                completed -= broken;
            }
            boolean all = !Session.cancel && failed == 0 && completed == plan.size() && !verified.isEmpty();
            if (trash && all) trashPending = savePending("复制到文件夹后移入回收站", targetLabel,
                    plan.size(), completed, failed, verified);
            journal.write(Session.eventName(Session.cancel ? "CANCELLED" : "FINISHED", null, targetLabel, "",
                    "completed=" + completed + ";failed=" + failed + ";planned=" + plan.size()
                            + ";trashPending=" + trashPending));
        } finally {
            Session.items = java.util.Collections.emptyList();
            Session.revision++;
            Session.rescanRequested = true;
        }
        Session.status = (Session.cancel ? "已取消" : "直接复制结束") + " · 成功 " + completed
                + " · 未完成 " + failed + " · 未开始 " + (plan.size() - completed - failed)
                + (trashPending
                ? "；全部复制并校验完成，回到 App 确认是否将原件移入回收站"
                : trash && !Session.cancel && failed == 0 && completed == plan.size()
                ? "；复制完成，但源文件没有媒体库索引，无法生成回收站清单，原件保留"
                : "；详细结果见操作记录");
    }

    /** Copy stray media into DCIM/Camera through MediaStore, then offer the recycle bin. */
    private void recallCamera(Intent intent) throws Exception {
        ArrayList<String> uris = intent.getStringArrayListExtra("uris");
        if (uris == null || uris.isEmpty()) throw new IOException("没有选择要放回 DCIM/Camera 的文件");
        if (android.os.Build.VERSION.SDK_INT < 29) {
            throw new IOException("Android 10 以下无法直接写入系统媒体库，请用目录授权复制");
        }
        Set<String> wanted = new HashSet<>(uris);
        List<MediaItem> plan = new ArrayList<>();
        for (MediaItem item : Session.items) if (wanted.contains(item.uri)) plan.add(item);
        if (plan.isEmpty()) throw new IOException("选中的文件已不在当前扫描结果中，请重新查找");
        Session.saveScan(this, java.util.Collections.emptyList());
        int completed = 0, failed = 0;
        boolean trashPending = false;
        List<Restored> restored = new ArrayList<>();
        try (Session.Journal journal = new Session.Journal(this)) {
            journal.write(Session.eventName("START", null, MediaPaths.display(MediaPaths.CAMERA_ID), "",
                    "RECALL_CAMERA;files=" + plan.size()));
            int index = 0;
            for (MediaItem item : plan) {
                if (Session.cancel) break;
                progress(++index + "/" + plan.size() + " · " + item.name);
                try {
                    if (MediaPaths.inCamera(item.parentId)) {
                        throw new IOException("源文件已在 DCIM/Camera，不能放回原位");
                    }
                    MediaStoreOps.Copy copy = MediaStoreOps.copyToCamera(this, item);
                    String target = MediaPaths.display(MediaPaths.CAMERA_ID) + "/" + copy.name();
                    journal.write(Session.event(copy.existing() ? "CAMERA_EXISTS" : "COPIED",
                            item, target, copy.hash(), copy.existing() ? "DCIM/Camera 已有相同内容" : ""));
                    restored.add(new Restored(item, copy.name(), copy.hash()));
                    completed++;
                } catch (Exception e) {
                    failed++;
                    journal.write(Session.event("FAILED", item, "", "", e.toString()));
                }
            }
            boolean all = !Session.cancel && failed == 0 && completed == plan.size() && !restored.isEmpty();
            if (all) {
                List<Trash.Item> items = new ArrayList<>();
                for (Restored item : restored) {
                    Uri source = MediaStoreOps.sourceUri(item.item());
                    if (source == null) continue;
                    items.add(new Trash.Item(source.toString(), item.item().name,
                            MediaPaths.display(item.item().parentId) + "/" + item.item().name,
                            MediaPaths.display(MediaPaths.CAMERA_ID) + "/" + item.name(),
                            item.item().size, item.hash()));
                }
                if (!items.isEmpty()) {
                    try {
                        Trash.save(this, new Trash.Pending("放回 DCIM/Camera 后移入回收站",
                                MediaPaths.display(MediaPaths.CAMERA_ID), plan.size(), completed, failed,
                                java.util.Collections.unmodifiableList(items)));
                        trashPending = true;
                    } catch (IOException e) {
                        Session.status = "复制已完成，但无法保存回收站清单：" + e.getMessage();
                    }
                }
            }
            journal.write(Session.eventName(Session.cancel ? "CANCELLED" : "FINISHED", null,
                    MediaPaths.display(MediaPaths.CAMERA_ID), "",
                    "completed=" + completed + ";failed=" + failed + ";planned=" + plan.size()
                            + ";trashPending=" + trashPending));
        } finally {
            Session.items = java.util.Collections.emptyList();
            Session.revision++;
            Session.rescanRequested = true;
        }
        Session.status = (Session.cancel ? "已取消" : "放回 DCIM/Camera 结束") + " · 成功 " + completed
                + " · 未完成 " + failed + " · 未开始 " + (plan.size() - completed - failed)
                + (trashPending
                ? "；全部复制并校验完成，回到 App 确认是否将原件移入回收站"
                : failed == 0 && completed == plan.size()
                ? "；全部复制完成；这些文件没有媒体库索引，原件保留"
                : "；成功复制的文件已在 DCIM/Camera，未完成的文件保留原处");
    }

    private record Restored(MediaItem item, String name, String hash) {}

    /** Size prefilter, then SHA-256; no file is deleted by this scan. */
    private void duplicates() throws Exception {
        if (Session.items.isEmpty()) throw new IOException("请先查找手机照片与视频，再查重");
        Duplicates.Result result = Duplicates.find(Session.items,
                item -> Hashing.of(getContentResolver(), Uri.parse(item.uri)),
                () -> Session.cancel,
                percent -> progress("查重 " + percent + "%"));
        List<Duplicates.Group> groups = result.groups();
        Session.saveDuplicates(this, groups);
        long duplicateFiles = 0, bytes = 0;
        for (Duplicates.Group group : groups) {
            duplicateFiles += Math.max(0, group.items().size() - 1);
            bytes += group.wasted();
        }
        Session.status = "查重完成 · " + groups.size() + " 组 · 可清理 " + duplicateFiles
                + " 个重复文件 · 约 " + (bytes >= 1073741824
                ? String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
                : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0))
                + (result.skipped() > 0 ? "；" + result.skipped() + " 个文件无法读取已跳过" : "")
                + "；不会自动删除";
    }

    private final class AndroidMove implements NativeMove.Files {
        private final MediaItem item;
        private final Uri source, parent, folder;
        private final Session.Journal journal;
        private Uri target;

        AndroidMove(MediaItem item, Uri folder, Session.Journal journal) throws IOException {
            this.item = item;
            this.folder = folder;
            this.journal = journal;
            source = Storage.authorizedDocument(getContentResolver(),
                    DocumentsContract.getDocumentId(Uri.parse(item.uri)));
            parent = Storage.authorizedDocument(getContentResolver(), item.parentId);
        }

        private InputStream read(Uri uri) throws IOException {
            InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) throw new IOException("无法读取移动文件");
            return stream;
        }
        @Override public InputStream readSource() throws IOException { return read(source); }
        @Override public InputStream readTarget() throws IOException { return read(target); }
        @Override public long sourceSize() throws IOException { return Storage.stat(getContentResolver(), source).size(); }
        @Override public long sourceModified() throws IOException { return Storage.stat(getContentResolver(), source).modified(); }
        @Override public boolean supported() throws IOException { return Storage.supportsMove(getContentResolver(), source, folder); }
        @Override public boolean targetExists() throws IOException { return Storage.find(getContentResolver(), folder, item.name) != null; }
        @Override public void move() throws IOException {
            target = DocumentsContract.moveDocument(getContentResolver(), source, parent, folder);
            if (target == null) throw new IOException("系统未返回移动后的文件位置");
            String movedId = DocumentsContract.getDocumentId(target);
            if (!Storage.LOCAL.equals(target.getAuthority())
                    || !MediaPaths.parent(movedId).equals(DocumentsContract.getDocumentId(folder))
                    || !Storage.stat(getContentResolver(), target).name().equals(item.name)
                    || Storage.find(getContentResolver(), parent, item.name) != null) {
                throw new IOException("系统返回的移动位置或源目录状态与预期不符");
            }
        }
        @Override public void record(String status, String hash) throws IOException {
            journal.write(Session.event(status, item, target == null ? folder.toString() : target.toString(),
                    hash, "source=" + source + ";parent=" + parent + ";name=" + item.name));
        }
        @Override public boolean cancelled() { return Session.cancel; }
    }

    private final class AndroidFiles implements Transfer.Files {
        private final MediaItem item;
        private final Uri folder;
        private final Session.Journal journal;
        private Uri target;
        private String finalName;

        AndroidFiles(MediaItem item, Uri folder, Session.Journal journal) {
            this.item = item; this.folder = folder; this.journal = journal;
            this.finalName = item.name;
        }
        String targetText() { return target == null ? "" : target.toString(); }
        Uri targetUri() { return target; }
        String targetName() { return finalName; }
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
        @Override public void syncTarget() throws IOException {
            Storage.sync(getContentResolver(), target);
        }
        @Override public long sourceSize() throws IOException {
            if ("media".equals(Uri.parse(item.uri).getAuthority())) {
                return MediaDiscovery.stat(SortService.this, Uri.parse(item.uri)).size();
            }
            return Storage.stat(getContentResolver(), Uri.parse(item.uri)).size();
        }
        @Override public long sourceModified() throws IOException {
            if ("media".equals(Uri.parse(item.uri).getAuthority())) {
                return MediaDiscovery.stat(SortService.this, Uri.parse(item.uri)).modified();
            }
            return Storage.stat(getContentResolver(), Uri.parse(item.uri)).modified();
        }
        @Override public void deleteSource() throws IOException {
            throw new IOException("复制归档禁止删除源文件");
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
                finalName = name;
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
        if (quiet) return;
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
                .setContentTitle("归藏").setContentText(text).setContentIntent(open)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "取消", cancel).build()).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
