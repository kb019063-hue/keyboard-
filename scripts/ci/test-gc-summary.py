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
    against "of a possible N M" (the JVM's limit): if the last figure is near N, the JVM is out
    of room, and the next test class that is added may be the one that turns a slow run into
    one that does not finish.
  * "full" collections are the collector's last resort; more than a handful means the same.
  * The machine is at the end (processors, memory), because a small or busy runner is the other
    way a run gets slow.
"""
import re
import sys

PAUSE = re.compile(r'\[(\d+\.\d+)s\].*?GC\(\d+\) Pause (\w+).*? (\d+)M->(\d+)M\((\d+)M\) (\d+\.\d+)ms')
CONCURRENT = re.compile(r'GC\(\d+\) Concurrent [\w ]*?Cycle (\d+\.\d+)ms')
USING = re.compile(r'Using (\w+)')
STAMP = re.compile(r'^\[(\d+\.\d+)s\]')
MAX_HEAP = re.compile(r'Heap Max Capacity: (\d+)([MG])')
CPUS = re.compile(r'CPUs: (\d+) total, (\d+) available')
MEMORY = re.compile(r'Memory: (\d+)([MG])')


def summarise(label, text):
    collector = None
    pauses = []                 # (at seconds, kind, heap after M, capacity M, pause ms)
    concurrent_ms, cycles, last = 0.0, 0, 0.0
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
    young = sum(1 for p in pauses if p[1] == 'Young')
    capacity = max(p[3] for p in pauses)
    longest = max(p[4] for p in pauses)

    def left_at(fraction):
        # The smallest heap any collection left behind near that point of the run: the nearest
        # thing a GC log has to "what is really still in use".
        upto = last * fraction
        window = [p[2] for p in pauses if upto - last * 0.08 <= p[0] <= upto] or \
                 [p[2] for p in pauses if p[0] <= upto][-5:] or [pauses[0][2]]
        return min(window)

    quarters = [left_at(f) for f in (0.25, 0.5, 0.75, 1.0)]
    share = 100.0 * paused / last if last > 0 else 0.0
    parts = [
        '%s: test JVM ran %.0f s (%s)' % (label, last, collector or '?'),
        '%d young and %d full collections, %d concurrent cycles' % (young, full, cycles),
        'paused %.1f s in all (%.1f%%), longest %.0f ms' % (paused, share, longest),
        'concurrent work %.1f s' % (concurrent_ms / 1000.0),
        'heap left after a collection: %dM, %dM, %dM, %dM at each quarter of the run, %s'
        % (quarters[0], quarters[1], quarters[2], quarters[3],
           'of a possible %dM' % limit_mb if limit_mb else 'of %dM in use at most' % capacity),
    ]
    return '; '.join(parts) + machine


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
