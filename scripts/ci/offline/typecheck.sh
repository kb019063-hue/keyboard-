#!/usr/bin/env bash
# typecheck.sh -- "did this change break the build?", answered without a build.
#
# The dev sandbox has no Android SDK and cannot reach Maven Central, so it cannot run Gradle;
# GitHub Actions is the compiler (AGENTS.md). But it CAN reach PyPI and api.github.com, and that
# is enough for the Kotlin compiler's front end:
#
#   * a JRE              -- PyPI wheel `jdk4py`
#   * the Kotlin compiler -- embedded in the PyPI wheel `kotlin-jupyter-kernel`
#   * kotlinx.coroutines  -- bundled, unrelocated, inside that same kernel jar
#   * android.jar (API 34) -- a git blob in github.com/Sable/android-platforms
#
# With only those on the classpath, every reference to AndroidX, Compose, Gson, `R` or
# `BuildConfig` is unresolved, so a healthy tree reports ~14,000 errors. That is expected and it
# does not matter, because the answer is the DIFFERENCE: the same front end is run over a base
# commit and over your tree, and only the errors your tree has and the base does not are shown.
#
#   scripts/ci/offline/typecheck.sh                    # working tree vs origin/main
#   scripts/ci/offline/typecheck.sh --tests            # ...including src/test/kotlin
#   scripts/ci/offline/typecheck.sh <base>             # working tree vs <base>
#   scripts/ci/offline/typecheck.sh <base> <commit>    # <commit> vs <base>
#   scripts/ci/offline/typecheck.sh setup              # only fetch the toolchain (~320 MB, once)
#
# Exit 0: no new errors.  Exit 1: new errors (listed).  Exit 2: the check itself did not run.
#
# What a clean result does NOT mean -- read README.md in this directory before trusting it:
# an expression whose receiver is unresolved is not checked at all, the compiler is newer than
# the project's (2.3 held to language level 2.0), and nothing is linked, packaged or run.
# It is a pre-push check. The green run is still the verdict.
set -uo pipefail

TC="${TC:-$HOME/.cache/tc-typecheck}"       # outside the repository; ~/.cache is never committed
HEAP="${TC_HEAP:-}"                         # default below: 2700m, or 3000m with --tests
JDK4PY_VERSION="21.0.8.2"
KERNEL_VERSION="0.14.1.550"
KERNEL_JAR="kotlin-jupyter-kernel-0.14.1-550-all.jar"
STDLIB_JAR="kotlin-stdlib-2.3.0-dev-200.jar"
# android-34/android.jar in github.com/Sable/android-platforms, addressed by blob so it cannot move.
ANDROID_JAR_BLOB="923bafccf73aef996495c559e919ad8be3cabd58"
ANDROID_JAR_BYTES=26361808

say() { printf '%s\n' "$*" >&2; }
die() { say "typecheck: $*"; exit 2; }

JAVA_BIN="$TC/jdk/jdk4py/java-runtime/bin/java"
JARS="$TC/kernel/run_kotlin_kernel/jars"
ANDROID_JAR="$TC/android-34.jar"
# kotlinx-coroutines-core, lifted out of the kernel jar (see setup). Without it every coroutine
# builder is an unresolved name and nothing inside a `launch { }` or `withContext { }` is checked.
COROUTINES_JAR="$TC/kotlinx-coroutines-core.jar"
# Part of every cached result's file name. Bump it whenever the compile classpath or the compiler
# flags change: a base cached under the old classpath compared with a tree compiled under the new
# one would report the difference between the classpaths, not between the trees.
CLASSPATH_TAG="cp2"

