package app.devicesorter;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MediaDiscovery {
    record Candidate(Uri uri, String name, String mime, long size, long modified,
                     boolean video, String parentId, int width, int height, long duration) {}
    record State(long size, long modified) {}

    /**
     * A cursor pass plus whether that pass can be trusted as the complete media
     * set. Incremental sync only deletes cached files when the answer is complete;
     * a partial permission grant or a failed volume must never look like a delete.
     */
    record Listing(List<Candidate> items, boolean complete, int skippedArchived) {}

    static State stat(Context context, Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {"_size", "date_modified"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("媒体文件不存在或授权已失效");
            return new State(cursor.isNull(0) ? -1 : cursor.getLong(0),
                    cursor.isNull(1) ? 0 : cursor.getLong(1) * 1000);
        } catch (RuntimeException e) {
            throw new IOException("无法读取媒体文件属性", e);
        }
    }

    static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean full(Context context) {
        return Build.VERSION.SDK_INT >= 33
                ? granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO)
                : granted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    static boolean accessible(Context context) {
        return full(context) || (Build.VERSION.SDK_INT >= 33
                && (granted(context, Manifest.permission.READ_MEDIA_IMAGES)
                || granted(context, Manifest.permission.READ_MEDIA_VIDEO)))
                || (Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));
    }

    static String scope(Context context) {
        return full(context)
                ? "媒体库全部已索引目录（含 Download、Movies 等；不含未索引或系统受限文件）"
                : "仅部分授权，结果不完整：只显示你选中的照片/视频，Download 等目录可能缺失";
    }

    static List<Candidate> list(Context context) throws IOException {
        return listDetailed(context, false).items();
    }

    static Listing listDetailed(Context context) throws IOException {
        return listDetailed(context, false);
    }

    static Listing listDetailed(Context context, boolean includeArchived) throws IOException {
        if (!accessible(context)) throw new IOException("未获得照片或视频读取权限");
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean queried = false;
        int skippedArchived = 0;
        boolean complete = full(context);
        List<String> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                volumes.addAll(MediaStore.getExternalVolumeNames(context));
            } catch (RuntimeException ignored) {
                // Some OEM providers fail the volume enumeration; primary below is still useful.
                complete = false;
            }
            if (volumes.isEmpty()) {
                volumes.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                complete = false;
            }
        } else {
            volumes.add(null);
        }
        // Query every external volume, not just the synthetic "external" view. Files in
        // Download, Movies, Documents, screenshots and app media directories are indexed
        // by MediaStore and therefore appear here just like they do in the system gallery.
        for (String volume : volumes) {
            for (boolean video : new boolean[] {false, true}) {
                if (Build.VERSION.SDK_INT >= 33 && !granted(context, video
                        ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_MEDIA_IMAGES)
                        && !(Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) {
                    complete = false;
                    continue;
                }
                Uri collection = Build.VERSION.SDK_INT >= 29
                        ? (video ? MediaStore.Video.Media.getContentUri(volume)
                        : MediaStore.Images.Media.getContentUri(volume))
                        : (video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                String[] columns = Build.VERSION.SDK_INT >= 29
                        ? new String[] {"_id", "_display_name", "mime_type", "_size", "date_modified",
                                "relative_path", "volume_name", "width", "height", "duration"}
                        : new String[] {"_id", "_display_name", "mime_type", "_size", "date_modified",
                                "_data", "width", "height"};
                String selection = Build.VERSION.SDK_INT >= 30 ? "is_pending=0 AND is_trashed=0"
                        : Build.VERSION.SDK_INT >= 29 ? "is_pending=0" : null;
                try (Cursor cursor = context.getContentResolver().query(collection, columns, selection, null, "_id ASC")) {
                    if (cursor == null) {
                        complete = false;
                        continue;
                    }
                    queried = true;
                    while (cursor.moveToNext()) {
                        if (Session.cancel) throw new IOException("扫描已取消");
                        long id = cursor.getLong(0);
                        String key = volume + "/" + video + "/" + id;
                        if (!seen.add(key)) continue;
                        Uri publicCollection = Build.VERSION.SDK_INT >= 29
                                && !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)
                                ? collection
                                : (video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        String parent;
                        if (Build.VERSION.SDK_INT >= 29) {
                            String rowVolume = cursor.isNull(6) ? volume : cursor.getString(6);
                            if (rowVolume == null || rowVolume.isEmpty()) rowVolume = volume;
                            parent = MediaPaths.parentId(rowVolume, cursor.getString(5));
                        } else {
                            String path = cursor.getString(5);
                            String primary = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
                            if (path != null && path.startsWith(primary)) {
                                parent = MediaPaths.parentId("external_primary",
                                        path.substring(primary.length(), path.lastIndexOf('/') + 1));
                            } else if (path != null && path.startsWith("/storage/")) {
                                String[] parts = path.substring(9).split("/", 2);
                                parent = parts.length == 2 ? MediaPaths.parentId(parts[0],
                                        parts[1].substring(0, Math.max(0, parts[1].lastIndexOf('/') + 1))) : "";
                            } else parent = "";
                        }
                        String name = cursor.getString(1);
                        if (parent.isEmpty() || MediaPaths.hidden(parent) || name == null || name.startsWith(".")) continue;
                        if (!includeArchived && MediaPaths.archived(parent)) { skippedArchived++; continue; }
                        int width = cursor.isNull(7) ? 0 : cursor.getInt(7);
                        int height = cursor.isNull(8) ? 0 : cursor.getInt(8);
                        long duration = Build.VERSION.SDK_INT >= 29 && !cursor.isNull(9) ? cursor.getLong(9) : 0;
                        if (Build.VERSION.SDK_INT < 29) {
                            width = cursor.isNull(6) ? 0 : cursor.getInt(6);
                            height = cursor.isNull(7) ? 0 : cursor.getInt(7);
                        }
                        result.add(new Candidate(ContentUris.withAppendedId(publicCollection, id),
                                name, cursor.getString(2), cursor.isNull(3) ? -1 : cursor.getLong(3),
                                cursor.isNull(4) ? 0 : cursor.getLong(4) * 1000, video, parent,
                                width, height, duration));
                    }
                } catch (RuntimeException e) {
                    // A volume can be unmounted or denied independently; keep other volumes.
                    complete = false;
                }
            }
        }
        if (!queried) throw new IOException("媒体库查询失败，请检查照片/视频权限后重试");
        return new Listing(result, complete && queried, skippedArchived);
    }
}
