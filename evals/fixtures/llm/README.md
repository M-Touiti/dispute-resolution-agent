# Model fixtures

`<sha256 of the canonical request>.json`: one recorded model call each, written by the recording adapter and served
by the replay adapter (ADR 0006). Committed; review the `request` section of a changed fixture as a prompt change.

Recorded in M1 task 1.16. Until then this folder is empty and the replay adapter misses on every call: a case then
escalates instead of being decided, which the compose smoke test accepts.
