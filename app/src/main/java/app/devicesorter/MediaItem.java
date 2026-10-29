package app.devicesorter;

import org.json.JSONException;
import org.json.JSONObject;

final class MediaItem {
    final String uri, name, path, mime, model, issue;
    final long size, modified;
    final boolean video;

    MediaItem(String uri, String name, String path, String mime, long size, long modified,
              boolean video, String model, String issue) {
        this.uri = uri;
        this.name = name;
        this.path = path;
        this.mime = mime;
        this.size = size;
        this.modified = modified;
        this.video = video;
        this.model = model;
        this.issue = issue;
    }

    JSONObject json() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("uri", uri).put("name", name).put("path", path).put("mime", mime);
        result.put("size", size).put("modified", modified).put("video", video);
        result.put("model", model == null ? JSONObject.NULL : model).put("issue", issue);
        return result;
    }

    static MediaItem from(JSONObject item) throws JSONException {
        return new MediaItem(item.getString("uri"), item.getString("name"), item.getString("path"),
                item.getString("mime"), item.getLong("size"), item.getLong("modified"),
                item.getBoolean("video"), item.isNull("model") ? null : item.getString("model"),
                item.optString("issue"));
    }
}
