# NetCache

👶 **[新人入职请先读 ONBOARDING.md](./ONBOARDING.md)**

NetCache is a lightweight distributed KV cache built with Java 17 and Netty 4. It implements a focused Redis-style command set, consistent-hash routing, asynchronous master-slave replication, and Sentinel-style failover primitives for local experimentation and incremental evolution.

## What It Is / What It Is Not

NetCache is a learning-oriented distributed cache engine with a multi-module architecture: protocol, storage, server, client, cluster, sentinel, and benchmark. It is not a drop-in Redis replacement, nor a production-hardened consensus system.

## Architecture Diagram

```text
                    +---------------------------+
                    |        NetCache Client    |
                    | TopologyCache + Router    |
                    +-------------+-------------+
                                  |
                 +----------------+----------------+
                 |                                 |
        +--------v--------+               +--------v--------+
        |   Master Node   |  repl stream  |    Slave Node   |
        | Netty + Storage +---------------> Netty + Storage |
        +--------+--------+               +--------+--------+
                 |                                 |
                 +----------------+----------------+
                                  |
                      +-----------v-----------+
                      |   Sentinel Quorum     |
                      | health + election +   |
                      | topology failover     |
                      +-----------------------+
```

## Quickstart

### 1. Build

```bash
mvn clean verify
```

### 2. Start the local 3M3S3Sentinel topology

```bash
docker compose up --build
```

### 3. Run the sample client

```bash
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dnetcache.seed=127.0.0.1:7001
```

### 4. Trigger a failover rehearsal

```bash
./scripts/kill-master.sh master-1
```

## Command Reference

| Command | Description | Notes |
|---|---|---|
| `PING` | Health check | returns OK |
| `GET key` | Fetch value | missing key => NIL |
| `SET key value [ttlMs]` | Write value | ttl optional |
| `DEL key` | Delete key | returns 0/1 |
| `EXPIRE key ttlMs` | Update ttl | returns 0/1 |
| `INCR key` | Increment integer string | creates from 0 |
| `DECR key` | Decrement integer string | creates from 0 |
| `EXISTS key` | Existence check | returns 0/1 |
| `INFO` | Node info | server metadata |

## Client SDK Usage

```java
try (NetCacheClient client = NetCacheClient.builder()
        .seeds("127.0.0.1:7001")
        .build()) {
    client.set("hello".getBytes(), "world".getBytes());
    byte[] value = client.get("hello".getBytes());
}
```

## Performance Data

- `netcache-benchmark` contains `ThroughputBenchmark` and `LatencyBenchmark` JMH entrypoints.
- `FailoverScenario` validates recovery flow and the smoke run keeps failover below 3 seconds.
- Final benchmark runs should be recorded from the generated JMH output before release.

## Roadmap

- Harden Sentinel from local failover primitives into networked multi-process orchestration.
- Add richer deployment packaging beyond Maven/compose execution.
- Expand protocol compatibility and benchmark reporting automation.
