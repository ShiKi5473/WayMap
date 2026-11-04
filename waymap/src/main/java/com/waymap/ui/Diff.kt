package com.waymap.ui

/** What changed between the items currently drawn and the next list. */
internal data class IdDiff<T>(
    /** Ids present only in the next list, in its order. */
    val added: List<T>,
    /** Same id in both, but the item is no longer equal. */
    val changed: List<Pair<T, T>>,
    /** Ids drawn now but absent from the next list. */
    val removed: List<String>,
)

/**
 * Diffs [current] (id → item as last drawn) against [next], matching items by [id].
 * Unchanged items appear in no list. Duplicate ids in [next]: the last one wins.
 */
internal fun <T> diffById(current: Map<String, T>, next: List<T>, id: (T) -> String): IdDiff<T> {
    val nextById = LinkedHashMap<String, T>(next.size)
    for (item in next) nextById[id(item)] = item

    val added = ArrayList<T>()
    val changed = ArrayList<Pair<T, T>>()
    for ((key, item) in nextById) {
        val old = current[key]
        when {
            old == null -> added += item
            old != item -> changed += old to item
        }
    }
    val removed = current.keys.filter { it !in nextById }
    return IdDiff(added, changed, removed)
}
