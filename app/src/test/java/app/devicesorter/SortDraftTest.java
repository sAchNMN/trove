package app.devicesorter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SortDraftTest {
    @Test public void defaultsMatchAFreshInstall() {
        SortDraft draft = new SortDraft();
        assertTrue(draft.sources.isEmpty());
        assertTrue(draft.selected.isEmpty());
        assertTrue(draft.inPlace);
        assertFalse(draft.move);
        assertFalse(draft.trash);
        assertFalse(draft.active);
        assertTrue(draft.foldersCollapsed);
        assertEquals(SortDraft.SCROLL, draft.viewMode);
        assertEquals(SortDraft.COPY, draft.mode());
        assertEquals(Classification.DEFAULT, draft.rule);
        assertEquals(0, draft.step);
        assertFalse(draft.hasTarget());
    }

    @Test public void modesAreExclusive() {
        SortDraft draft = new SortDraft();
        draft.setMode(SortDraft.MOVE);
        assertTrue(draft.move);
        assertFalse(draft.trash);
        assertEquals(SortDraft.MOVE, draft.mode());

        draft.setMode(SortDraft.TRASH);
        assertFalse(draft.move);
        assertTrue(draft.trash);
        assertEquals(SortDraft.TRASH, draft.mode());

        draft.setMode(SortDraft.COPY);
        assertFalse(draft.move);
        assertFalse(draft.trash);
        assertEquals(SortDraft.COPY, draft.mode());
    }

    @Test public void unknownModeDoesNotStick() {
        SortDraft draft = new SortDraft();
        draft.setMode("delete-everything");
        assertFalse(draft.move);
        assertFalse(draft.trash);
        assertEquals(SortDraft.COPY, draft.mode());
        // A null value from a corrupt settings file is treated the same way.
        draft.setMode(null);
        assertEquals(SortDraft.COPY, draft.mode());
    }

    @Test public void stepIsClampedToTheWizard() {
        SortDraft draft = new SortDraft();
        draft.setStep(-4);
        assertEquals(0, draft.step);
        draft.setStep(99);
        assertEquals(Wizard.STEPS - 1, draft.step);
    }

    @Test public void wizardFlagFollowsViewMode() {
        SortDraft draft = new SortDraft();
        assertFalse(draft.wizard());
        draft.setWizard(true);
        assertTrue(draft.wizard());
        assertEquals(SortDraft.WIZARD, draft.viewMode);
        draft.setWizard(false);
        assertEquals(SortDraft.SCROLL, draft.viewMode);
    }

    @Test public void resetClearsChoicesButKeepsViewPreferences() {
        SortDraft draft = new SortDraft();
        draft.sources.add("content://com.android.externalstorage.documents/tree/primary%3ADCIM");
        draft.selected.add("2024/01");
        draft.knownGroups.add("2024/01");
        draft.knownFolders.add("2024");
        draft.excludedFolders.add("Android/data");
        draft.target = "content://tree/x";
        draft.targetParentId = "x";
        draft.inPlace = false;
        draft.rule = Classification.FLAT;
        draft.setMode(SortDraft.TRASH);
        draft.setStep(2);
        draft.active = true;
        draft.setWizard(true);
        draft.foldersCollapsed = false;

        draft.reset();

        assertFalse(draft.active);
        assertTrue(draft.sources.isEmpty());
        assertTrue(draft.selected.isEmpty());
        assertTrue(draft.knownGroups.isEmpty());
        assertTrue(draft.knownFolders.isEmpty());
        assertTrue(draft.excludedFolders.isEmpty());
        assertEquals("", draft.target);
        assertEquals("", draft.targetParentId);
        assertTrue(draft.inPlace);
        assertEquals(Classification.DEFAULT, draft.rule);
        assertEquals(SortDraft.COPY, draft.mode());
        assertEquals(0, draft.step);
        // "舍弃设置" promises not to touch how the page is displayed.
        assertEquals(SortDraft.WIZARD, draft.viewMode);
        assertFalse(draft.foldersCollapsed);
    }

    @Test public void hasTargetTracksTheTargetString() {
        SortDraft draft = new SortDraft();
        assertFalse(draft.hasTarget());
        draft.target = "content://com.android.externalstorage.documents/tree/primary%3APictures";
        assertTrue(draft.hasTarget());
    }
}