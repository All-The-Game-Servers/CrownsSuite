# Phase 3 Status

Phase 3 is implemented enough to stabilize, not to declare finished and forget.

Implemented in source:

- Central relay-sync endpoint and relay routing cache
- Keeper `/ws/data` client and relay `/ws/data` server with HELLO/ACK
- non-zero `host_port` reporting on instance create and start results
- Java handshake parsing on relay ingress
- local Keeper byte-stream bridging
- cross-relay forwarding through peer relay sessions
- Bedrock public-port route assignment in Central
- Relay UDP ingress listeners keyed by assigned public port
- keeper-local UDP bridging for Bedrock
- cross-relay Bedrock forwarding through peer relay sessions

Still not done well enough to call production-proven:

- production load testing and recovery hardening
- broad real-world operator validation across separate machines

For v1 stabilization, the next job is no longer designing relay architecture. It is proving the Java and Bedrock relay paths end to end on real machines and packaging the repo so operators understand the real contract.
