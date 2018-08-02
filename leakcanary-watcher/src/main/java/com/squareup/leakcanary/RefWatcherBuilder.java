package com.squareup.leakcanary;

/**
 * Responsible for building {@link RefWatcher} instances. Subclasses should provide sane defaults
 * for the platform they support.
 */
public class RefWatcherBuilder<T extends RefWatcherBuilder<T>> {

  private ExcludedRefs excludedRefs;
  private HeapDump.Listener heapDumpListener;
  private DebuggerControl debuggerControl;
  private HeapDumper heapDumper;
  private WatchExecutor watchExecutor;
  private GcTrigger gcTrigger;

  /** @see HeapDump.Listener */
  public final T heapDumpListener(HeapDump.Listener heapDumpListener) {
    this.heapDumpListener = heapDumpListener;
    return self();
  }

  /** @see ExcludedRefs */
  public final T excludedRefs(ExcludedRefs excludedRefs) {
    this.excludedRefs = excludedRefs;
    return self();
  }

  /** @see HeapDumper */
  public final T heapDumper(HeapDumper heapDumper) {
    this.heapDumper = heapDumper;
    return self();
  }

  /** @see DebuggerControl */
  public final T debuggerControl(DebuggerControl debuggerControl) {
    this.debuggerControl = debuggerControl;
    return self();
  }

  /** @see WatchExecutor */
  public final T watchExecutor(WatchExecutor watchExecutor) {
    this.watchExecutor = watchExecutor;
    return self();
  }

  /** @see GcTrigger */
  public final T gcTrigger(GcTrigger gcTrigger) {
    this.gcTrigger = gcTrigger;
    return self();
  }

  /** Creates a {@link RefWatcher}. */
  public final RefWatcher build() {
    // 判断install是否在Analyzer进程里，重复执行
    if (isDisabled()) {
      return RefWatcher.DISABLED;
    }
    //用于排除某些系统bug导致的内存泄露
    ExcludedRefs excludedRefs = this.excludedRefs;
    if (excludedRefs == null) {
      excludedRefs = defaultExcludedRefs();
    }
    //用于分析生成的dump文件，找到内存泄露的原因
    HeapDump.Listener heapDumpListener = this.heapDumpListener;
    if (heapDumpListener == null) {
      heapDumpListener = defaultHeapDumpListener();
    }
    //用于查询是否正在调试中，调试中不会执行内存泄露检测
    DebuggerControl debuggerControl = this.debuggerControl;
    if (debuggerControl == null) {
      debuggerControl = defaultDebuggerControl();
    }
    //用于在产生内存泄露室执行dump 内存heap
    HeapDumper heapDumper = this.heapDumper;
    if (heapDumper == null) {
      heapDumper = defaultHeapDumper();
    }
    //执行内存泄露检测的executor
    WatchExecutor watchExecutor = this.watchExecutor;
    if (watchExecutor == null) {
      //创建默认的监听内存泄漏的线程池
      watchExecutor = defaultWatchExecutor();
    }
    //用于在判断内存泄露之前，再给一次GC的机会
    GcTrigger gcTrigger = this.gcTrigger;
    if (gcTrigger == null) {
      gcTrigger = defaultGcTrigger();
    }

    return new RefWatcher(watchExecutor, debuggerControl, gcTrigger, heapDumper, heapDumpListener,
        excludedRefs);
  }

  protected boolean isDisabled() {
    return false;
  }

  protected GcTrigger defaultGcTrigger() {
    return GcTrigger.DEFAULT;
  }

  protected DebuggerControl defaultDebuggerControl() {
    return DebuggerControl.NONE;
  }

  protected ExcludedRefs defaultExcludedRefs() {
    return ExcludedRefs.builder().build();
  }

  protected HeapDumper defaultHeapDumper() {
    return HeapDumper.NONE;
  }

  protected HeapDump.Listener defaultHeapDumpListener() {
    return HeapDump.Listener.NONE;
  }

  protected WatchExecutor defaultWatchExecutor() {
    return WatchExecutor.NONE;
  }

  @SuppressWarnings("unchecked")
  protected final T self() {
    return (T) this;
  }
}
