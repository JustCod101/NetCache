# NetCache Protocol

NetCache uses a compact binary frame with a fixed header followed by an encoded command payload.

## Frame Layout

- magic: 4 bytes
- version: 1 byte
- type: 1 byte
- requestId: 8 bytes
- payloadLength: 4 bytes

Header size is 18 bytes total. The codec layer in `netcache-protocol` validates magic, decodes half-packets/sticky packets, and round-trips request/response payloads.

## Command Payloads

- Requests: `OpCode + argCount + repeated(argLen + argBytes)`
- Responses: `Status + ResultType + bodyLen + body`

## Replication Stream

Phase 7 adds a replication stream format:

- offset: 8 bytes
- opCode: 1 byte
- keyLen + key
- valueLen + value

This is encoded by `ReplStream` and stored in `ReplicationBacklog` for incremental slave catch-up.
