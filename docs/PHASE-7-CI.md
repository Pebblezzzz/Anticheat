# Phase 7 CI gate

The Phase 7 implementation is gated by the repository's Java 21 Maven suite and the existing observation-only 1.21.11 harness. The Maven suite includes the Phase 7 timing, history, live-integration, scenario-matrix, replay, duplicate-safety, and performance regression tests.

A green CI run validates compilation and deterministic internal tests only. Empirical validation against a live Minecraft Java 1.21.11 client remains external because this repository cannot launch the licensed client in the development environment.
