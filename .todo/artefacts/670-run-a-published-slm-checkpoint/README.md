# The certifying report SETS, so the next run can diff the LIST

`.todo/670` says twice that **what a run certifies is failures, errors and the report-file
COUNT, never the totals**, and twice that the count has never closed to the unit because no
prior report SET survived to diff against. This directory is that set.

One file per certification, `report-classes-<box>-<commit>.txt`: the basenames of
`target/surefire-reports/*.txt` after a full `./mvnw test` taken by the ORCHESTRATOR on
`develop` (rule 4), sorted. A later run diffs its own list against the newest file for its
box and NAMES what left, which is the only thing that distinguishes a dropped class from a
skipped one.

| file | box | commit | run |
| --- | --- | --- | --- |
| `report-classes-gb10-9b10e4f0f.txt` | GB10 | `9b10e4f0f` | 10125 / 0 / 0 / 189 skipped, 237 reports, exit 0, `GpuTest` included |

A list from `dorian` is owed the first time A certifies after this; the cross-box diff is
expected to be non-empty and `.todo/708` derived its size (seventeen differing classes, 87
skips) on one box.
