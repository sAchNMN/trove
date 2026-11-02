package app.devicesorter;

import android.content.Context;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent metadata index for MediaStore items.
 *
 * Querying the MediaStore cursor is cheap. Opening every JPEG/MP4 and parsing
 * EXIF/QuickTime is not: it is the part that turned every automatic rescan into
 * minutes of work. This cache keeps the parsed result keyed by the MediaStore
 * URI together with the size/date_modified stamp that was current when the
 * metadata was read. A file whose stamp did not change is never opened again.
 *
 * The cache is only an optimisation. Every file operation still re-stats its
 * source before copying or moving, so a stale cache can never silently move the
 * wrong bytes.
 */
final class MediaCache {
    /** Bump when metadata extraction changes, so old entries are re-read once. */
    private static final int VERSION = 1;
    private static final String CACHE_FILE = "media-cache.json";
    private static final String PREFS = "settings";
    private static final String STATE_KEY = "mediaSyncState";

    /** One cached item plus the MediaStore stamp used to decide whether it is still valid. */
    record Entry(MediaItem item, long mediaSize, long mediaModified) {}

    static final class Data {
        final Map<String, Entry> entries = new LinkedHashMap<>();
    }

    /** Small state kept in preferences, so checking freshness never parses the whole cache. */
    static final class State {
        long syncedAt;
        boolean complete;
        boolean fullPermission;
        String grants = "";
        final Map<String, Long> generations = new LinkedHashMap<>();
    }

    private static final Object LOCK = new Object();
    private static Data memory;
    private static long memoryModified = -1;
    private static long memoryLength = -1;

    private MediaCache() {}

    static String mediaKey(android.net.Uri uri) {
        return "m:" + uri;
    }

    static Data load(Context context) {
        synchronized (LOCK) {
            File file = cacheFile(context);
            long modified = file.lastModified(), length = file.length();
            if (memory != null && modified == memoryModified && length == memoryLength) return memory;
            Data data = new Data();
            if (file.isFile()) {
                try {
                    AtomicFile atomic = new AtomicFile(file);
                    JSONObject root = new JSONObject(
                            new String(atomic.readFully(), StandardCharsets.UTF_8));
                    if (root.optInt("version") == VERSION) {
                        JSONArray list = root.optJSONArray("entries");
                        if (list != null) {
                            for (int i = 0; i < list.length(); i++) {
                                JSONObject object = list.getJSONObject(i);
                                data.entries.put(object.getString("key"), decode(object));
                            }
                        }
                    }
                } catch (IOException | JSONException | RuntimeException ignored) {
                    // A damaged cache only costs one metadata rebuild; never fail the scan.
                    data.entries.clear();
                }
            }
            memory = data;
            memoryModified = modified;
            memoryLength = length;
            return data;
        }
    }

