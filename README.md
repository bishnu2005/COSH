# COSH – Distributed In-Memory Cache System

COSH is a modular distributed in-memory cache system built in Java. It is designed to handle concurrent workloads while providing deterministic request routing and adaptive eviction strategies.

## Core Architecture

* **Consistent Hashing Router:** Implements a hash ring using MD5 and virtual nodes for deterministic request routing across physical cache nodes.

* **Thread-Safe LRU Core:** Combines `ConcurrentHashMap`, a custom Doubly Linked List, and `ReentrantLock` synchronization to support concurrent cache access, O(1) node reordering, and efficient eviction.

* **Pluggable Eviction Framework:** Supports interchangeable cache eviction strategies without modifying the underlying cache engine.

* **Sliding-Window Adaptive Policy:** Tracks recent access patterns using a concurrent temporal window. Recently accessed eviction candidates receive a second chance and are promoted, allowing the policy to adapt to localized traffic patterns.

## Technologies Used

* **Java 17**
* **Concurrency Utilities:** `ConcurrentHashMap`, `ReentrantLock`, `CountDownLatch`, `ExecutorService`
* **Maven**
* **JUnit 5**

## Benchmark

COSH includes a reproducible 100K-operation benchmark using an 80/20 skewed workload to introduce temporal locality.

| Metric | Result |
|---|---:|
| Total Operations | 100,000 |
| Cache Capacity | 200 |
| Key Space | 2,000 |
| Workload | 80/20 Skewed |
| GET Operations | 70,154 |
| Cache Hits | 22,391 |
| Cache Misses | 47,763 |
| Cache Hit Rate | 31.92% |
| P50 Latency | 42 ns |
| P95 Latency | 6,458 ns |

The benchmark includes a JVM warmup phase and pre-generated keys to reduce measurement noise.

## Getting Started

### Prerequisites

* Java 17 or higher
* Maven 3.6+

### Build and Test

Compile the project and run the complete test suite:

```bash
mvn clean test