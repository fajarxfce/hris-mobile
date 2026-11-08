# Bounded indexed snapshots

ReplicaStore reads up to four indexed query windows under one generation, revision and
account check. Each window remains capped at200 rows; the aggregate bound is400. This
allows two complete bounded inventories in one transaction without combining checkpoints
from independent reads. The API, schema, ownership and mutation limits are unchanged.

The common raw contract verifies two129-row indexes and the400-row request budget under
the same checkpoint. It rejects aggregate401, per-page201, more than four windows and
invalid scopes/tags/cursors. Actual SQLite and Chrome/IndexedDB tests pass; shared iOS
source compiles. This is not a device benchmark or native Apple test execution.

The chat composer uses two129-row windows for active/held candidates while retaining its
own total128-entry capacity. This generic raw-store change belongs in the starter after
Mobile validation. See [the exact gate](measurements/collaboration-composer-adoption-2026-11-08.json).
