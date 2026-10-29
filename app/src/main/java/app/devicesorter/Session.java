package app.devicesorter;

import android.content.Context;
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

final class Session {
    static volatile boolean running;
    static volatile boolean cancel;
    static volatile String status = "尚未扫描";
    static volatile List<MediaItem> items = java.util.Collections.emptyList();
    static volatile int revision;

    static File scanFile(Context context) {
        return new File(context.getFilesDir(), "scan.json");
    }

    static void saveScan(Context context, List<MediaItem> items) throws IOException, JSONException {
        JSONArray array = new JSONArray();
        for (MediaItem item : items) array.put(item.json());
        AtomicFile file = new AtomicFile(scanFile(context));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException e) {
            file.failWrite(stream);
            throw e;
        }
        Session.items = java.util.Collections.unmodifiableList(new ArrayList<>(items));
        revision++;
    }

    static void load(Context context) {
        if (running || !items.isEmpty()) return;
        try {
            AtomicFile file = new AtomicFile(scanFile(context));
            JSONArray array = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            if (array.length() == 0) return;
            List<MediaItem> loaded = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) loaded.add(MediaItem.from(array.getJSONObject(i)));
            items = java.util.Collections.unmodifiableList(loaded);
            status = "已恢复上次扫描，请确认原文件未被其他应用修改";
            revision++;
        } catch (IOException | JSONException ignored) {
            items = java.util.Collections.emptyList();
        }
    }

    static synchronized File latestLog(Context context) {
        String path = context.getSharedPreferences("settings", 0).getString("log", "");
        File file = new File(context.getFilesDir(), path);
        return path.isEmpty() || !file.isFile() ? null : file;
    }

    static final class Journal implements AutoCloseable {
        private final FileOutputStream stream;

        Journal(Context context) throws IOException {
            File folder = new File(context.getFilesDir(), "logs");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法建立操作日志");
            String relative = "logs/" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".jsonl";
            stream = new FileOutputStream(new File(context.getFilesDir(), relative));
            if (!context.getSharedPreferences("settings", 0).edit().putString("log", relative).commit()) {
                stream.close();
                throw new IOException("无法保存日志索引");
            }
        }

        synchronized void write(JSONObject object) throws IOException {
            stream.write((object + "\n").getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.getFD().sync();
        }

        @Override public void close() throws IOException { stream.close(); }
    }

    static JSONObject event(String status, MediaItem item, String target, String hash, String detail) {
        try {
            JSONObject object = new JSONObject();
            object.put("time", java.time.Instant.now().toString()).put("status", status);
            if (item != null) object.put("source", item.json());
            object.put("target", target).put("sha256", hash).put("detail", detail);
            return object;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
