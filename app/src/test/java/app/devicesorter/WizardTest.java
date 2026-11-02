package app.devicesorter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WizardTest {
    @Test public void scanIsRequiredBeforeClassification() {
        assertTrue(Wizard.blockNext(0, false, false, true, false).contains("扫描"));
        assertNull(Wizard.blockNext(0, true, false, true, false));
    }

    @Test public void selectionIsRequiredBeforeExecution() {
        assertTrue(Wizard.blockNext(1, true, false, true, false).contains("勾选"));
        assertNull(Wizard.blockNext(1, true, true, true, false));
    }

    @Test public void independentTargetIsRequiredForExecution() {
        assertTrue(Wizard.blockNext(2, true, true, false, false).contains("目标目录"));
        assertNull(Wizard.blockNext(2, true, true, false, true));
        assertNull(Wizard.blockNext(2, true, true, true, false));
    }

    @Test public void startLabelFollowsExecutionMode() {
        assertEquals("开始文件移动", Wizard.startLabel(true, false));
        assertEquals("复制后移入回收站", Wizard.startLabel(false, true));
        assertEquals("开始复制", Wizard.startLabel(false, false));
    }

    @Test public void stepLabelsStayInsideRange() {
        assertEquals("选择整理目录", Wizard.title(-5));
        assertEquals("执行与开始", Wizard.title(99));
        assertEquals("第 2 / 3 步", Wizard.badge(1));
        // Half-width buttons on a 347dp screen clipped "下一步：执行与开始"; the step
        // header already names the next step, so the button stays short.
        assertEquals("下一步", Wizard.nextLabel(0, false, false));
        assertEquals("下一步", Wizard.nextLabel(1, false, false));
        assertEquals("开始复制", Wizard.nextLabel(2, false, false));
    }
}