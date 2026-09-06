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
| `report-classes-dorian-4a0c8f5e9.txt` | dorian | `4a0c8f5e9` | 10133 / 0 / 0 / 283 skipped, 237 reports, exit 0 (run at `53077edd2`, no `src/` drift) |

**The first cross-box diff is EMPTY, and this file predicted it would not be.** The two
lists are identical, class for class, so every class in the suite is PRESENT on both boxes;
what differs across boxes is what each class SKIPS (283 against 189), not what runs at all.
The prediction quoted `.todo/708`'s seventeen differing classes and 87 skips as if they were
classes that would be absent -- they are classes whose SKIP COUNT differs, all present on
both. The distinction is the whole point of keeping the list: a count that misses by one and
a set that misses a name are different findings, and only the second is a dropped class.

So the count now closes as a SET EQUALITY rather than as an arithmetic, which is stronger
than what the discipline was written to get: the next run on either box compares against a
list known to be shared, and any name that leaves is attributable to the box or to the
change, never to the two boxes having been different all along.
