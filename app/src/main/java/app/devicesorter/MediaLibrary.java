package app.devicesorter;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles the MediaStore listing with the persistent metadata cache.
 *
 * A sync always asks MediaStore what currently exists (one cheap cursor pass per
 * volume). Only files whose size/date_modified changed, plus brand-new files, are
 * opened for EXIF/QuickTime parsing. Files that vanished are removed in the same
 * pass, so the result is as complete as a full scan without the repeated reads.
 */
final class MediaLibrary {
    interface Progress { void update(String text); }

    record Result(List<MediaItem> items, int added, int updated, int removed, int reused,
                  int skippedArchived, boolean complete, long elapsedMs) {}

    private MediaLibrary() {}

    static Result sync(Context context, boolean rebuildMetadata, Progress progress) throws IOException {
        return sync(context, rebuildMetadata, false, progress);
    }

    static Result sync(Context context, boolean rebuildMetadata, boolean includeArchived,
                       Progress progress) throws IOException {
        long started = SystemClock.elapsedRealtime();
        // Read the generation before querying: if MediaStore changes while the sync
        // is running, the saved generation stays older and the next check syncs again.
        Map<String, Long> generations = MediaCache.generations(context);
        MediaCache.Data cache = MediaCache.load(context);
        MediaCache.State oldState = MediaCache.state(context);
        Storage.Grants grants = Storage.grants(context.getContentResolver());
        boolean grantsChanged = !oldState.grants.equals(grants.signature());
        MediaDiscovery.Listing listing = MediaDiscovery.listDetailed(context, includeArchived);
        int total = listing.items().size();

        List<MediaItem> items = new ArrayList<>(total);
        Map<String, MediaCache.Entry> next = new LinkedHashMap<>();
        int added = 0, updated = 0, reused = 0, index = 0;
        for (MediaDiscovery.Candidate candidate : listing.items()) {
            if (Session.cancel) throw new IOException("扫描已取消");
            String key = MediaCache.mediaKey(candidate.uri());
            MediaCache.Entry old = cache.entries.get(key);
            MediaItem item;
            if (!rebuildMetadata && MediaCache.sameContent(old, candidate)) {
                item = reconcile(context, old.item(), candidate, grants, grantsChanged);
                reused++;
                progress.update("复用缓存 " + (++index) + "/" + total + " · " + candidate.name());
            } else {
                item = inspect(context, candidate, grants);
                if (old == null) added++; else updated++;
                progress.update("读取元数据 " + (++index) + "/" + total + " · " + candidate.name());
            }
            items.add(item);
            next.put(key, new MediaCache.Entry(item, candidate.size(), candidate.modified()));
        }

        int removed = 0;
        if (listing.complete()) {
            for (Map.Entry<String, MediaCache.Entry> entry : cache.entries.entrySet()) {
                if (next.containsKey(entry.getKey())) continue;
                if (entry.getValue().item().mediaUri.isEmpty()) continue; // SAF-only entry
                removed++;
            }
        } else {
            // Partial permissions or a failed volume mean "not seen" is not "deleted".
            // Keep those entries in the cache, but do not put them into the visible list.
            for (Map.Entry<String, MediaCache.Entry> entry : cache.entries.entrySet()) {
                if (next.containsKey(entry.getKey())) continue;
                if (entry.getValue().item().mediaUri.isEmpty()) continue;
                next.put(entry.getKey(), entry.getValue());
            }
        }

        MediaCache.State state = new MediaCache.State();
        state.syncedAt = System.currentTimeMillis();
        state.complete = listing.complete();
        state.fullPermission = MediaDiscovery.full(context);
        state.grants = grants.signature();
        state.generations.putAll(generations);
        boolean cacheChanged = rebuildMetadata || grantsChanged || added > 0 || updated > 0
                || removed > 0;
        if (cacheChanged) MediaCache.save(context, next, state);
        else MediaCache.saveState(context, state);
        return new Result(java.util.Collections.unmodifiableList(items), added, updated, removed,
                reused, listing.skippedArchived(), listing.complete(),
                SystemClock.elapsedRealtime() - started);
    }

