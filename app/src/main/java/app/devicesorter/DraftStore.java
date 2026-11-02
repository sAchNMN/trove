package app.devicesorter;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * SharedPreferences and Bundle round-trip for {@link SortDraft}. The Activity no
 * longer knows which key holds which field: it calls {@link #load} once and
 * {@link #save} whenever something changed, so a new draft field cannot be added
 * to the model and silently forgotten in one of the two save paths.
 */
final class DraftStore {
    private static final String KEY_VIEW_MODE = "viewMode";
    private static final String KEY_STEP = "wizardStep";
    private static final String KEY_FOLDERS_COLLAPSED = "foldersCollapsed";
    private static final String KEY_DRAFT_ACTIVE = "draftActive";
    private static final String KEY_EXEC_MODE = "execMode";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_TARGET = "target";
    private static final String KEY_IN_PLACE = "inPlace";
    private static final String KEY_RULE = "rule";
    private static final String KEY_CUSTOM_RULE = "customRule";
    private static final String KEY_EXCLUDED_FOLDERS = "excludedFolders";
    private static final String KEY_KNOWN_FOLDERS = "knownFolders";
    private static final String KEY_SELECTED = "selectedGroups";
    private static final String KEY_KNOWN_GROUPS = "knownGroups";

    private final SharedPreferences prefs;

    DraftStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Restores the draft. Process-death state wins for the fields it carries
     * (selections, step, mode); sources, target and the rule come from the saved
     * settings so a restored process still opens with its folders.
     */
    void load(SortDraft draft, Bundle state) {
        readArray(prefs.getString(KEY_SOURCES, "[]"), draft.sources);
        draft.target = prefs.getString(KEY_TARGET, "");
        draft.targetParentId = documentId(draft.target);
        draft.inPlace = prefs.getBoolean(KEY_IN_PLACE, true);
        draft.setWizard(SortDraft.WIZARD.equals(prefs.getString(KEY_VIEW_MODE, SortDraft.SCROLL)));
        draft.setStep(prefs.getInt(KEY_STEP, 0));
        draft.foldersCollapsed = prefs.getBoolean(KEY_FOLDERS_COLLAPSED, true);
        draft.active = prefs.getBoolean(KEY_DRAFT_ACTIVE, false);
        draft.setMode(prefs.getString(KEY_EXEC_MODE, SortDraft.COPY));
        String savedRule = prefs.getString(KEY_RULE, Classification.DEFAULT);
        if (Classification.valid(savedRule)) draft.rule = savedRule;
        String savedCustom = prefs.getString(KEY_CUSTOM_RULE, draft.customRule);
        if (Classification.valid(savedCustom)) draft.customRule = savedCustom;

        if (state == null) {
            readSet(prefs, KEY_EXCLUDED_FOLDERS, draft.excludedFolders);
            readSet(prefs, KEY_KNOWN_FOLDERS, draft.knownFolders);
            readSet(prefs, KEY_SELECTED, draft.selected);
            readSet(prefs, KEY_KNOWN_GROUPS, draft.knownGroups);
            return;
        }
        draft.setWizard(SortDraft.WIZARD.equals(state.getString(KEY_VIEW_MODE, draft.viewMode)));
        draft.setStep(state.getInt(KEY_STEP, draft.step));
        draft.active = state.getBoolean(KEY_DRAFT_ACTIVE, draft.active);
        draft.foldersCollapsed = state.getBoolean(KEY_FOLDERS_COLLAPSED, draft.foldersCollapsed);
        draft.setMode(state.getString(KEY_EXEC_MODE, draft.mode()));
        readBundle(state, KEY_SELECTED, draft.selected);
        readBundle(state, KEY_KNOWN_GROUPS, draft.knownGroups);
        readBundle(state, KEY_EXCLUDED_FOLDERS, draft.excludedFolders);
        readBundle(state, KEY_KNOWN_FOLDERS, draft.knownFolders);
    }

    void save(SortDraft draft) {
        prefs.edit()
                .putString(KEY_VIEW_MODE, draft.viewMode)
                .putInt(KEY_STEP, draft.step)
                .putBoolean(KEY_FOLDERS_COLLAPSED, draft.foldersCollapsed)
                .putBoolean(KEY_DRAFT_ACTIVE, draft.active)
                .putString(KEY_EXEC_MODE, draft.mode())
                .putString(KEY_SOURCES, new JSONArray(draft.sources).toString())
                .putString(KEY_TARGET, draft.target)
                .putBoolean(KEY_IN_PLACE, draft.inPlace)
                .putString(KEY_RULE, draft.rule)
                .putString(KEY_EXCLUDED_FOLDERS, new JSONArray(draft.excludedFolders).toString())
                .putString(KEY_KNOWN_FOLDERS, new JSONArray(draft.knownFolders).toString())
                .putString(KEY_SELECTED, new JSONArray(draft.selected).toString())
                .putString(KEY_KNOWN_GROUPS, new JSONArray(draft.knownGroups).toString())
                .apply();
    }

    /** Folder lists change on every discovery run; skip rewriting the whole draft. */
    void saveFolders(SortDraft draft) {
        prefs.edit()
                .putString(KEY_EXCLUDED_FOLDERS, new JSONArray(draft.excludedFolders).toString())
                .putString(KEY_KNOWN_FOLDERS, new JSONArray(draft.knownFolders).toString())
                .apply();
    }

    /** One key each, so tapping a step or the folder header stays cheap. */
    void saveStep(SortDraft draft) {
        prefs.edit().putInt(KEY_STEP, draft.step).apply();
    }

    void saveCollapsed(SortDraft draft) {
        prefs.edit().putBoolean(KEY_FOLDERS_COLLAPSED, draft.foldersCollapsed).apply();
    }

    /** Only the fields a recreated process cannot rebuild from settings. */
    void saveState(Bundle state, SortDraft draft) {
        state.putStringArrayList(KEY_SELECTED, new ArrayList<>(draft.selected));
        state.putStringArrayList(KEY_KNOWN_GROUPS, new ArrayList<>(draft.knownGroups));
        state.putStringArrayList(KEY_EXCLUDED_FOLDERS, new ArrayList<>(draft.excludedFolders));
        state.putStringArrayList(KEY_KNOWN_FOLDERS, new ArrayList<>(draft.knownFolders));
        state.putString(KEY_VIEW_MODE, draft.viewMode);
        state.putInt(KEY_STEP, draft.step);
        state.putBoolean(KEY_DRAFT_ACTIVE, draft.active);
        state.putBoolean(KEY_FOLDERS_COLLAPSED, draft.foldersCollapsed);
        state.putString(KEY_EXEC_MODE, draft.mode());
    }

    private static void readSet(SharedPreferences prefs, String key, Set<String> into) {
        readArray(prefs.getString(key, "[]"), into);
    }

    private static void readArray(String json, Collection<String> into) {
        into.clear();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) into.add(array.getString(i));
        } catch (Exception ignored) {
            into.clear();
        }
    }

    private static void readBundle(Bundle state, String key, Set<String> into) {
        ArrayList<String> values = state.getStringArrayList(key);
        if (values != null) into.addAll(values);
    }

    private static String documentId(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        try {
            return Storage.id(Uri.parse(uri));
        } catch (Exception e) {
            return "";
        }
    }
}