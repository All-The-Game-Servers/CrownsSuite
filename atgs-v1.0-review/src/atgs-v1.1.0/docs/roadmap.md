# ATGS Roadmap

This document tracks the phase plan. Each phase is a meaningful slice, not a wish list.

## Phase 0 - Foundations (DONE)

- monorepo layout
- Go workspace
- Postgres schema for keepers, enrollment tokens, sessions, audit log
- shared protocol package
- shared PKI helpers

## Phase 1 - Enrollment and Control Channel (DONE)

- Keeper enrollment with one-time token
- persistent Keeper to Central WebSocket control channel
- session tracking and audit log
- reconnect handling

## Phase 2 - Task Model and Docker Runtime (DONE)

- typed tasks
- Central dispatcher and task status API
- Keeper Docker runtime
- egg loading
- instance lifecycle and logs tasks

## Phase 3 - Relay Data Plane (IMPLEMENTED, STABILIZING)

- relay sync from Central
- Java hostname-based ingress
- Bedrock public-port UDP ingress
- keeper `/ws/data` channel
- cross-relay forwarding support for Java and Bedrock
- Central kept off the player byte stream

Stabilization work still required:

- real operator validation across separate machines
- repeatable smoke coverage
- release docs and package cleanup
- load and failure hardening

## Phase 4 - Backups (PARTIAL)

- backup data model and APIs exist
- storage wiring exists
- operators still need better release-level validation and docs

## Phase 5 - Progenitor Console (PARTIAL, v1.1 operator pass)

- Wails desktop app exists
- keepers, instances, backups, schedules, logs, console, tasks, and audit are surfaced
- still needs real deployed validation as part of v1 stabilization

## Phase 6 - Keeper UX (PARTIAL)

- dual-mode keeper binary exists
- headless and desktop paths exist
- Windows and Linux startup flows still need operator polish

## Phase 7 - Hardening (PARTIAL)

- signed-envelope plumbing exists
- CRL and revoke flow exist
- stabilization still matters more than adding more hardening layers right now

## Phase 8 - Scale and Platform Breadth (DEFERRED)

- broader multi-relay scaling work
- billing and broader platform expansion

The current focus is a dependable flagship v1 release with Paper, Fabric, and Bedrock support that still needs operator validation.

## v1.1 bridge theme

The active bridge release between flagship v1 hosting and the future multi-tenant rewrite is:

- stabilize current Java, Fabric, and Bedrock operator flows
- tighten backups, schedules, and recovery visibility
- improve Progenitor operator leverage
- prepare data contracts and docs for the future v1.2 refactor without implementing the tenant model early
