package app.devicesorter;

/**
 * Wording for the confirmation dialog before a batch runs.
 *
 * Kept out of {@link MainActivity} so the copy/move/trash distinctions can be
 * unit tested without an Android runtime.
 */
final class ConfirmText {
    private ConfirmText() {}

    static String title(boolean moving, int limit) {
        if (moving) return "确认移动原文件";
        return limit > 0 ? "小批量试跑" : "确认批量复制";
    }

    static String title(boolean moving, boolean trash, int limit) {
        if (moving) return "确认移动原文件";
        if (limit > 0) return "小批量试跑";
        return trash ? "确认复制（完成后询问回收站）" : "确认批量复制";
    }

    static String body(String targetLabel, String strategyTitle, int count, int assigned, int pending,
                       boolean moving, int limit, boolean moveSelected) {
        return body(targetLabel, strategyTitle, count, assigned, pending, moving, limit, moveSelected, false);
    }

    static String body(String targetLabel, String strategyTitle, int count, int assigned, int pending,
                       boolean moving, int limit, boolean moveSelected, boolean trashSelected) {
        StringBuilder text = new StringBuilder()
                .append("目标：").append(targetLabel)
                .append("\n策略：").append(strategyTitle)
                .append("\n本次：").append(count).append(" 个文件（人工指定 ").append(assigned).append(" 个）")
                .append("\n待确认保留原处：").append(pending).append(" 个")
                .append("\n\n");
        if (moving) {
            text.append("请求同卷系统移动，移动前后校验内容。不支持、未授权或重名时保留原处；")
                    .append("结果不确定立即停止。系统接口不保证厂商底层零拷贝或断电安全。");
        } else if (limit > 0) {
            text.append("每个目录最多复制 ").append(limit).append(" 张照片和 ")
                    .append(limit).append(" 段视频，原件保留。");
            if (moveSelected) {
                text.append("\n\n注意：当前执行方式是「系统移动」，但试跑只复制、不会移动原件。")
                        .append("确认结果无误后，请改用「开始文件移动」。");
            }
            if (trashSelected) {
                text.append("\n\n注意：试跑只复制，不会把原件移入回收站；确认结果无误后，")
                        .append("再改用「复制后移入回收站」。");
            }
        } else if (trashSelected) {
            text.append("先复制并逐个做 SHA-256 校验。只有本次全部文件复制成功，")
                    .append("才会询问是否把原件移入系统回收站；任一文件失败或取消，都不会移入回收站。")
                    .append("\n\n回收站可恢复，App 不会直接删除，也不会清空回收站。");
        } else {
            text.append("复制所选文件，原件保留。请确保目标有足够空间。");
        }
        text.append("\n\n").append(moving ? "重名时保留原处，不覆盖已有文件。" : "重名自动加序号。");
        return text.toString();
    }
}