package app.devicesorter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Finds byte-identical media. Size is a cheap prefilter; SHA-256 decides. */
final class Duplicates {
    private Duplicates() {}

    record Group(String hash, long size, List<MediaItem> items) {
        long wasted() {
            return Math.max(0, size) * Math.max(0, items.size() - 1);
        }

        /** Largest count sharing one file name; content is still the sole delete criterion. */
        int sameNameCount() {
            Map<String, Integer> counts = new HashMap<>();
            int best = 0;
            for (MediaItem item : items) {
                int count = counts.merge(item.name, 1, Integer::sum);
                best = Math.max(best, count);
            }
            return best;
        }

        JSONObject json() throws JSONException {
            JSONArray array = new JSONArray();
            for (MediaItem item : items) array.put(item.json());
            return new JSONObject().put("hash", hash).put("size", size).put("items", array);
        }

        static Group from(JSONObject object) throws JSONException {
            List<MediaItem> items = new ArrayList<>();
            JSONArray array = object.getJSONArray("items");
            for (int i = 0; i < array.length(); i++) items.add(MediaItem.from(array.getJSONObject(i)));
            return new Group(object.getString("hash"), object.getLong("size"),
                    java.util.Collections.unmodifiableList(items));
        }
    }

    record Result(List<Group> groups, int compared, int skipped) {}

    interface Hash {
        String hash(MediaItem item) throws IOException;
    }

    static Result find(List<MediaItem> items, Hash hasher, BooleanSupplier cancelled, IntConsumer progress)
            throws IOException {
        Map<Long, List<MediaItem>> bySize = new LinkedHashMap<>();
        for (MediaItem item : items) {
            if (item.size <= 0) continue;
            bySize.computeIfAbsent(item.size, key -> new ArrayList<>()).add(item);
        }
        int total = 0;
        for (List<MediaItem> group : bySize.values()) if (group.size() > 1) total += group.size();
        List<Group> groups = new ArrayList<>();
        int done = 0, compared = 0, skipped = 0;
        for (Map.Entry<Long, List<MediaItem>> entry : bySize.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            Map<String, List<MediaItem>> byHash = new HashMap<>();
            for (MediaItem item : entry.getValue()) {
                if (cancelled.getAsBoolean()) throw new IOException("查重已取消");
                String hash = null;
                try {
                    hash = hasher.hash(item);
                } catch (IOException | RuntimeException ignored) {
                    skipped++;
                }
                if (hash != null && !hash.isEmpty()) {
                    byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(item);
                    compared++;
                }
                done++;
                if (progress != null) progress.accept(total == 0 ? 0 : done * 100 / total);
            }
            for (Map.Entry<String, List<MediaItem>> same : byHash.entrySet()) {
                if (same.getValue().size() < 2) continue;
                long size = same.getValue().get(0).size;
                groups.add(new Group(same.getKey(), size,
                        java.util.Collections.unmodifiableList(new ArrayList<>(same.getValue()))));
            }
        }
        groups.sort(Comparator.comparingLong(Group::wasted).reversed()
                .thenComparing(Group::hash));
        return new Result(java.util.Collections.unmodifiableList(groups), compared, skipped);
    }

    static List<MediaItem> trashable(Group group) {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : group.items()) if (item.trashable()) result.add(item);
        return result;
    }
}