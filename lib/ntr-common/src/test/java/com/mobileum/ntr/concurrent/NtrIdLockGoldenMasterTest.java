package com.mobileum.ntr.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden Master / characterization tests for {@link NtrIdLock}.
 *
 * <p>Locks in the externally observable behavior of the C++ original
 * {@code CIDLock} from {@code RNS-NTR/cpp/IDLock.h}, with two intentional
 * deviations recorded in the Port Mode Report:
 *
 * <ol>
 *   <li>Bounds check is FIXED: the C++ comparison {@code m_baseId > id} (on
 *       a normalized id) was a defect. The Java port correctly rejects IDs
 *       outside {@code [baseId, maxId)} by throwing
 *       {@link IllegalArgumentException}.</li>
 *   <li>{@code acquire()} on a bad ID THROWS rather than silently returning,
 *       preventing use-after-bad-input.</li>
 * </ol>
 */
class NtrIdLockGoldenMasterTest {

    // ─── Construction / range invariants ───────────────────────────────────

    @Nested
    @DisplayName("Constructor / range invariants")
    class Construction {

        @Test
        @DisplayName("rejects empty range (maxId == baseId)")
        void emptyRange_throws() {
            assertThatThrownBy(() -> new NtrIdLock(5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxId");
        }

        @Test
        @DisplayName("rejects inverted range (maxId < baseId)")
        void invertedRange_throws() {
            assertThatThrownBy(() -> new NtrIdLock(10, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxId");
        }

        @Test
        @DisplayName("baseId() and maxId() expose the configured range")
        void rangeAccessors() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            assertThat(lock.baseId()).isEqualTo(10);
            assertThat(lock.maxId()).isEqualTo(20);
        }
    }

    // ─── Bounds checking (FIXED vs C++ buggy behavior) ─────────────────────

    @Nested
    @DisplayName("Bounds checking — FIXED from C++ original")
    class Bounds {

        @Test
        @DisplayName("acquire below baseId throws")
        void acquireBelowBase_throws() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            assertThatThrownBy(() -> lock.acquire(9))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("acquire at maxId (exclusive) throws")
        void acquireAtMax_throws() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            assertThatThrownBy(() -> lock.acquire(20))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("tryAcquire above maxId throws")
        void tryAcquireAboveMax_throws() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            assertThatThrownBy(() -> lock.tryAcquire(100))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("release on out-of-range ID throws")
        void releaseOutOfRange_throws() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            assertThatThrownBy(() -> lock.release(0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("baseId (lower edge, inclusive) is valid")
        void lowerEdgeValid() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            lock.acquire(10);
            lock.release(10);
        }

        @Test
        @DisplayName("maxId-1 (upper edge, inclusive) is valid")
        void upperEdgeValid() {
            NtrIdLock lock = new NtrIdLock(10, 20);
            lock.acquire(19);
            lock.release(19);
        }
    }

    // ─── Single-threaded acquire / release semantics ───────────────────────

    @Nested
    @DisplayName("Single-threaded acquire / tryAcquire / release")
    class SingleThreaded {

        @Test
        @DisplayName("tryAcquire on unheld ID returns true")
        void tryAcquire_unheld_returnsTrue() {
            NtrIdLock lock = new NtrIdLock(0, 16);
            assertThat(lock.tryAcquire(3)).isTrue();
            lock.release(3);
        }

        @Test
        @DisplayName("tryAcquire on ID held by same thread is reentrant (returns true)")
        void tryAcquire_reentrant() {
            NtrIdLock lock = new NtrIdLock(0, 16);
            assertThat(lock.tryAcquire(3)).isTrue();
            assertThat(lock.tryAcquire(3)).isTrue();
            lock.release(3);
            lock.release(3);
        }

        @Test
        @DisplayName("acquire then release on the same ID succeeds")
        void acquireRelease_sameId() {
            NtrIdLock lock = new NtrIdLock(0, 16);
            lock.acquire(7);
            lock.release(7);
            assertThat(lock.tryAcquire(7)).isTrue();
            lock.release(7);
        }

        @Test
        @DisplayName("different IDs are independent (acquire one doesn't block another)")
        void differentIds_independent() {
            NtrIdLock lock = new NtrIdLock(0, 16);
            assertThat(lock.tryAcquire(1)).isTrue();
            assertThat(lock.tryAcquire(2)).isTrue();
            assertThat(lock.tryAcquire(3)).isTrue();
            lock.release(1);
            lock.release(2);
            lock.release(3);
        }
    }

    // ─── Multi-threaded contention ─────────────────────────────────────────

    @Nested
    @DisplayName("Multi-threaded contention")
    class Contention {

        @Test
        @DisplayName("tryAcquire on ID held by ANOTHER thread returns false")
        void tryAcquire_heldByOtherThread_returnsFalse() throws Exception {
            NtrIdLock lock = new NtrIdLock(0, 16);
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean otherThreadGotIt = new AtomicBoolean(true);

            Thread holder = new Thread(() -> {
                lock.acquire(5);
                acquired.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.release(5);
            });
            holder.start();

            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
            otherThreadGotIt.set(lock.tryAcquire(5));

            release.countDown();
            holder.join(2_000);

            assertThat(otherThreadGotIt).isFalse();
        }

        @Test
        @DisplayName("acquire blocks until other thread releases, then proceeds")
        void acquire_blocksUntilReleased() throws Exception {
            NtrIdLock lock = new NtrIdLock(0, 16);
            CountDownLatch holderHasLock = new CountDownLatch(1);
            CountDownLatch holderReleased = new CountDownLatch(1);
            AtomicBoolean waiterProceeded = new AtomicBoolean(false);

            Thread holder = new Thread(() -> {
                lock.acquire(8);
                holderHasLock.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.release(8);
                holderReleased.countDown();
            });

            Thread waiter = new Thread(() -> {
                try {
                    holderHasLock.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.acquire(8);
                waiterProceeded.set(true);
                lock.release(8);
            });

            holder.start();
            waiter.start();

            assertThat(holderReleased.await(2, TimeUnit.SECONDS)).isTrue();
            waiter.join(2_000);

            assertThat(waiterProceeded).isTrue();
        }
    }
}
