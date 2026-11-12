#!/usr/bin/env python3
"""One line about how a test JVM spent its time collecting garbage.

    test-gc-summary.py <label> <gc log>      prints the line; prints nothing if there is no log

The log is what `-Xlog:gc,gc+init:file=<path>:uptime:filecount=0` writes (build.gradle turns
that on when Gradle is given `-PtestGcLog=<path>`; the full-suite workflow does). The line goes
into a check-run annotation, which is the only part of a run the development sandbox can read.

Why. The whole suite runs in ONE test JVM, on Gradle's default heap, and everything a test
leaves in a static field stays until the JVM ends. A heap that is nearly full does not fail: the
collector runs more and more often and the suite slows down, and from outside that cannot be
told from a stuck test. On 2026-11-12 the same code took 9 and 11 minutes in two runs and was
stopped at the 45-minute bound in a third (43242985447), with nothing to say which of the two it
was. This line says it for every run: how much of the JVM's life went to the collector, and how
the heap that survives a collection grew from the start of the run to the end.

Reading it:
  * "paused" is time the tests were stopped. A few percent is ordinary.
  * "heap left after a collection" at a quarter, half, three quarters and the end of the run,
    then "at its fullest", against "of a possible N M" (the JVM's limit). If the figures near
    N, the JVM is out of room, and the next test class that is added may be the one that
    turns a slow run into one that does not finish. "At its fullest" is the most that five
    collections in a row all left behind, anywhere in the run; from 85% of the limit the line
    says "NEARLY OUT OF ROOM" and the workflow raises it from a notice to a warning.
  * "the full ones" splits the full collections in two, each with the time the tests were
    stopped for them. "For want of room" are the collector's last resort: more than a handful
    means the same as a last figure near N. "Asked for by the code" are calls to System.gc(),
    and say nothing about room: one test (StaticLmAssetDriftTest) makes 36 of them, three
    before and three after loading each of six language models, to measure a heap delta.
  * The machine is at the end (processors, memory), because a small or busy runner is the other
    way a run gets slow.

What it has said so far, about the whole suite (docs/TEST-PITFALLS.md, CI traps 5 and 9):
  * 43259344146, on Gradle's default 512 MB: 61 full collections, 27 s paused and 94 s of
    concurrent work in a 340 s run, and 475M of 512M still in use after a collection at the
    end. It would have been the warning. The invocation was given 1 GB because of it.
  * 43266527543, at 1 GB: 36 full collections, 15 s paused, 50 s of concurrent work, 554M of
    1024M at the end. 36 is exactly what the one test asks for, which is why the line now
    counts those apart.
  * 43270987868, at 1 GB, the first with the split: none for want of room, 36 asked for (6.1 s
    of the 12.1 s paused).
  * 43275303929, at 1 GB, the first with "at its fullest" as it is computed now: 842M of 1024M,
    82%. More than the whole of the old limit, and three points short of the warning. The
    invocation was given 2 GB because of it.

"At its fullest" is not what the program needs. G1 lets old garbage lie until the heap is
nearly half full before it starts to clean it, so a long run that makes a lot of garbage
drifts up towards half its limit whatever the limit is. The figure means something near the
top: at 85% and more, five collections in a row could not get the JVM back below that.

How far the figures can be trusted. Each quarter's is the least that any collection left
behind in the twelfth of the run before that point: what was in use, plus whatever garbage
that collection did not look at. A program with room to spare reads the same under any
limit: the Kotlin compiler, logged the same way under limits of 70M, 90M and 1200M, read
18/42/51/57M, 17/40/52/56M and 16/43/51/59M. The suite did not: 475M under 512M, 554M under
1024M. Two things can do that, and they were not told apart: given room, the collector
leaves more old garbage lying; pressed against its limit, a JVM drops what it is allowed to
drop (soft references). So compare runs at the same limit, and do not read the smaller
figure as a size the suite fits in.
"""
import re
import sys

PAUSE = re.compile(r'\[(\d+\.\d+)s\].*?GC\(\d+\) Pause (\w+).*? (\d+)M->(\d+)M\((\d+)M\) (\d+\.\d+)ms')
# Why a full collection ran: "Pause Full (System.gc()) 300M->250M(512M) 150.1ms".
FULL_CAUSE = re.compile(r'Pause Full \((.*?)\) \d+M->')
# The causes that are a request, not a need. Everything else (G1 Compaction Pause, Allocation
# Failure, ...) is the collector having run out of room.
ASKED_FOR = ('System.gc()', 'Diagnostic Command', 'Heap Inspection Initiated GC',
             'Heap Dump Initiated GC', 'JvmtiEnv ForceGarbageCollection', 'WhiteBox Initiated Full GC')
CONCURRENT = re.compile(r'GC\(\d+\) Concurrent [\w ]*?Cycle (\d+\.\d+)ms')
USING = re.compile(r'Using (\w+)')
STAMP = re.compile(r'^\[(\d+\.\d+)s\]')
MAX_HEAP = re.compile(r'Heap Max Capacity: (\d+)([MG])')
CPUS = re.compile(r'CPUs: (\d+) total, (\d+) available')
MEMORY = re.compile(r'Memory: (\d+)([MG])')
# The share of the limit still in use after a collection, at the JVM's fullest, from which the
# line says so in capitals. The suite stood at 93% the first time it was measured, two runs
# after the one that sat in the test task until its bound; the Kotlin compiler finishes
# untroubled at 81% of a 70M limit.
NEARLY_FULL = 0.85