    /** Reuse the cached metadata but take the current path/URI/shape from MediaStore. */
    private static MediaItem reconcile(Context context, MediaItem old,
                                       MediaDiscovery.Candidate candidate,
                                       Storage.Grants grants, boolean grantsChanged) {
        String path = path(candidate);
        boolean pathChanged = !path.equals(old.path);
        String uri = old.uri;
        long size = old.size, modified = old.modified;
        // A moved file keeps its EXIF but not its document ID. Re-resolve whenever
        // the path changed or a SAF grant was added/removed.
        boolean resolve = grantsChanged || (pathChanged && isDocumentUri(uri));
        if (resolve) {
            try {
                Uri document = grants.document(documentId(candidate));
                String resolved = document.toString();
                if (grantsChanged && !resolved.equals(uri)) {
                    // First time this tree can address the file: take the provider's
                    // exact size/mtime now, so later copy verification compares like
                    // with like instead of MediaStore's whole-second timestamp.
                    try {
                        Storage.Entry entry = Storage.stat(context.getContentResolver(), document);
                        if (!entry.directory() && candidate.name().equals(entry.name())) {
                            size = entry.size();
                            modified = entry.modified();
                        }
                    } catch (IOException ignored) {
                        // Keep MediaStore's stamp; the transfer still re-stats the source.
                    }
                }
                uri = resolved;
            } catch (IOException e) {
                if (isDocumentUri(uri)) {
                    uri = candidate.uri().toString();
                    size = candidate.size();
                    modified = candidate.modified();
                }
            }
        } else if (pathChanged) {
            // The file has no writable SAF document; its MediaStore URI still follows it.
            uri = candidate.uri().toString();
        }
        if (!resolve && !pathChanged && size == old.size && modified == old.modified
                && mime(candidate).equals(old.mime) && old.video == candidate.video()
                && old.width == candidate.width() && old.height == candidate.height()
                && old.duration == candidate.duration()) {
            return old;
        }
        return new MediaItem(uri, candidate.name(), path, mime(candidate), size, modified,
                candidate.video(), old.model, old.issue, old.capturedAt, old.metadataSource,
                old.parameters, old.manualFolder, candidate.parentId(), candidate.uri().toString(),
                candidate.width(), candidate.height(), candidate.duration());
    }

    /** New or changed file: this is the only place that opens the file for metadata. */
    private static MediaItem inspect(Context context, MediaDiscovery.Candidate candidate,
                                     Storage.Grants grants) {
        String uri = candidate.uri().toString();
        long size = candidate.size(), modified = candidate.modified();
        try {
            Uri document = grants.document(documentId(candidate));
            Storage.Entry entry = Storage.stat(context.getContentResolver(), document);
            if (!entry.directory() && candidate.name().equals(entry.name())) {
                uri = document.toString();
                size = entry.size();
                modified = entry.modified();
            }
        } catch (IOException | RuntimeException ignored) {
            // Discovery is read-only and remains available before folder write authorization.
        }
        String model = null, capturedAt = "", source = "", parameters = "", issue;
        try {
            Metadata.Info info = Metadata.inspect(context.getContentResolver(), Uri.parse(uri),
                    candidate.video());
            model = info.model();
            capturedAt = info.capturedAt();
            source = info.source();
            parameters = info.parameters();
            issue = info.issue();
        } catch (Exception e) {
            issue = "元数据读取失败：" + e.getClass().getSimpleName();
        }
        return new MediaItem(uri, candidate.name(), path(candidate), mime(candidate), size, modified,
                candidate.video(), model, issue, capturedAt, source, parameters, "",
                candidate.parentId(), candidate.uri().toString(), candidate.width(),
                candidate.height(), candidate.duration());
    }

    private static String documentId(MediaDiscovery.Candidate candidate) {
        String parent = candidate.parentId();
        return parent + (parent.endsWith(":") ? "" : "/") + candidate.name();
    }

    private static String path(MediaDiscovery.Candidate candidate) {
        return candidate.parentId() + "/" + candidate.name();
    }

    private static String mime(MediaDiscovery.Candidate candidate) {
        return candidate.mime() == null
                ? (candidate.video() ? "video/mp4" : "image/jpeg") : candidate.mime();
    }

    private static boolean isDocumentUri(String uri) {
        return uri != null && uri.startsWith("content://" + Storage.LOCAL);
    }
}