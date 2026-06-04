package com.mobileum.ntr.concurrent;

import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * (ported-cpp)
 *
 * <p>Java 21 port of {@code CIDLock} from {@code RNS-NTR/cpp/IDLock.h}.
 *
 * <p>A fixed-range, ID-keyed lock multiplexer. Each integer ID in the
 * configured range {@code [baseId, maxId)} owns an independent lock; threads
 * acquiring different IDs do not contend with each other.
 *
 * <p>Differences from the C++ original (intentional):
 * <ul>
 *   <li>Per-slot {@link java.util.concurrent.locks.ReentrantLock} replaces a
 *       single mutex + condition. Eliminates the missed-wakeup hazard present
 *       in the C++ when {@code signal()} woke a waiter for the wrong ID
 *       (CLAUDE.md P12 — no global hot-path lock).</li>
 *   <li>Bounds check fixed. The C++ original (lines 68/100/129) compared
 *       {@code m_baseId > id} after normalizing {@code id = Id - m_baseId},
 *       which is a defect. The Java port checks the original input ID against
 *       {@code [baseId, maxId)} correctly and throws
 *       {@link IllegalArgumentException} on out-of-range IDs.</li>
 *   <li>{@link #acquire(int)} throws on out-of-range ID instead of silently
 *       returning. In the C++, {@code acquire()} returned void on bad input,
 *       leaving the caller to assume it owned the lock — a use-after-bad-input
 *       hazard. The Java port fails fast (CLAUDE.md P1).</li>
 *   <li>Constructor injection / final fields — no global state (CLAUDE.md P10).</li>
 * </ul>
 */
public final class NtrIdLock {

    private static final Logger log = LogManager.getLogger(NtrIdLock.class);

    private final int baseId;
    private final int maxId;
    private final ReentrantLock[] slots;

    /**
     * @param baseId inclusive lower bound of valid IDs
     * @param maxId  exclusive upper bound of valid IDs (must be {@code > baseId})
     * @throws IllegalArgumentException if the range is empty or invalid
     */
    public NtrIdLock(int baseId, int maxId) {
        if (maxId <= baseId) {
            throw new IllegalArgumentException(
                "maxId must be greater than baseId: baseId=" + baseId + ", maxId=" + maxId);
        }
        this.baseId = baseId;
        this.maxId = maxId;
        int size = maxId - baseId;
        this.slots = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            this.slots[i] = new ReentrantLock();
        }
        log.info("NtrIdLock initialized: baseId={} maxId={} slots={}", baseId, maxId, size);
    }

    /** @return the inclusive lower bound of the configured ID range. */
    public int baseId() {
        return baseId;
    }

    /** @return the exclusive upper bound of the configured ID range. */
    public int maxId() {
        return maxId;
    }

    /**
     * Acquire the lock for {@code id}. Blocks until the lock is available.
     * Reentrant for the calling thread (a thread already holding {@code id}
     * does not block on itself).
     *
     * @throws IllegalArgumentException if {@code id} is outside
     *         {@code [baseId, maxId)}.
     */
    public void acquire(int id) {
        slot(id).lock();
    }

    /**
     * Non-blocking acquire.
     *
     * @return {@code true} if the lock was acquired (including reentrant
     *         re-acquisition by the calling thread), {@code false} if it is
     *         held by another thread.
     * @throws IllegalArgumentException if {@code id} is outside
     *         {@code [baseId, maxId)}.
     */
    public boolean tryAcquire(int id) {
        return slot(id).tryLock();
    }

    /**
     * Release the lock for {@code id}. No-op if not currently held by the
     * calling thread — deliberate deviation from the C++ original, which
     * relied on a single global mutex and allowed any thread to clear any
     * slot. Per-slot {@link ReentrantLock} requires holder-only release;
     * tolerating spurious release keeps the externally observable contract
     * close to the C++ behavior.
     *
     * @throws IllegalArgumentException if {@code id} is outside
     *         {@code [baseId, maxId)}.
     */
    public void release(int id) {
        ReentrantLock lock = slot(id);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private ReentrantLock slot(int id) {
        if (id < baseId || id >= maxId) {
            throw new IllegalArgumentException(
                "id out of range [" + baseId + "," + maxId + "): " + id);
        }
        return slots[id - baseId];
    }
}