    static void save(Context context, Map<String, Entry> entries, State state) throws IOException {
        JSONArray list = new JSONArray();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            try {
                list.put(encode(entry.getKey(), entry.getValue()));
            } catch (JSONException e) {
                throw new IOException("无法建立媒体索引", e);
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION).put("entries", list);
        } catch (JSONException e) {
            throw new IOException("无法建立媒体索引", e);
        }
        File file = cacheFile(context);
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException e) {
            atomic.failWrite(stream);
            throw e;
        }
        synchronized (LOCK) {
            // Force the next load() to read the file we just wrote.
            memory = null;
            memoryModified = file.lastModified();
            memoryLength = file.length();
        }
        saveState(context, state);
    }

    static List<MediaItem> cachedItems(Context context) {
        List<MediaItem> items = new ArrayList<>();
        for (Entry entry : load(context).entries.values()) items.add(entry.item());
        return items;
    }

    /** True when MediaStore reports no change (and folder grants did not change) since the last sync. */
    static boolean fresh(Context context) {
        State state = state(context);
        if (state.syncedAt == 0) return false;
        // Moving from "selected photos" to "allow all" changes what the query can
        // see without necessarily changing the volume generation.
        if (state.fullPermission != MediaDiscovery.full(context)) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            Map<String, Long> current = generations(context);
            if (current.isEmpty() || !current.equals(state.generations)) return false;
        } else if (System.currentTimeMillis() - state.syncedAt > 60_000L) {
            // API 28/29 has no generation counter; a minute is short enough to notice
            // new photos while still avoiding a query on every resume.
            return false;
        }
        try {
            return Storage.grants(context.getContentResolver()).signature().equals(state.grants);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean hasBaseline(Context context) {
        return state(context).syncedAt != 0;
    }

    /** A restored cache must be a complete view; partial results may contain hidden entries. */
    static boolean canRestore(Context context) {
        State state = state(context);
        return state.syncedAt != 0 && state.complete;
    }

    static State state(Context context) {
        State state = new State();
        try {
            JSONObject root = new JSONObject(
                    context.getSharedPreferences(PREFS, 0).getString(STATE_KEY, "{}"));
            state.syncedAt = root.optLong("at");
            state.complete = root.optBoolean("complete");
            state.fullPermission = root.optBoolean("fullPermission");
            state.grants = root.optString("grants");
            JSONObject generations = root.optJSONObject("generations");
            if (generations != null) {
                for (Iterator<String> keys = generations.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    state.generations.put(key, generations.optLong(key));
                }
            }
        } catch (JSONException | RuntimeException ignored) {
            state.syncedAt = 0;
        }
        return state;
    }

    static void saveState(Context context, State state) {
        JSONObject generations = new JSONObject();
        try {
            for (Map.Entry<String, Long> entry : state.generations.entrySet()) {
                generations.put(entry.getKey(), entry.getValue());
            }
            JSONObject root = new JSONObject()
                    .put("at", state.syncedAt)
                    .put("complete", state.complete)
                    .put("fullPermission", state.fullPermission)
                    .put("grants", state.grants)
                    .put("generations", generations);
            context.getSharedPreferences(PREFS, 0).edit().putString(STATE_KEY, root.toString()).apply();
        } catch (JSONException ignored) {
            // Preferences are a convenience; the cache file itself is still valid.
        }
    }

    /** MediaStore reports one generation counter per external volume (API 30+). */
    static Map<String, Long> generations(Context context) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT < 30) return result;
        try {
            for (String volume : MediaStore.getExternalVolumeNames(context)) {
                try {
                    result.put(volume, MediaStore.getGeneration(context, volume));
                } catch (RuntimeException ignored) {
                    // An unmounted/denied volume must not hide the others.
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through with an empty map; callers treat that as "not fresh".
        }
        return result;
    }

    static boolean sameContent(Entry entry, MediaDiscovery.Candidate candidate) {
        return entry != null && sameContent(entry.item(), entry.mediaSize(), entry.mediaModified(),
                candidate.size(), candidate.modified(), candidate.mime(), candidate.video(),
                candidate.width(), candidate.height(), candidate.duration());
    }

    /**
     * A file is unchanged when its MediaStore size, date_modified and media shape
     * are identical. The folder path is deliberately not part of this check:
     * moving a file does not change its EXIF, so a move must not trigger a reread.
     * The cached stamp is compared, never the cached item's own size/modified:
     * when a SAF document is available those come from the provider in
     * milliseconds while MediaStore stores whole seconds.
     */
    static boolean sameContent(MediaItem item, long cachedSize, long cachedModified,
                               long size, long modified, String mime, boolean video,
                               int width, int height, long duration) {
        return item != null
                && cachedSize == size
                && cachedModified == modified
                && item.video == video
                && item.width == width
                && item.height == height
                && item.duration == duration
                && (item.mime == null ? mime == null : item.mime.equals(mime));
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static JSONObject encode(String key, Entry entry) throws JSONException {
        return new JSONObject().put("key", key)
                .put("size", entry.mediaSize())
                .put("modified", entry.mediaModified())
                .put("item", entry.item().json());
    }

    private static Entry decode(JSONObject object) throws JSONException {
        return new Entry(MediaItem.from(object.getJSONObject("item")),
                object.optLong("size", -1), object.optLong("modified", 0));
    }
}