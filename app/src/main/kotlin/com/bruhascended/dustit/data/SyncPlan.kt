package com.bruhascended.dustit.data

/** What has to change in the local mirror to match MediaStore. */
data class SyncPlan(
    /** New rows, plus existing rows whose metadata differs. */
    val upserts: List<MediaEntity>,
    /** Rows whose pixels may have changed (edited in place), so their classification is stale. */
    val contentChangedIds: List<Long>,
    /** Rows no longer in MediaStore. */
    val removedIds: List<Long>,
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && removedIds.isEmpty()
}

fun planSync(local: List<MediaEntity>, mediaStore: List<MediaEntity>): SyncPlan {
    val localById = local.associateBy { it.id }
    val seen = HashSet<Long>(mediaStore.size)
    val upserts = ArrayList<MediaEntity>()
    val contentChanged = ArrayList<Long>()
    for (current in mediaStore) {
        seen += current.id
        val previous = localById[current.id]
        if (previous == current) continue
        upserts += current
        if (previous != null && (previous.dateModified != current.dateModified || previous.size != current.size)) {
            contentChanged += current.id
        }
    }
    val removed = local.mapNotNull { if (it.id in seen) null else it.id }
    return SyncPlan(upserts, contentChanged, removed)
}
