package app.devicesorter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Everything the user has chosen on the sort page — and nothing about views or
 * running jobs. Keeping it in one plain object is what lets the draft be saved,
 * restored and reset without an Activity, and guarantees the step panels, the
 * preview and the executor all read the same state instead of three copies.
 *
 * <p>Field names are intentionally the same words the UI uses (sources, target,
 * rule, step…); screens read {@code draft.sources} directly, no getters.
 */
final class SortDraft {
    static final String SCROLL = "scroll", WIZARD = "wizard";
    static final String COPY = "copy", MOVE = "move", TRASH = "trash";

    /** Source tree URIs, in the order the user authorised them. */
    final ArrayList<String> sources = new ArrayList<>();
    /** Checked classification folders; empty means "nothing selected yet". */
    final Set<String> selected = new HashSet<>();
    final Set<String> knownGroups = new HashSet<>();
    final Set<String> excludedFolders = new HashSet<>();
    final Set<String> knownFolders = new HashSet<>();

    String target = "", targetParentId = "";
    String rule = Classification.DEFAULT;
    String customRule = "month/device/type";
    String viewMode = SCROLL;
    int step;
    boolean inPlace = true;
    boolean move;
    boolean trash;
    /** True once the user changed something; drives "未完成设置" on the hub. */
    boolean active;
    boolean foldersCollapsed = true;

    String mode() {
        return move ? MOVE : trash ? TRASH : COPY;
    }

    /** Modes are exclusive; anything unknown falls back to the copy default. */
    void setMode(String mode) {
        move = MOVE.equals(mode);
        trash = !move && TRASH.equals(mode);
    }

    boolean wizard() {
        return WIZARD.equals(viewMode);
    }

    void setWizard(boolean on) {
        viewMode = on ? WIZARD : SCROLL;
    }

    void setStep(int value) {
        step = Math.max(0, Math.min(Wizard.STEPS - 1, value));
    }

    boolean hasTarget() {
        return !target.isEmpty();
    }

    /**
     * "舍弃设置": clears the choices, but keeps the scanned library, the view mode
     * and the collapse state — exactly what the confirmation dialog promises.
     */
    void reset() {
        active = false;
        sources.clear();
        target = "";
        targetParentId = "";
        inPlace = true;
        rule = Classification.DEFAULT;
        selected.clear();
        knownGroups.clear();
        excludedFolders.clear();
        knownFolders.clear();
        move = false;
        trash = false;
        step = 0;
    }
}