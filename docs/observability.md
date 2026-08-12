# Observability

The engine keeps fixed-size counters and gauges for every loaded model — inference latency,
memory, and how inputs are reaching the runtime — readable in-process or over JMX.

## Why not `ai.djl.metric.Metrics`

DJL's own metrics are a time-series buffer suited to benchmarking, not to always-on monitoring.
`Metrics.limit` defaults to 0, which means *uncapped*, so every `predict()` retains three
`Metric` objects indefinitely unless you wire up both `setLimit` and `setOnLimit`. In a
long-running service that is an unbounded retention of per-call objects.

`IreeEngineStats` is the opposite shape: fixed-size counters and gauges, read on demand, with no
per-call retention at all. Use `ai.djl.metric.Metrics` for profiling a benchmark run; use
`IreeEngineStats` for a process you intend to leave running.

## Reading the statistics

`IreeEngineStats.snapshot()` returns an immutable view of engine configuration, process totals,
and every live model. It never throws: a monitoring poll must not be the thing that breaks
production.

```java
IreeStatsSnapshot stats = IreeEngineStats.snapshot();
for (IreeModelStats model : stats.getModels()) {
    long imports = model.getWrappedImports() + model.getStagedImports();
    double stagedRate = imports == 0 ? 0.0 : (double) model.getStagedImports() / imports;
    System.out.printf(
            "%s: %d forwards, %.1f%% staged, %d bytes staging%n",
            model.getName(), model.getForwardCount(), stagedRate * 100, model.getStagingBytes());
}
```

Per model you get latency — `getLoadNanos()`, `getForwardCount()`, `getForwardTotalNanos()`,
`getForwardMaxNanos()` — and memory: `getStagingBytes()` for input staging, plus
`getDeviceBytesPeak()` and `getDeviceBytesLive()` from IREE's own allocator. The snapshot adds
process-wide totals, including counters for models that have since been closed, so a model's
work is not lost from the process figures when it goes away.

## The staged-import rate

IREE imports a host buffer zero-copy only when it meets a 64-byte
alignment precondition. A Java direct `ByteBuffer`
does not — the JVM guarantees nothing stronger than 8-byte alignment — so inputs handed
straight from `NDArray.toByteBuffer()` stage a copy on every call. `stagedImports /
(stagedImports + wrappedImports)` is how you find out whether that is happening to you.

A high staged rate is not automatically a problem. Whether the copy is worth eliminating
depends on the ratio of copy cost to kernel cost, which is a property of your model and needs
measuring rather than guessing. Setting `-Diree.engine.alignedBuffers=true` makes
`NDManager.create` hand back 64-byte-aligned buffers, which import with no copy.

## Gauge semantics: `-1` versus `0`

**Byte gauges use `-1` for "unavailable" and `0` for "genuinely zero".** `stagingBytes == 0`
means nothing has staged yet, which is a real state. `-1` means the figure could not be
produced, for any of three reasons: IREE's allocator statistics were compiled out of the
runtime, the model was closed by the time the poll reached it (`toStats()` seeds every gauge —
`wrapped`, `staged`, `stagingBytes`, and both device figures — to `-1` and leaves them there
when the handle is already gone), or the native read itself failed. `isNativeStatsAvailable()`
distinguishes only the first of those: it is a handle-free build probe, so it can read `true`
while a concurrent `close()` still produces an all-`-1` snapshot.

The distinction matters for a dashboard: averaging or summing a `-1` sentinel silently
corrupts a chart, so filter on the gauge value itself (skip a reading if any figure you plan to
use is `-1`) rather than relying on `isNativeStatsAvailable()` to catch every case — it tells
you whether the build supports statistics at all, not whether this particular reading landed
before the model closed.

## JMX

**JMX is the intended path into an existing monitoring stack.** The engine registers an MXBean
at `org.measly.iree:type=IreeEngineStats` on the first model load, carrying the same figures as
`snapshot()`. An MBean is already what the Prometheus JMX exporter and the OpenTelemetry JMX
receiver consume, so exporting these numbers is a matter of pointing one of those at the
process — the engine deliberately ships no exporter of its own and no vendor-specific
integration.

Disable registration with `-Dai.djl.iree.jmx_enabled=false`, or drive it explicitly via
`IreeEngineStats.registerMBean()` / `IreeEngineStats.unregisterMBean()`.

Registration behavior:

- **Idempotent.** A second `registerMBean()` on an already-registered name is a no-op, not an
  error. The automatic registration is a one-shot on first model load and is never retried.
- **Never throws, never fails a model load.** A name collision, a `SecurityManager`, or a
  restricted container produces exactly one logged warning and nothing else. `snapshot()` is
  unaffected either way — JMX is a transport, not the source of truth.
- **The outcome is observable.** `IreeStatsSnapshot.getJmxStatus()` returns `REGISTERED`,
  `DISABLED`, or `FAILED`, and `getJmxError()` carries the reason when it failed. That is how
  you tell "monitoring is off because someone disabled it" from "monitoring is off because
  registration blew up", without grepping logs.

`unregisterMBean()` is equally forgiving: it removes the bean if present, tolerates a race with
another unregister, and logs rather than throws on anything else.
