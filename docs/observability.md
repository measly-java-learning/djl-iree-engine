# Observability reference

The short version lives in [`README.md`](../README.md#observability). This file is the detail:
what the numbers mean, how JMX behaves when it goes wrong, and why this exists alongside DJL's
own metrics. It assumes you have read that section — what `IreeEngineStats.snapshot()` returns
and the usage snippet are there and are not repeated here.

## The staged-import rate

IREE imports a host buffer zero-copy only when it meets a 64-byte
alignment precondition. A Java direct `ByteBuffer`
does not — the JVM guarantees nothing stronger than 8-byte alignment — so inputs handed
straight from `NDArray.toByteBuffer()` stage a copy on every call. `stagedImports /
(stagedImports + wrappedImports)` is how you find out whether that is happening to you.

A high staged rate is not automatically a problem. Whether the copy is worth eliminating
depends on the ratio of copy cost to kernel cost; see
[Performance and zero-copy inputs](../README.md#performance-and-zero-copy-inputs) and the two
findings documents it links.

## Gauge semantics: `-1` versus `0`

**Byte gauges use `-1` for "unavailable" and `0` for "genuinely zero".** `stagingBytes == 0`
means nothing has staged yet, which is a real state. `deviceBytesPeak == -1` means IREE's
allocator statistics were compiled out of the runtime, so the figure is unknowable — check
`isNativeStatsAvailable()`, which is `false` exactly when every `deviceBytes*` figure is `-1`.

The distinction matters for a dashboard: averaging or summing a `-1` sentinel silently
corrupts a chart, so filter on `isNativeStatsAvailable()` rather than treating the gauge as a
number in all cases.

## JMX

The engine registers an MXBean at `org.measly.iree:type=IreeEngineStats` on the first model
load. Disable it with `-Dai.djl.iree.jmx_enabled=false`, or drive it explicitly via
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

## Why not `ai.djl.metric.Metrics`

DJL's own metrics are a time-series buffer suited to benchmarking, not to always-on monitoring.
`Metrics.limit` defaults to 0, which means *uncapped*, so every `predict()` retains three
`Metric` objects indefinitely unless you wire up both `setLimit` and `setOnLimit`. In a
long-running service that is an unbounded retention of per-call objects.

`IreeEngineStats` is the opposite shape: fixed-size counters and gauges, read on demand, with no
per-call retention at all. Use `ai.djl.metric.Metrics` for profiling a benchmark run; use
`IreeEngineStats` for a process you intend to leave running.