setup() {
    mkdir -p "$TC" || die "cannot create $TC"
    if [ ! -x "$JAVA_BIN" ]; then
        say "typecheck: fetching a JRE (jdk4py $JDK4PY_VERSION) ..."
        python3 -m pip download -q --no-deps --only-binary=:all: --platform manylinux_2_17_x86_64 \
            --python-version 3.11 -d "$TC/wheels" "jdk4py==$JDK4PY_VERSION" >&2 \
            || die "pip could not download jdk4py (is pypi.org reachable?)"
        python3 -m zipfile -e "$TC"/wheels/jdk4py-*.whl "$TC/jdk" || die "could not unpack jdk4py"
        chmod +x "$TC"/jdk/jdk4py/java-runtime/bin/*
    fi
    if [ ! -f "$JARS/$KERNEL_JAR" ]; then
        say "typecheck: fetching the Kotlin compiler (kotlin-jupyter-kernel $KERNEL_VERSION) ..."
        python3 -m pip download -q --no-deps -d "$TC/wheels" "kotlin-jupyter-kernel==$KERNEL_VERSION" >&2 \
            || die "pip could not download kotlin-jupyter-kernel"
        python3 -m zipfile -e "$TC"/wheels/kotlin_jupyter_kernel-*.whl "$TC/kernel" \
            || die "could not unpack kotlin-jupyter-kernel"
    fi
    if [ ! -s "$COROUTINES_JAR" ]; then
        # The kernel's fat jar carries kotlinx-coroutines-core under its real package name, with
        # its .kotlin_module (which is what makes top-level `launch`/`withContext` resolvable).
        # Copy just that out: putting the whole fat jar on the compile classpath would also
        # expose the compiler's own internals to the sources being checked.
        python3 - "$JARS/$KERNEL_JAR" "$COROUTINES_JAR.part" <<'PY' || die "could not extract kotlinx.coroutines from $KERNEL_JAR"
import sys, zipfile
source, target = sys.argv[1], sys.argv[2]
copied = 0
with zipfile.ZipFile(source) as fat, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
    for name in fat.namelist():
        if name.endswith('/'):
            continue
        if name.startswith('kotlinx/coroutines/') or name == 'META-INF/kotlinx-coroutines-core.kotlin_module':
            out.writestr(name, fat.read(name))
            copied += 1
# ~800 classes plus the module file; far fewer means the kernel jar's layout changed.
sys.exit(0 if copied > 500 else 1)
PY
        mv "$COROUTINES_JAR.part" "$COROUTINES_JAR"
    fi
    if [ ! -f "$ANDROID_JAR" ] || [ "$(wc -c < "$ANDROID_JAR")" -ne "$ANDROID_JAR_BYTES" ]; then
        local sdk_jar="${ANDROID_HOME:-/nonexistent}/platforms/android-34/android.jar"
        if [ -f "$sdk_jar" ]; then
            cp "$sdk_jar" "$ANDROID_JAR"
        else
            say "typecheck: fetching android.jar (API 34) ..."
            local api="repos/Sable/android-platforms/git/blobs/$ANDROID_JAR_BLOB"
            gh api -H "Accept: application/vnd.github.raw" "$api" > "$ANDROID_JAR.part" 2>/dev/null \
                || curl -fsSL -H "Accept: application/vnd.github.raw" "https://api.github.com/$api" \
                        -o "$ANDROID_JAR.part" \
                || die "could not download android.jar (is api.github.com reachable?)"
            [ "$(wc -c < "$ANDROID_JAR.part")" -eq "$ANDROID_JAR_BYTES" ] \
                || die "android.jar download is the wrong size; delete $ANDROID_JAR.part and retry"
            mv "$ANDROID_JAR.part" "$ANDROID_JAR"
        fi
    fi
    [ -x "$JAVA_BIN" ] && [ -f "$JARS/$KERNEL_JAR" ] && [ -f "$JARS/$STDLIB_JAR" ] && [ -f "$ANDROID_JAR" ] \
        && [ -s "$COROUTINES_JAR" ] || die "toolchain incomplete under $TC"
}

# front_end <root holding src/> <diagnostics file>: run the compiler front end; never fails on
# compile errors (they are the output), only when the compiler itself did not finish.
front_end() {
    local root="$1" out="$2" list
    list="$(mktemp)"
    ( cd "$root" && find $SOURCE_SETS -name '*.kt' 2>/dev/null | sort ) > "$list"
    [ -s "$list" ] || { rm -f "$list"; die "no Kotlin sources under $root ($SOURCE_SETS)"; }
    local objs; objs="$(mktemp -d)"
    ( cd "$root" && "$JAVA_BIN" "-Xmx$HEAP" -Xss8m -cp "$JARS/$KERNEL_JAR" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 11 \
        -cp "$ANDROID_JAR:$JARS/$STDLIB_JAR:$COROUTINES_JAR" -d "$objs" "@$list" ) > "$out" 2>&1
    rm -rf "$objs" "$list"
    if grep -qE 'OutOfMemoryError|^exception: |Internal error|Could not create the Java Virtual Machine' "$out"; then
        say "$(grep -m3 -E 'OutOfMemoryError|^exception: |Internal error|Could not create' "$out")"
        die "the compiler did not finish on $root -- this is NOT a pass (heap was $HEAP; set TC_HEAP)"
    fi
    # A tree this size without AndroidX on the classpath always has thousands of diagnostics.
    # Almost none means the compiler stopped early, and a silent pass would be worse than no check.
    [ "$(grep -c ': error: ' "$out")" -ge 1000 ] \
        || die "only $(grep -c ': error: ' "$out") diagnostics for $root: the front end stopped early, not a pass"
}

SOURCE_SETS="src/main/kotlin"
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --tests) SOURCE_SETS="src/main/kotlin src/test/kotlin" ;;
        -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) ARGS+=("$arg") ;;
    esac
done

# Measured in the 4 GB dev sandbox: main alone finishes in 2700m, main + tests was run at 3000m.
if [ -z "$HEAP" ]; then
    case "$SOURCE_SETS" in *test*) HEAP=3000m ;; *) HEAP=2700m ;; esac
fi
command -v python3 >/dev/null || die "python3 is required"
setup
[ "${ARGS[0]:-}" = "setup" ] && { say "typecheck: toolchain ready in $TC"; exit 0; }

REPO="$(git rev-parse --show-toplevel 2>/dev/null)" || die "run this inside the repository"
BASE="${ARGS[0]:-origin/main}"
TREE="${ARGS[1]:-}"
git -C "$REPO" rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || die "unknown base '$BASE'"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$TC/cache"
SET_TAG="$(printf '%s' "$SOURCE_SETS" | tr -c 'a-z' '_')"

# diagnostics_for_commit <ref> <label>: front end over a commit, cached by the hash of its src/.
diagnostics_for_commit() {
    local ref="$1" key cached
    key="$(git -C "$REPO" rev-parse "$ref:src")" || die "'$ref' has no src/ directory"
    cached="$TC/cache/$key-$SET_TAG-$CLASSPATH_TAG.txt"
    if [ ! -s "$cached" ]; then
        say "typecheck: compiling $ref ($(git -C "$REPO" rev-parse --short "$ref")) ..."
        mkdir -p "$WORK/$key"
        # shellcheck disable=SC2086
        git -C "$REPO" archive "$ref" $SOURCE_SETS | tar -x -C "$WORK/$key" || die "git archive $ref failed"
        front_end "$WORK/$key" "$cached.part"
        mv "$cached.part" "$cached"
    fi
    printf '%s' "$cached"
}

BASE_OUT="$(diagnostics_for_commit "$BASE")" || exit 2
if [ -n "$TREE" ]; then
    git -C "$REPO" rev-parse --verify --quiet "$TREE^{commit}" >/dev/null || die "unknown commit '$TREE'"
    TREE_OUT="$(diagnostics_for_commit "$TREE")" || exit 2
    TREE_LABEL="$TREE ($(git -C "$REPO" rev-parse --short "$TREE"))"
else
    say "typecheck: compiling the working tree ..."
    TREE_OUT="$WORK/tree.txt"
    front_end "$REPO" "$TREE_OUT"
    TREE_LABEL="working tree"
fi

python3 - "$BASE_OUT" "$TREE_OUT" "$BASE ($(git -C "$REPO" rev-parse --short "$BASE"))" "$TREE_LABEL" <<'PY'
import collections, re, sys

# `src/<set>/kotlin/.../File.kt:LINE:COL: error: message`. Anything before `src/` is dropped, so
# the same file compares equal wherever the tree was unpacked.
PATTERN = re.compile(r'^(?:\S*?/)?(src/(?:main|test)/kotlin/\S+\.kt):(\d+):\d+: error: (.*)$')

def load(path):
    found = collections.defaultdict(list)
    for line in open(path, errors='replace'):
        m = PATTERN.match(line.rstrip('\n'))
        if m:
            found[(m.group(1), m.group(3))].append(int(m.group(2)))
    return found

base, tree = load(sys.argv[1]), load(sys.argv[2])
total = lambda d: sum(len(v) for v in d.values())
print('base  %-30s %6d diagnostics  (thousands are expected: no AndroidX, R, BuildConfig)' % (sys.argv[3], total(base)))
print('tree  %-30s %6d diagnostics' % (sys.argv[4], total(tree)))

# The base builds in CI, so every one of ITS diagnostics is an artefact of the missing libraries.
# A diagnostic counts as new when the tree has more of that (file, message) than the base does;
# comparing counts rather than line numbers keeps an edit that only moves code from looking new.
base_messages = {message for _, message in base}
to_tree, to_file, repeats = [], [], 0
for (path, message), lines in sorted(tree.items()):
    had = len(base.get((path, message), ()))
    if len(lines) <= had:
        continue
    if had:
        repeats += len(lines) - had       # this file already failed on this exact name in the base
    elif message in base_messages:
        to_file.append((path, message, lines))
    else:
        to_tree.append((path, message, lines))

def show(rows):
    for path, message, lines in rows:
        where = ', '.join(str(n) for n in lines[:8]) + (', ...' if len(lines) > 8 else '')
        print('  %s:%s  %s' % (path, where, message[:220]))

count = lambda rows: sum(len(lines) for _, _, lines in rows)
if to_tree:
    print('\nNEW TO THE TREE: %d -- no file in the base reports these. Treat them as real.' % count(to_tree))
    show(to_tree)
if to_file:
    print('\nNEW TO THE FILE: %d -- the base reports the same message, but in other files. Either this'
          '\nfile now uses a library this check cannot see (fine), or it is a real error. Read each.' % count(to_file))
    show(to_file)
if repeats:
    print('\n(%d more are repeats of a message the same file already had in the base: uses of libraries'
          '\n this check cannot see. Not listed.)' % repeats)
if to_tree or to_file:
    print('\nRESULT: %s' % ('new compiler errors' if to_tree else 'nothing certain, but read the list above'))
    sys.exit(1)
print('\nRESULT: OK -- no diagnostic that is new to its file.')
print('Not a verdict: code that touches AndroidX, Compose, Gson or R is not checked. Push and read the run.')
PY
