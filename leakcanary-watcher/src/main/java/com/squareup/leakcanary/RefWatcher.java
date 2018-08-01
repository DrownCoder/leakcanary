/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

  public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

  // 执行内存泄漏检测的 executor
  private final WatchExecutor watchExecutor;
  // 调试中不会执行内存泄漏检测
  private final DebuggerControl debuggerControl;
  // 用于在判断内存泄露之前，再给一次GC的机会
  private final GcTrigger gcTrigger;
  // 内存泄漏的 heap
  private final HeapDumper heapDumper;
  // 持有那些待检测以及产生内存泄露的引用的key
  private final Set<String> retainedKeys;
  // 用于判断弱引用所持有的对象是否已被GC,如果被回收，会存在队列中，反之，没有存在队列中则泄漏了
  private final ReferenceQueue<Object> queue;
  // 用于分析产生的heap文件
  private final HeapDump.Listener heapdumpListener;
  // 排除一些系统的bug引起的内存泄漏
  private final ExcludedRefs excludedRefs;

  RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
      HeapDumper heapDumper, HeapDump.Listener heapdumpListener, ExcludedRefs excludedRefs) {
    this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
    this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
    this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
    this.heapDumper = checkNotNull(heapDumper, "heapDumper");
    this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
    this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
    retainedKeys = new CopyOnWriteArraySet<>();
    queue = new ReferenceQueue<>();
  }

  /**
   * Identical to {@link #watch(Object, String)} with an empty string reference name.
   *
   * @see #watch(Object, String)
   */
  public void watch(Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Watches the provided references and checks if it can be GCed. This method is non blocking,
   * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
   * with.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  public void watch(Object watchedReference, String referenceName) {
    if (this == DISABLED) {
      return;
    }
    checkNotNull(watchedReference, "watchedReference");
    checkNotNull(referenceName, "referenceName");
    //获得当前时间
    final long watchStartNanoTime = System.nanoTime();
    //生成一个唯一的key
    String key = UUID.randomUUID().toString();
    //保存这个key
    retainedKeys.add(key);
    //将检查内存泄漏的对象保存为一个弱引用，注意queue
    final KeyedWeakReference reference =
        new KeyedWeakReference(watchedReference, key, referenceName, queue);
    //异步开始分析这个弱引用
    ensureGoneAsync(watchStartNanoTime, reference);
  }

  private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
    watchExecutor.execute(new Retryable() {
      @Override public Retryable.Result run() {
        return ensureGone(reference, watchStartNanoTime);
      }
    });
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
    // 避免因为gc不及时带来的误判，leakcanay会手动进行gc,进行二次确认进行保证
  Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
    //System.currentTimeMillis，那么每次的结果将会差别很小，甚至一样，因为现代的计算机运行速度很快
    //检测系统的耗时所用，所以使用System.nanoTime提供相对精确的计时
    long gcStartNanoTime = System.nanoTime();
    long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);
    //第一次判断，移除此时已经被回收的对象
    removeWeaklyReachableReferences();
    //调试的的时候是否开启内存泄漏判断，默认是false
    if (debuggerControl.isDebuggerAttached()) {
      // The debugger can create false leaks.
      return RETRY;
    }
    //如果此时该对象已经不再retainedKeys中说明第一次判断时该对象已经被回收，不存在内存泄漏
    if (gone(reference)) {
      return DONE;
    }
    //如果当前检测对象还没有被回收，则手动调用gc
    gcTrigger.runGc();
    //再次做一次判断，移除被回收的对象
    removeWeaklyReachableReferences();
    if (!gone(reference)) {
      //如果该对象仍然在retainedKey中，则说明内存泄漏了，进行分析
      long startDumpHeap = System.nanoTime();
      long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);
      // dump出来heap，此时认为内存确实已经泄漏了
      File heapDumpFile = heapDumper.dumpHeap();
      if (heapDumpFile == RETRY_LATER) {
        // Could not dump the heap.
        return RETRY;
      }
      long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);
      //开始分析
      heapdumpListener.analyze(
          new HeapDump(heapDumpFile, reference.key, reference.name, excludedRefs, watchDurationMs,
              gcDurationMs, heapDumpDurationMs));
    }
    return DONE;
  }

  private boolean gone(KeyedWeakReference reference) {
    //retainedKeys不存在该对象的key
    return !retainedKeys.contains(reference.key);
  }

  private void removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    KeyedWeakReference ref;
    //如果此时已经在queue中，说明已经被回收
    while ((ref = (KeyedWeakReference) queue.poll()) != null) {
      //则从retainedKeys中移除
      retainedKeys.remove(ref.key);
    }
  }
}
