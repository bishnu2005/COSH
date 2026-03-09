# COSH — Concurrent Optimized Storage & Hashing

A lightweight, JVM-native modular cache engine with distributed grid capabilities and adaptive eviction strategies.

---

## Project Overview

COSH (Concurrent Optimized Storage & Hashing) is a framework-free, high-performance caching system built for the JVM. Designed as a systems research prototype, COSH explores the performance and architectural trade-offs of caching mechanisms, comparing classic Least Recently Used (LRU) approximations against custom adaptive frequency-recency heuristics.

The architecture is highly modular, allowing the cache to run as a lightweight embedded library (Karui mode) or as a distributed cluster (Grid mode) supporting both binary and HTTP transports. It includes a robust benchmarking harness for scientifically validating eviction policies under skewed workload distributions.

---

## Key Features

- **Modular Cache Architecture**: Clean separation between core storage, transport layers, and eviction strategies.
- **Pluggable Eviction Policy SPI**: Stateless and stateful eviction algorithms dynamically loaded via Java SPI.
- **Sampled LRU Eviction**: High-performance, low-overhead LRU approximation using random sampling.
- **Adaptive Eviction Policy**: Custom sliding-window frequency and recency heuristics avoiding ML framework bloat.
- **Embedded Cache Mode (Karui)**: Ultra-lightweight deployment mode for single-JVM applications.
- **Distributed Grid Architecture**: Client-gateway-node topology for horizontal scaling.
- **Consistent Hashing Gateway**: MurmurHash3-based virtual node rings for stable data distribution.
- **Binary Netty Transport Protocol**: High-throughput, low-latency custom binary wire format.
- **HTTP REST API**: Standardized access via Spring Boot Web for interoperability.
- **Benchmark Workload Generator**: Built-in Zipf, Uniform, and Hot-Key Spike distributions for rigorous testing.

---

## Architecture

The project is structured as a Maven multi-module monorepo:

```text
cosh/
├── cosh-core           # Core in-memory storage, APIs, and default eviction (SampledLRU)
├── cosh-adaptive       # Advanced frequency-recency eviction heuristics loaded via SPI
├── cosh-karui          # Embedded deployment wrapper for localized caching
├── cosh-grid           # Distributed caching extensions
│   ├── cosh-grid-node    # Data node supporting Netty binary and HTTP transports
│   └── cosh-grid-gateway # Routing gateway using a consistent hash ring
├── cosh-benchmark      # Tooling for latency and hit-rate analysis across workloads
└── cosh-spring         # Spring Boot starters and auto-configurations
```

### Module Responsibilities

- **`cosh-core`**: The heart of the system. Implements `CacheStore` using `ConcurrentHashMap` with lazy TTL expiration. Zero external dependencies.
- **`cosh-adaptive`**: Provides `AdaptiveEvictionPolicy`, avoiding `cosh-core` modification.
- **`cosh-karui`**: Simplifies standard local ingestion.
- **`cosh-grid`**: Scales out the storage. The **gateway** handles Request routing via `ConsistentHashRing`, while **nodes** act as discrete data silos.
- **`cosh-benchmark`**: A custom harness measuring throughput, latency percentiles, and hit-rates under modeled contention.

---

## Core Components

- **`CacheStore` Implementation**: Thread-safe caching engine powered exclusively by Java's `ConcurrentHashMap`. Expiration is evaluated lazily on retrieval, avoiding the overhead of background reaper threads.
- **`EvictionPolicy` Interface**: A strict lifecycle SPI (`onGet`, `onPut`, `onRemove`, `selectEvictionCandidate`) promoting decoupled eviction algorithms.
- **SampledLRU Algorithm**: Inspired by Redis, it selects 5 random keys and evicts the one with the oldest access timestamp. This achieves O(1) eviction logic without global queues.
- **Adaptive Eviction Implementation**: Uses an `AdaptiveScorer` that evaluates random candidates against running global maxima of recency and frequency, dynamically punishing stale or low-frequency entries.
- **Consistent Hashing**: The `ConsistentHashRing` allocates 100 virtual nodes per physical instance using mapping functions powered by Guava's MurmurHash3, minimizing rebalancing thrash during topology changes.

