package app.devicesorter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

/**
 * Opens one media item in whichever installed app handles its type: photos go to a
 * viewer, videos go to a player. Kept in one place so every "预览 / 查看 / 播放"
 * entry point behaves the same.
 */
final class MediaViewer {
    private MediaViewer() {}

    static void open(Activity activity, MediaItem item) {
        if (item.uri == null || item.uri.isEmpty()) {
            Ui.toast(activity, "这个文件没有可打开的位置");
            return;
        }
        String mime = item.mime == null ? "" : item.mime;
        // A SAF entry can report application/octet-stream; the viewer still needs to
        // know whether it should open a photo viewer or a video player.
        if (!mime.startsWith(item.video ? "video/" : "image/")) {
            mime = item.video ? "video/*" : "image/*";
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(item.uri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            Ui.toast(activity, item.video ? "没有可播放视频的应用" : "没有可查看照片的应用");
        } catch (SecurityException e) {
            Ui.toast(activity, "无法读取这个文件，可能已被移动或删除");
        } catch (Exception e) {
            Ui.toast(activity, "无法打开这个文件");
        }
    }
}