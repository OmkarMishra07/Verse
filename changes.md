# Verse Change Log

This file tracks technical modifications, fixes, and architectural adjustments made to the **Verse** codebase.

---

## [Initial Audit] - 2026-07-16

### Added
* Created [insight.md](file:///D:/java%20all%20files/Verse/insight.md) documenting system-wide architecture overview, directory layout, execution flow, dependency lists, and system-level risks.
* Created [changes.md](file:///D:/java%20all%20files/Verse/changes.md) (this file) to maintain development logs and context over time.

### Status / Current State
* The project has been fully audited.
* Discovered 7 critical queue-related logic errors (documented in `insight.md`):
  * **Bug A**: Repeat-All clears the upcoming context queue.
  * **Bug B**: Drag-and-drop queue reordering is a no-op (local lists are overwritten by `QueueManager` state Flow updates).
  * **Bug C**: Tapping history items in the queue list has no effect.
  * **Bug D**: Adding/removing items during shuffle is discarded when shuffle is disabled.
  * **Bug E**: Tapping a context track deletes all pending manual items.
  * **Bug F**: Replaying previous manual songs breaks navigation symmetry.
  * **Bug G**: Unplayed future tracks are erroneously saved to play history.
* **No code changes have been introduced yet.**
