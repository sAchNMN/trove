package app.devicesorter;

import org.json.JSONException;
import org.json.JSONObject;

final class MediaItem {
    final String uri, name, path, mime, model, issue;
    final long size, modified;
    final boolean video;
    final String capturedAt, metadataSource, parameters, manualFolder;
    final String parentId;
    /** MediaStore item URI when this file came from the media library; empty for SAF-only scans. */
    final String mediaUri;
    final int width, height;
    final long duration;

    MediaItem(String uri, String name, String path, String mime, long size, long modified,
              boolean video, String model, String issue) {
        this(uri, name, path, mime, size, modified, video, model, issue, "", "", "", "");
    }

    MediaItem(String uri, String name, String path, String mime, long size, long modified,
              boolean video, String model, String issue, String capturedAt, String metadataSource,
              String parameters, String manualFolder) {
        this(uri, name, path, mime, size, modified, video, model, issue, capturedAt,
                metadataSource, parameters, manualFolder, "");
    }

    MediaItem(String uri, String name, String path, String mime, long size, long modified,
              boolean video, String model, String issue, String capturedAt, String metadataSource,
              String parameters, String manualFolder, String parentId) {
        this(uri, name, path, mime, size, modified, video, model, issue, capturedAt,
                metadataSource, parameters, manualFolder, parentId, "", 0, 0, 0);
    }

    MediaItem(String uri, String name, String path, String mime, long size, long modified,
              boolean video, String model, String issue, String capturedAt, String metadataSource,
              String parameters, String manualFolder, String parentId, String mediaUri,
              int width, int height, long duration) {
        this.uri = uri;
        this.name = name;
        this.path = path;
        this.mime = mime;
        this.size = size;
        this.modified = modified;
        this.video = video;
        this.model = model;
        this.issue = issue;
        this.capturedAt = capturedAt;
        this.metadataSource = metadataSource;
        this.parameters = parameters;
        this.manualFolder = manualFolder;
        this.parentId = parentId;
        this.mediaUri = mediaUri == null ? "" : mediaUri;
        this.width = width;
        this.height = height;
        this.duration = duration;
    }

    MediaItem withFolder(String folder) {
        return new MediaItem(uri, name, path, mime, size, modified, video, model, issue,
                capturedAt, metadataSource, parameters, folder, parentId, mediaUri,
                width, height, duration);
    }

    /** True when the system recycle bin can address this file by its MediaStore URI. */
    boolean trashable() {
        return !mediaUri.isEmpty();
    }

    JSONObject json() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("uri", uri).put("name", name).put("path", path).put("mime", mime);
        result.put("size", size).put("modified", modified).put("video", video);
        result.put("model", model == null ? JSONObject.NULL : model).put("issue", issue);
        result.put("capturedAt", capturedAt).put("metadataSource", metadataSource)
                .put("parameters", parameters).put("manualFolder", manualFolder).put("parentId", parentId)
                .put("mediaUri", mediaUri).put("width", width).put("height", height)
                .put("duration", duration);
        return result;
    }

    static MediaItem from(JSONObject item) throws JSONException {
        return new MediaItem(item.getString("uri"), item.getString("name"), item.getString("path"),
                item.getString("mime"), item.getLong("size"), item.getLong("modified"),
                item.getBoolean("video"), item.isNull("model") ? null : item.getString("model"),
                item.optString("issue"), item.optString("capturedAt"), item.optString("metadataSource"),
                item.optString("parameters"), item.optString("manualFolder"), item.optString("parentId"),
                item.optString("mediaUri"), item.optInt("width"), item.optInt("height"),
                item.optLong("duration"));
    }
}