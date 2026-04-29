# Deployment Guide

## Local Multi-Node Topology

`docker-compose.yml` defines:

- 3 master nodes on ports `7001-7003`
- 3 slave nodes on ports `7101-7103`
- 3 sentinel processes

All services execute from the checked-out source tree using Maven entrypoints.

## Start

```bash
docker compose up --build
```

## Stop

```bash
docker compose down
```

## Sample Client Check

```bash
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dnetcache.seed=127.0.0.1:7001
```

## Server Overrides

`NetCacheServer` reads these system properties:

- `netcache.host`
- `netcache.port`
- `netcache.bossThreads`
- `netcache.workerThreads`

This keeps the single-node server reusable in containerized multi-node rehearsals.
