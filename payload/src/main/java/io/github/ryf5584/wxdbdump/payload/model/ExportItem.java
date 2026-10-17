package io.github.ryf5584.wxdbdump.payload.model;

/** One file in an export run and its current state. */
public final class ExportItem {

    public enum Status {
        QUEUED("排队中"),
        SNAPSHOTTING("生成快照"),
        RUNNING("导出中"),
        DONE("已完成"),
        SKIPPED("已跳过"),
        FAILED("失败");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    public final DbFile db;
    public Status status = Status.QUEUED;
    /** Bytes copied so far. */
    public long copiedBytes;
    /** Current stage label, e.g. WCDB backup or copy trio. */
    public String stage = "";
    /** Failure or skip reason. Always surfaced in the UI. */
    public String reason = "";
    /** Whether the result passed the post-export integrity check. */
    public boolean verified;
    /** Display name of the output file. */
    public String outputName;

    public ExportItem(DbFile db, String outputName) {
        this.db = db;
        this.outputName = outputName;
    }

    public int progressPercent() {
        long total = db.sizeBytes;
        if (total <= 0) return status == Status.DONE ? 100 : 0;
        return (int) Math.min(100, copiedBytes * 100 / total);
    }
}
