package rw.plasmaco.ops.api

/** Entity type names used on the wire (push operations, pull changes) and in audit logs, on device and server. */
object WireEntity {
    const val REPORT = "report"
    const val REPORT_ACTION = "report_action"
    const val REPORT_VERSION = "report_version"
    const val APPROVAL = "approval"
    const val TXN = "inventory_txn"
    const val STOCK_COUNT = "stock_count"
    const val PRODUCT = "product"
    const val MATERIAL = "material"
    const val LINE = "line"
    const val MACHINE = "machine"
    const val SHIFT = "shift"
    const val DOWNTIME_CATEGORY = "downtime_category"
    const val DEFECT_CATEGORY = "defect_category"
    const val THRESHOLD = "threshold"
    const val TARGET = "target"
    const val RUN_RATE = "run_rate"
    const val USER = "user"
    const val IMPORT = "import_batch"
    const val AUDIT = "audit"

    /** Reference data every signed-in device receives. */
    val MASTER: Set<String> = setOf(PRODUCT, MATERIAL, LINE, MACHINE, SHIFT, DOWNTIME_CATEGORY, DEFECT_CATEGORY, THRESHOLD, TARGET, RUN_RATE)
}

/** Push operation outcomes (PushResult.status). */
object PushStatus {
    const val APPLIED = "APPLIED"
    const val DUPLICATE = "DUPLICATE"
    const val CONFLICT = "CONFLICT"
    const val REJECTED = "REJECTED"
}
