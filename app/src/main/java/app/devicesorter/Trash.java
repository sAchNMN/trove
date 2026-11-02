package app.devicesorter;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A verified-copy manifest plus the system recycle-bin request.
 *
 * Copies are verified one by one by {@link Transfer}; this class only offers a
 * trash request after the whole batch copied successfully. It never deletes a
 * file itself. The user must confirm the Android system dialog, and the system
 * recycle bin keeps the file recoverable.
 */
final class Trash {
    static final int MAX_REQUEST = 200;

    private Trash() {}

    record Item(String uri, String name, String source, String target, long size, String hash) {
        JSONObject json() throws JSONException {
            return new JSONObject().put("uri", uri).put("name", name).put("source", source)
                    .put("target", target).put("size", size).put("hash", hash);
        }

        static Item from(JSONObject object) {
            return new Item(object.optString("uri"), object.optString("name"),
                    object.optString("source"), object.optString("target"),
                    object.optLong("size"), object.optString("hash"));
        }
    }

    record Pending(String action, String target, int planned, int copied, int failed,
                   List<Item> items) {
        boolean complete() {
            return planned > 0 && copied == planned && failed == 0;
        }

        JSONObject json() throws JSONException {
            JSONArray array = new JSONArray();
            for (Item item : items) array.put(item.json());
            return new JSONObject().put("action", action).put("target", target)
                    .put("planned", planned).put("copied", copied).put("failed", failed)
                    .put("items", array);
        }

        static Pending from(JSONObject object) throws JSONException {
            List<Item> items = new ArrayList<>();
            JSONArray array = object.optJSONArray("items");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) items.add(Item.from(array.getJSONObject(i)));
            }
            return new Pending(object.optString("action"), object.optString("target"),
                    object.optInt("planned"), object.optInt("copied"), object.optInt("failed"),
                    java.util.Collections.unmodifiableList(items));
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "pending-trash.json");
    }

    static void save(Context context, Pending pending) throws IOException {
        AtomicFile file = new AtomicFile(file(context));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(pending.json().toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | JSONException e) {
            file.failWrite(stream);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("无法保存回收站清单", e);
        }
    }

    static Pending load(Context context) {
        try {
            AtomicFile file = new AtomicFile(file(context));
            return Pending.from(new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static void clear(Context context) {
        File file = file(context);
        if (file.isFile()) file.delete();
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** The system shows its own confirmation; the app cannot bypass it. */
    static IntentSender request(ContentResolver resolver, List<Item> items) throws IOException {
        if (!supported()) throw new IOException("Android 11 以下没有可恢复的系统回收站，未删除任何文件");
        if (items == null || items.isEmpty()) throw new IOException("没有可移入回收站的文件");
        if (items.size() > MAX_REQUEST) throw new IOException("单次最多处理 " + MAX_REQUEST + " 个，请分批操作");
        List<Uri> uris = new ArrayList<>();
        for (Item item : items) {
            if (item.uri() == null || item.uri().isEmpty()) continue;
            uris.add(Uri.parse(item.uri()));
        }
        if (uris.isEmpty()) throw new IOException("这些文件没有媒体库 URI，无法放入系统回收站");
        PendingIntent intent = MediaStore.createTrashRequest(resolver, uris, true);
        if (intent == null) throw new IOException("系统未返回回收站确认框");
        return intent.getIntentSender();
    }
}