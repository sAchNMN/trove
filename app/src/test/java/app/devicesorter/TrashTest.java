package app.devicesorter;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TrashTest {
    private Trash.Item item(String name) {
        return new Trash.Item("content://media/external/images/media/1", name,
                "内部存储/Pictures/" + name, "内部存储/DCIM/Camera/" + name, 10, "hash");
    }

    @Test public void completeOnlyWhenEveryPlannedCopySucceeded() {
        Trash.Pending complete = new Trash.Pending("复制后", "内部存储/DCIM/Camera", 3, 3, 0,
                List.of(item("a.jpg"), item("b.jpg"), item("c.jpg")));
        assertTrue(complete.complete());

        assertFalse(new Trash.Pending("复制后", "目标", 3, 2, 1,
                List.of(item("a.jpg"), item("b.jpg"))).complete());
        assertFalse(new Trash.Pending("复制后", "目标", 3, 2, 0,
                List.of(item("a.jpg"), item("b.jpg"))).complete());
        assertFalse(new Trash.Pending("复制后", "目标", 0, 0, 0, List.of()).complete());
    }

    @Test public void pendingKeepsUserReadableSourceAndTarget() {
        Trash.Pending pending = new Trash.Pending("放回 DCIM/Camera", "内部存储/DCIM/Camera",
                1, 1, 0, List.of(item("a.jpg")));
        assertEquals("内部存储/Pictures/a.jpg", pending.items().get(0).source());
        assertEquals("内部存储/DCIM/Camera/a.jpg", pending.items().get(0).target());
    }
}