package com.runeclans.bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bot's background timers, shared (T-184). Every service used to start its own single-thread scheduler: about 37 threads that sat idle
 * almost all the time. Now one timer thread decides when things are due and a worker pool runs them; the pool grows while work is running
 * and shrinks back to nothing when it is idle, so a long job (a clan sync, a feed poll) never makes a short one (a reminder) wait.
 * <p>
 * Each service gets its own {@link #lane}, a {@link ScheduledExecutorService} that keeps what the single-thread scheduler promised: its
 * tasks never run at the same time as each other, in the order they became due. A repeating task that throws stops repeating, as before.
 * While a lane's task runs, the thread carries the lane's name, so logs read as they did.
 * <p>
 * Every lane has an owner role ({@link LaneOwners}, T-702). A repeating task (fixed rate or fixed delay) only does its work in a process
 * whose {@code BOT_ROLE} owns the lane; elsewhere it stays scheduled and each turn does nothing. One-off work ({@code execute},
 * {@code schedule}) always runs, since it answers something that happened in this process. With {@code BOT_ROLE=all} (the default) every
 * process owns every lane, exactly as before.
 */
public final class BotScheduler {
    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final AtomicInteger WORKER_NUMBER = new AtomicInteger();

    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "bot-timer");
        thread.setDaemon(true);
        return thread;
    });
    /** Grows to as many threads as there are lanes with work, and lets each go after a minute idle. */
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 256, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "bot-worker-" + WORKER_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    static {
        TIMER.setRemoveOnCancelPolicy(true);
    }

    private BotScheduler() {}

    /** A scheduler for one service: its tasks run one at a time, on the shared threads. */
    public static ScheduledExecutorService lane(String name) {
        return new Lane(name);
    }

    /** How many threads the shared scheduler has right now (the timer plus the workers busy or about to go idle). For the tests. */
    static int threads() {
        return 1 + WORKERS.getPoolSize();
    }

    private static final class Lane extends AbstractExecutorService implements ScheduledExecutorService {
        private final String name;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private final Set<LaneFuture<?>> futures = ConcurrentHashMap.newKeySet();
        private boolean draining;
        private volatile boolean shutdown;

        Lane(String name) {
            this.name = name;
        }

        /** Queues work for this lane; the lane runs one thing at a time, on a shared worker. */
        private void enqueue(Runnable work) {
            synchronized (this) {
                queue.add(work);
                if (draining) return;
                draining = true;
            }
            try {
                WORKERS.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // every worker busy: leave the work queued and the lane free, so the next thing due starts it instead of the lane sticking
                synchronized (this) {
                    draining = false;
                }
                log.error("No free worker for {}: {} task(s) wait for its next one", name, queue.size(), e);
            }
        }

        private void drain() {
            Thread thread = Thread.currentThread();
            String own = thread.getName();
            thread.setName(name);
            try {
                while (true) {
                    Runnable next;
                    synchronized (this) {
                        next = queue.poll();
                        if (next == null) {
                            draining = false;
                            notifyAll();
                            return;
                        }
                    }
                    try {
                        next.run();
                    } catch (Throwable t) {
                        // execute() work: the single-thread scheduler's thread would have died and been replaced; just log it
                        log.error("A task on {} failed", name, t);
                    }
                }
            } finally {
                thread.setName(own);
            }
        }

        private void open() {
            if (shutdown) throw new RejectedExecutionException(name + " is shut down");
        }

        /** A repeating task that does its work only in a process that owns this lane right now (asked each turn: the leader can change). */
        private Runnable owned(Runnable command) {
            return () -> {
                if (LaneOwners.runsHere(name)) command.run();
            };
        }

        @Override
        public void execute(Runnable command) {
            open();
            enqueue(command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return schedule(() -> {
                command.run();
                return null;
            }, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            open();
            LaneFuture<V> future = new LaneFuture<>(this);
            future.timer.set(TIMER.schedule(() -> enqueue(() -> {
                if (future.isDone()) return;
                try {
                    future.result.complete(callable.call());
                } catch (Throwable t) {
                    future.result.completeExceptionally(t);
                }
                futures.remove(future);
            }), delay, unit));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable given, long initialDelay, long period, TimeUnit unit) {
            open();
            Runnable command = owned(given);
            LaneFuture<Void> future = new LaneFuture<>(this);
            AtomicBoolean waiting = new AtomicBoolean();
            // like the JDK's: a run that is late or long delays the next one, never overlaps it
            future.timer.set(TIMER.scheduleAtFixedRate(() -> {
                if (future.isDone() || !waiting.compareAndSet(false, true)) return;
                enqueue(() -> {
                    try {
                        if (!future.isDone()) command.run();
                    } catch (Throwable t) {
                        future.fail(t);
                    } finally {
                        waiting.set(false);
                    }
                });
            }, initialDelay, period, unit));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable given, long initialDelay, long delay, TimeUnit unit) {
            open();
            Runnable command = owned(given);
            LaneFuture<Void> future = new LaneFuture<>(this);
            Runnable[] fire = new Runnable[1];
            fire[0] = () -> enqueue(() -> {
                if (future.isDone()) return;
                try {
                    command.run();
                } catch (Throwable t) {
                    future.fail(t);
                    return;
                }
                if (!future.isDone() && !shutdown) future.timer.set(TIMER.schedule(fire[0], delay, unit));
            });
            future.timer.set(TIMER.schedule(fire[0], initialDelay, unit));
            return future;
        }

        @Override
        public void shutdown() {
            shutdown = true;
            for (LaneFuture<?> future : List.copyOf(futures)) future.cancel(false);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            synchronized (this) {
                List<Runnable> left = new ArrayList<>(queue);
                queue.clear();
                return left;
            }
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && !draining && queue.isEmpty();
        }

        @Override
        public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated()) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    /** One scheduled task: the timer's next firing, and how it ended (a repeating task only ends by being cancelled or by throwing). */
    private static final class LaneFuture<V> implements ScheduledFuture<V> {
        private final Lane lane;
        final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        final CompletableFuture<V> result = new CompletableFuture<>();

        /** Tracked by its lane from the start, before the timer can fire it, so a task that finishes at once is never re-added after it ends. */
        LaneFuture(Lane lane) {
            this.lane = lane;
            lane.futures.add(this);
        }

        void fail(Throwable t) {
            // the single-thread scheduler stopped such a task silently; say so, since nothing reads a repeating task's future
            log.error("A repeating task on {} failed and won't run again", lane.name, t);
            ScheduledFuture<?> next = timer.get();
            if (next != null) next.cancel(false);
            result.completeExceptionally(t);
            lane.futures.remove(this);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            ScheduledFuture<?> next = timer.get();
            return next == null ? 0 : next.getDelay(unit);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = result.cancel(false);
            ScheduledFuture<?> next = timer.get();
            if (next != null) next.cancel(false);
            lane.futures.remove(this);
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }
}
