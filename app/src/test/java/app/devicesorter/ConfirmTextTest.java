package app.devicesorter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The trial always copies; a selected move must be called out, never implied. */
public class ConfirmTextTest {
    private static String body(boolean moving, int limit, boolean moveSelected) {
        return ConfirmText.body("各来源目录/分类", "设备 / 拍摄年月", 3, 0, 9, moving, limit, moveSelected);
    }

    @Test public void trialCopySaysOriginalsAreKept() {
        String text = body(false, 3, false);
        assertTrue(text, text.contains("每个目录最多复制 3 张照片和 3 段视频，原件保留。"));
        assertTrue(text, text.contains("重名自动加序号"));
        assertFalse(text, text.contains("注意："));
        assertEquals("小批量试跑", ConfirmText.title(false, 3));
    }

    @Test public void trialWhileMoveSelectedSaysItOnlyCopies() {
        String text = body(false, 3, true);
        assertTrue(text, text.contains("当前执行方式是「系统移动」，但试跑只复制、不会移动原件"));
        assertTrue(text, text.contains("请改用「开始文件移动」"));
        // It still copies, so the promise about originals stays true.
        assertTrue(text, text.contains("原件保留。"));
        assertFalse(text, text.contains("原件不保留"));
        assertEquals("小批量试跑", ConfirmText.title(false, 3));
    }

    @Test public void bulkCopySaysOriginalsAreKept() {
        String text = body(false, 0, false);
        assertTrue(text, text.contains("复制所选文件，原件保留"));
        assertTrue(text, text.contains("重名自动加序号"));
        assertFalse(text, text.contains("最多复制"));
        assertEquals("确认批量复制", ConfirmText.title(false, 0));
    }

    @Test public void bulkMoveWarnsAboutNativeMove() {
        String text = body(true, 0, true);
        assertTrue(text, text.contains("请求同卷系统移动"));
        assertTrue(text, text.contains("重名时保留原处"));
        assertFalse(text, text.contains("原件保留。"));
        assertFalse(text, text.contains("自动加序号"));
        assertEquals("确认移动原文件", ConfirmText.title(true, 0));
    }

    @Test public void countsAreRendered() {
        String text = body(false, 0, false);
        assertTrue(text, text.contains("本次：3 个文件（人工指定 0 个）"));
        assertTrue(text, text.contains("待确认保留原处：9 个"));
    }
}