# Failover Flow

## Detection

- each sentinel records successful pings
- after `sdownAfterMs`, a node becomes SDOWN for that sentinel
- `QuorumDecision` aggregates SDOWN votes into ODOWN once quorum is reached

## Election

`RaftLite` performs simplified leader election:

- increments a term
- elects one leader from participating sentinels
- records vote ownership in the election result

## Promotion

`FailoverCoordinator`:

1. confirms quorum and failover timeout window
2. chooses the best slave by highest replication offset, then priority
3. rewrites topology so the promoted slave becomes master
4. points the failed master and remaining replicas at the new master
5. increments topology epoch

## Rehearsal

Use the helper script:

```bash
./scripts/kill-master.sh master-1
```

For code-level validation, `FailoverScenario` and `SentinelFailoverTest` exercise the recovery path and keep the smoke assertion below 3 seconds.