---

## Eviction Policies

COSH contrasts two primary eviction approaches:

1. **SampledLRU (Baseline)**: 
   Stateless and fast. It relies only on a `lastAccessTime` recorded in the `CacheEntry`. During eviction, it compares random samples and drops the least recently used, representing low overhead but lower accuracy under heavy skew.

2. **Adaptive Eviction (Frequency-Recency)**:
   A lightweight heuristic policy isolating state within a dedicated `KeyStats` structure. Instead of running inference on every read, it tracks O(1) counters and only evaluates its scoring formula during `selectEvictionCandidate()`. This approach is mathematically geared to protect long-term hot keys (frequency) while allowing bursty traffic to cool off (recency).

---

## Transport Layer

To evaluate IPC overhead, `cosh-grid-node` supports dual protocol stacks:

- **HTTP (Spring Boot REST)**: Handled by `NodeController`, providing standard `GET`, `PUT`, and `DELETE` endpoints for broad compatibility and easy debugging.
- **Binary Protocol (Netty)**: A high-performance, custom wire format (COSH Binary Protocol v1). It utilizes a dense 12-byte fixed request header and a 6-byte response header, bypassing massive HTTP framing overhead and substantially reducing network latency.

---

## Benchmark System

Built to provide scientific rigor, the `cosh-benchmark` module generates deterministically seeded traffic:

- **Workload Generation**: Features a pre-computed inverse-CDF Zipfian distribution generator mimicking real-world object popularity.
- **Distributions**: Supports Uniform, varying Zipf skews (s=1.0, 1.2, 1.5), and transient Hot-Key Spikes (where 70% of traffic focuses on 5 keys).
- **Measurement**: Captures latency micro-percentiles (avg, p99), cache hit rates, CPU utilization, and heap allocation.
- **Reporting**: Exports cleanly formatted Console grids and CSV output for plotting.

---

## Performance Experiments

Experimental outputs reveal clear trade-offs between speed and accuracy:

- **Eviction Hit-Rates**: Under a Zipf (s=1.2) workload with constrained cache sizing (e.g., 100 slots for 1000 keys), **Adaptive** eviction achieves a +1.22% absolute hit-rate improvement over **SampledLRU** (2.02% vs 0.80%).
- **Latency Overhead**: The computational simplicity of SampledLRU yields exceptional speed (~0.3µs avg latency), while the heavier math of Adaptive scoring introduces minor computational drag (~1.1µs avg latency).

---

## Getting Started

### 1. Build the Project
Compile all modules, run tests, and repackage Spring Boot applications:
```bash
mvn clean install
```

### 2. Run the Cache Node (Grid Mode)
Start a standalone data node exposing HTTP and Netty ports:
```bash
java -jar cosh-grid/cosh-grid-node/target/cosh-grid-node.jar
```

### 3. Run the Routing Gateway
Start the consistent hashing gateway to proxy requests:
```bash
java -jar cosh-grid/cosh-grid-gateway/target/cosh-grid-gateway.jar
```

### 4. Run Benchmarks
Execute the latency and eviction simulation suites:
```bash
mvn test -pl cosh-benchmark
```

---

## Project Goals

COSH was engineered explicitly as a **systems research prototype** to investigate:
- The robustness and hit-rate trade-offs of localized eviction policies under Zipfian skews.
- Distributed cache gateway routing strategies via consistent hashing.
- The latency delta between classic Spring HTTP REST architectures and bespoke Netty binary protocols.
- Pure JVM-native concurrent systems engineering.

---

## Technologies Used

- **Java 17**
- **Spring Boot 3.2.3**
- **Netty** 
- **ConcurrentHashMap** 
- **Guava** (MurmurHash3)
- **Micrometer / Prometheus**
- **Maven** 
- **Lombok**
- **JUnit 5 / Surefire** 
- **Docker** 

---

## License

*This project is unlicensed / proprietary research.*
