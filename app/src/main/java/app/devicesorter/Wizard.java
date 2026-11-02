package app.devicesorter;

/**
 * Pure copy and gating rules for the step-by-step view. Keeping them out of the
 * Activity makes it possible to test the guidance without an emulator, and gives
 * future features a reusable three-step shell.
 */
final class Wizard {
    static final int STEPS = 3;

    static final String[] TITLES = {
            "选择整理目录",
            "分类与预览",
            "执行与开始"
    };

    static final String[] HINTS = {
            "授权并扫描手机媒体，选择就地整理或独立目标目录。",
            "选择分类策略，勾选这次要处理的目录。",
            "选择复制、移动或复制后清理，核对摘要后开始。"
    };

    private Wizard() { }

    static String title(int step) {
        return TITLES[clamp(step)];
    }

    static String hint(int step) {
        return HINTS[clamp(step)];
    }

    static String badge(int step) {
        return "第 " + (clamp(step) + 1) + " / " + STEPS + " 步";
    }

    /**
     * Why the user cannot leave {@code step} yet, or null when the next step is
     * safe. Scanned media and a selected group are required; the target check is
     * only meaningful for an independent destination.
     */
    static String blockNext(int step, boolean scanned, boolean hasSelection, boolean inPlace,
                            boolean hasTarget) {
        switch (clamp(step)) {
            case 0:
                if (!scanned) return "请先扫描手机媒体，看到文件后再进入分类";
                return null;
            case 1:
                if (!scanned) return "还没有扫描结果，请先回到第 1 步扫描";
                if (!hasSelection) return "请至少勾选一个分类目录";
                return null;
            default:
                return startBlocker(scanned, hasSelection, inPlace, hasTarget);
        }
    }

    /** Same conditions, phrased for the final start button. */
    static String startBlocker(boolean scanned, boolean hasSelection, boolean inPlace,
                               boolean hasTarget) {
        if (!scanned) return "还没有扫描结果，请先回到第 1 步扫描";
        if (!hasSelection) return "请至少勾选一个分类目录";
        if (!inPlace && !hasTarget) return "请先选择独立目标目录，或改为就地分类";
        return null;
    }

    static String nextLabel(int step, boolean move, boolean trash) {
        switch (clamp(step)) {
            // Keep the label short enough for a half-width button at fontScale 1.3;
            // the step header and the blocker line explain what comes next.
            case 0:
            case 1: return "下一步";
            default: return startLabel(move, trash);
        }
    }

    static String startLabel(boolean move, boolean trash) {
        if (move) return "开始文件移动";
        if (trash) return "复制后移入回收站";
        return "开始复制";
    }

    private static int clamp(int step) {
        return Math.max(0, Math.min(STEPS - 1, step));
    }
}