def summarise(label, text):
    collector = None
    pauses = []                 # (at seconds, kind, heap after M, capacity M, pause ms)
    concurrent_ms, cycles, last = 0.0, 0, 0.0
    asked, asked_ms = 0, 0.0    # full collections that the code asked for, and their pauses
    limit = MAX_HEAP.search(text)
    limit_mb = int(limit.group(1)) * (1024 if limit.group(2) == 'G' else 1) if limit else None
    cpus = CPUS.search(text)
    memory = MEMORY.search(text)
    machine = ''
    if cpus:
        machine = '; machine: %s processors' % cpus.group(2)
        if memory:
            machine += ', %s%s' % (memory.group(1), memory.group(2))
    for line in text.splitlines():
        stamp = STAMP.match(line)
        if stamp:
            last = max(last, float(stamp.group(1)))
        if collector is None:
            using = USING.search(line)
            if using:
                collector = using.group(1)
        pause = PAUSE.search(line)
        if pause:
            at, kind, _before, after, capacity, ms = pause.groups()
            pauses.append((float(at), kind, int(after), int(capacity), float(ms)))
            if kind == 'Full':
                cause = FULL_CAUSE.search(line)
                if cause and cause.group(1) in ASKED_FOR:
                    asked += 1
                    asked_ms += float(ms)
            continue
        cycle = CONCURRENT.search(line)
        if cycle:
            cycles += 1
            concurrent_ms += float(cycle.group(1))
    if not pauses:
        if last > 0:
            return '%s: test JVM ran %.0f s and never collected (%s)%s' % (
                label, last, collector or '?', machine)
        return None

    paused = sum(p[4] for p in pauses) / 1000.0
    full = sum(1 for p in pauses if p[1] == 'Full')
    full_ms = sum(p[4] for p in pauses if p[1] == 'Full')
    young = sum(1 for p in pauses if p[1] == 'Young')
    capacity = max(p[3] for p in pauses)
    longest = max(p[4] for p in pauses)

    def left_at(fraction):
        # The smallest heap any pause left behind near that point of the run: the nearest
        # thing a GC log has to "what is really still in use". Every kind of pause counts,
        # the two in a concurrent cycle included ("Pause Remark", "Pause Cleanup"): they give
        # back the regions that marking found empty, and so read lowest of all.
        upto = last * fraction
        window = [p[2] for p in pauses if upto - last * 0.08 <= p[0] <= upto] or \
                 [p[2] for p in pauses if p[0] <= upto][-5:] or [pauses[0][2]]
        return min(window)

    def count(n, noun):
        return '%d %s%s' % (n, noun, '' if n == 1 else 's')

    quarters = [left_at(f) for f in (0.25, 0.5, 0.75, 1.0)]
    # The fullest the JVM was at any moment: the most that five collections in a row all left
    # behind. Not the last quarter's figure, for two reasons that were both seen. A heap can
    # peak between two quarters and fall again (the engine classes' does). And a JVM that dies
    # of OutOfMemoryError lets go of everything as it ends: the stand-in that did read 16M at
    # the end and 99% of its limit by this measure.
    #
    # Young and full collections only. The two pauses of a concurrent cycle log the heap as it
    # stands between collections, with whatever has been allocated since the last one, and
    # counting them (as run 43270987868's line did) let "five in a row" be as little as one
    # real collection.
    after = [p[2] for p in pauses if p[1] in ('Young', 'Full')] or [p[2] for p in pauses]
    fullest = max(min(after[max(0, i - 4):i + 1]) for i in range(len(after)))
    # A quarter's window can hold fewer than five collections and so read higher than that;
    # the line must not print an end that is fuller than "its fullest".
    fullest = max(fullest, max(quarters))
    share = 100.0 * paused / last if last > 0 else 0.0
    parts = [
        '%s: test JVM ran %.0f s (%s)' % (label, last, collector or '?'),
        '%d young and %s, %s' % (young, count(full, 'full collection'),
                                 count(cycles, 'concurrent cycle')),
    ]
    if full:
        # max(): the two sums are floats, and their difference can be a hair below zero.
        parts.append('the full ones: %d for want of room (%.1f s), %d asked for by the code (%.1f s)'
                     % (full - asked, max(0.0, full_ms - asked_ms) / 1000.0,
                        asked, asked_ms / 1000.0))
    parts += [
        'paused %.1f s in all (%.1f%%), longest %.0f ms' % (paused, share, longest),
        'concurrent work %.1f s' % (concurrent_ms / 1000.0),
        'heap left after a collection: %dM, %dM, %dM, %dM at each quarter of the run and %dM at '
        'its fullest, %s'
        % (quarters[0], quarters[1], quarters[2], quarters[3], fullest,
           'of a possible %dM' % limit_mb if limit_mb else 'of %dM in use at most' % capacity),
    ]
    line = '; '.join(parts) + machine
    if limit_mb and fullest >= NEARLY_FULL * limit_mb:
        line += ('; NEARLY OUT OF ROOM: at its fullest the JVM still had %d%% of its limit in use '
                 'after a collection. Raise -PtestMaxHeap for this invocation, or find what the '
                 'tests keep (docs/TEST-PITFALLS.md, CI trap 9)'
                 % round(100.0 * fullest / limit_mb))
    return line


def main(argv):
    if len(argv) != 3:
        print('usage: test-gc-summary.py <label> <gc log>', file=sys.stderr)
        return 0
    try:
        with open(argv[2], errors='replace') as fh:
            text = fh.read()
    except OSError:
        return 0
    line = summarise(argv[1], text)
    if line:
        print(line)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
