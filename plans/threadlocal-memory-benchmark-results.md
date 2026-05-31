# ThreadLocal Memory Benchmark Results

Generated: 2026-05-31, branch `support-jackson3`

## Setup

- Threads: 1024
- Payload: 1024 KB (1 MB byte array)
- JVM: OpenJDK 21
- Tool: `ThreadLocalMemoryBenchmark` (msgpack-jackson3/src/jmh/...)
- ThreadLocal implementation: **strong references** (no WeakReference)

## Measurement method

Each run has two phases on the **same** fixed thread pool:

1. **No-op phase** — all threads run a no-op; GC × 5; measure heap → thread-pool-only baseline
2. **Work phase** — same threads run Jackson work; GC × 5; measure heap

Reported delta = `(afterWork − afterNoOp) / numThreads`.  
Each mode row is an **independent run**, not cumulative.

## Results

| Mode | Heap after no-op | Heap after work | Delta/thread |
|------|-----------------|-----------------|--------------|
| thread pool only (no Jackson) | 4.24 MB | — | 0.46 KB/thread |
| vanilla (JsonMapper, write+read, default pool) | 4.24 MB | 149.01 MB | 145 KB/thread |
| vanilla-shared-pool (JsonMapper, write+read, sharedBoundedPool) | 4.24 MB | 18.37 MB | 14 KB/thread |
| generator (MessagePackMapper, write only) | 4.24 MB | 141.83 MB | 138 KB/thread |
| generator:noreuse (our ThreadLocal disabled) | 4.23 MB | 134.00 MB | 130 KB/thread |
| parser (MessagePackMapper, read only) | 5.48 MB | 5.71 MB | 0.22 KB/thread |
| both (MessagePackMapper, write+read) | 4.23 MB | 12.80 MB | 8.57 KB/thread |
| both:noreuse (our ThreadLocal disabled) | 4.24 MB | 4.70 MB | 0.47 KB/thread |

## Our ThreadLocal cost (strong reference)

| ThreadLocal | Retained/thread | How measured |
|---|---|---|
| `messageBufferOutputHolder` (generator) | **~8 KB** | `generator` − `generator:noreuse` = 138 − 130 |
| `messageUnpackerHolder` (parser) | **~0.2 KB** | `parser` result |

Both are negligible compared to Jackson's own `BufferRecycler` (~130 KB/thread for large payloads).

## Notes

- **`vanilla` vs `vanilla-shared-pool`**: the default `JsonMapper` uses a ThreadLocal-based
  `BufferRecycler` (145 KB/thread); switching to `sharedBoundedPool` drops this to 14 KB/thread.
  This is a Jackson configuration choice, unrelated to our code.

- **`both` (8.57 KB) << `generator` (138 KB)**: in `both` mode, the read runs after the write
  on the same thread. Jackson's `ByteArrayBuilder` (backed by `BufferRecycler`) retains a large
  write buffer in `generator`-only mode; in `both`, the subsequent read evicts it with a smaller
  buffer. Our `OutputStreamBufferOutput` (~8 KB) accounts for nearly all of the `both` delta.
  Note: Jackson's `BufferRecycler` is not involved in msgpack's internal write path; the large
  retention in `generator` mode comes from `writeValueAsBytes`'s `ByteArrayBuilder` wrapper,
  not from msgpack's own output buffer.
