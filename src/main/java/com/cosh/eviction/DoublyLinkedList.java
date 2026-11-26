package com.cosh.eviction;

/**
 * Intrusive doubly linked list of {@link CacheEntry} nodes with head/tail sentinels.
 *
 * <p>All operations are O(1): sentinels remove every null/edge-case branch from
 * insert and unlink, and entries carry their own {@code prev}/{@code next} pointers.
 *
 * <p><b>Not thread-safe by design.</b> The list is always mutated under the owning
 * {@code PluggableCache}'s lock, which also guards the map/list pair as one unit.
 * An earlier version had its own internal lock. That made each pointer update atomic on its own,
 * but not the map+list update together, and that gap caused a lost-update race (see
 * ENGINEERING_DEEP_DIVE.md, "Bug #1"). One lock domain per cache means no lock-ordering deadlocks
 * and no pointer updates seen half-done.
 */
public final class DoublyLinkedList<K, V> {
    private final CacheEntry<K, V> head = new CacheEntry<>(null, null);
    private final CacheEntry<K, V> tail = new CacheEntry<>(null, null);
    private int size;

    public DoublyLinkedList() {
        head.next = tail;
        tail.prev = head;
    }

    /** Inserts {@code node} right after the head sentinel (most-recent position). */
    public void addFirst(CacheEntry<K, V> node) {
        if (node.isLinked()) {
            throw new IllegalStateException("entry already linked: " + node.key);
        }
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
        size++;
    }

    /** Unlinks {@code node} if it is linked; returns false if it was not linked. */
    public boolean remove(CacheEntry<K, V> node) {
        if (!node.isLinked()) {
            return false;
        }
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
        size--;
        return true;
    }

    /** Moves an already-linked node to the front (O(1)). */
    public void moveToFirst(CacheEntry<K, V> node) {
        if (head.next == node) {
            return; // already most recent: skip four pointer writes on the hottest path
        }
        remove(node);
        addFirst(node);
    }

    /** Returns the least-recent node without unlinking it, or null if empty. */
    public CacheEntry<K, V> peekLast() {
        return tail.prev == head ? null : tail.prev;
    }

    /** Unlinks and returns the least-recent node, or null if empty. */
    public CacheEntry<K, V> removeLast() {
        CacheEntry<K, V> last = peekLast();
        if (last != null) {
            remove(last);
        }
        return last;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Unlinks every node (so stale references cannot be re-linked) and resets the list. */
    public void clear() {
        while (removeLast() != null) {
            // unlink one by one so every node's prev/next is nulled
        }
    }

    /**
     * Walks the list forward and backward and checks every pointer pair.
     * Used by tests to prove no pointer update was left half-done after concurrent stress.
     *
     * @return the number of nodes found
     * @throws IllegalStateException if the structure is corrupt
     */
    public int validate() {
        int forward = 0;
        CacheEntry<K, V> prev = head;
        for (CacheEntry<K, V> n = head.next; n != tail; n = n.next) {
            if (n == null) throw new IllegalStateException("broken forward chain at index " + forward);
            if (n.prev != prev) throw new IllegalStateException("prev pointer mismatch at key " + n.key);
            if (++forward > size) throw new IllegalStateException("cycle or size drift: forward > size " + size);
            prev = n;
        }
        if (tail.prev != prev) throw new IllegalStateException("tail.prev does not point at last node");
        int backward = 0;
        for (CacheEntry<K, V> n = tail.prev; n != head; n = n.prev) {
            if (++backward > size) throw new IllegalStateException("backward walk exceeds size");
        }
        if (forward != size || backward != size) {
            throw new IllegalStateException("size=" + size + " forward=" + forward + " backward=" + backward);
        }
        return forward;
    }
}
