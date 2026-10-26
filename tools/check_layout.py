import xml.etree.ElementTree as ET
import sys, os, glob, re

layout_file_name = 0
warnings = []
# Warnings that must break the build. Other checks remain advisory because the
# shipped non-Latin layouts intentionally omit ASCII punctuation/digits.
fatal = []

KNOWN_NOT_LAYOUT = set([
    "number_row", "numpad", "pin",
    "bottom_row", "settings", "method",
    "greekmath", "numeric", "emoji_bottom_row",
    "clipboard_bottom_row" ])

KEY_ATTRIBUTES = set([
    "key0", "key1", "key2", "key3", "key4", "key5", "key6", "key7", "key8",
    "c", "nw", "ne", "sw", "se", "w", "e", "n", "s"
    ])

# Keys defined in KeyValue.kt
known_keys = set()

def warn(msg):
    warnings.append("%s: %s" % (layout_file_name, msg))

def key_list_str(keys):
    return ", ".join(sorted(list(keys)))

def canonical_key_value(raw):
    """Resolve the layout's `label:key` syntax to the underlying key name.

    `?123:switch_numeric` is one key whose centre label is "?123"; treating the
    whole string as a key name made the bottom-row check ask for a second
    `switch_numeric` that was already present. Single-character ":" values and
    parser expressions such as `keyevent:123` are left alone.
    """
    k = raw.removeprefix("\\")
    labelled = re.fullmatch(r"([^:]+):([A-Za-z_][A-Za-z0-9_]*)", k)
    return labelled.group(2) if labelled else k

def missing_some_of(keys, symbols, class_name=None):
    if class_name is None:
        class_name = "of [" + ", ".join(symbols) + "]"
    missing = set(symbols).difference(keys)
    if len(missing) > 0 and len(missing) != len(symbols):
        warn("Layout includes some %s but not all, missing: %s" % (
            class_name, key_list_str(missing)))

def missing_required(keys, symbols, msg):
    missing = set(symbols).difference(keys)
    if len(missing) > 0:
        warn("%s, missing: %s" % (msg, key_list_str(missing)))

def unexpected_keys(keys, symbols, msg):
    unexpected = set(symbols).intersection(keys)
    if len(unexpected) > 0:
        warn("%s, unexpected: %s" % (msg, key_list_str(unexpected)))

# Write to [keys] and [dup].
def parse_row_from_et(row, keys, dup):
    for key in row:
        for attr in key.keys():
            if attr in KEY_ATTRIBUTES:
                k = canonical_key_value(key.get(attr))
                if k in keys: dup.add(k)
                keys.add(k)

def parse_layout(fname):
    keys = set()
    dup = set()
    root = ET.parse(fname).getroot()
    if root.tag != "keyboard":
        return None
    for row in root:
        parse_row_from_et(row, keys, dup)
    return root, keys, dup

def parse_row(fname):
    keys = set()
    dup = set()
    root = ET.parse(fname).getroot()
    if root.tag != "row":
        return None
    parse_row_from_et(root, keys, dup)
    return root, keys, dup

def check_layout(layout):
    root, keys, dup = layout
    if len(dup) > 0:
        # KeyboardData.getKeys() is a Map<KeyValue, KeyPos>, so the second
        # occurrence silently overwrites the first position. This is a real
        # layout bug, not a style warning.
        msg = "Duplicate keys: " + key_list_str(dup)
        warn(msg)
        fatal.append("%s: %s" % (layout_file_name, msg))
    missing_some_of(keys, "~!@#$%^&*(){}`[]=\\-_;:/.,?<>'\"+|", "ASCII punctuation")
    missing_some_of(keys, "0123456789", "digits")
    missing_required(keys, ["backspace", "delete"], "Layout doesn't define some important keys")
    unexpected_keys(keys,
                    ["copy", "paste", "cut", "selectAll", "shareText",
                     "pasteAsPlainText", "undo", "redo" ],
                    "Layout contains editing keys")
    unexpected_keys(keys,
                    [ "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9",
                     "f10", "f11", "f12" ],
                    "Layout contains function keys")
    unexpected_keys(keys, [""], "Layout contains empty strings")
    unexpected_keys(keys, ["loc"], "Special keyword cannot be a symbol")
    unexpected_keys(keys, filter(lambda k: k.strip()!=k, keys), "Some keys contain whitespaces")
    unexpected_keys(keys, ["f11_placeholder", "f12_placeholder"], "These keys are now added automatically")

    if root.get("script", "latin") == "latin":
        missing_required(keys, ["shift", "loc capslock"], "Missing important key")
        missing_required(keys, ["loc esc", "loc tab"], "Missing programming keys")

    _, bottom_row_keys, _ = parse_row("res/xml/bottom_row.xml")

    if root.get("bottom_row") == "false":
        missing_required(keys, bottom_row_keys,
                         "Layout redefines the bottom row but some important keys are missing")
    else:
        unexpected_keys(keys, bottom_row_keys,
                        "Layout contains keys present in the bottom row")

    if root.get("script") == None:
        warn("Layout doesn't specify a script.")

    keys_without_loc = set(( k.removeprefix("loc ") for k in keys ))
    # Keys with a len under 3 are often composed characters
    special_keys = set(( k for k in keys_without_loc if len(k) > 3 and ":" not in k ))
    unknown = special_keys.difference(known_keys)
    if len(unknown) > 0:
        warn("Layout contains unknown keys: %s" % key_list_str(unknown))

# Fill 'known_keys', which is used for some checks
def parse_known_keys():
    global known_keys
    with open("src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt", "r") as f:
        # This tree is Kotlin, not Java: getSpecialKeyByName is a `when` whose
        # branches are `"name" -> ...`. The old regexes only matched Java
        # `case` / `namedKeys[...]` syntax and therefore reported EVERY special
        # key as unknown.
        content = f.read()
        keys = set()
        # Collect every string literal on a `when` branch line, including
        # comma-separated alternatives such as `"zwnj", "halfspace" ->`.
        for line in content.splitlines():
            if "->" in line:
                keys.update(re.findall(r'"((?:\\.|[^"\\])*)"', line))
        keys.update(m.group(1) for m in re.finditer('case "([^"]+)":', content))
        keys.update(m.group(1) for m in re.finditer('namedKeys\\["([^"]+)"\\]', content))
        known_keys = keys

parse_known_keys()


def check_gboard_default():
    """Fail the build if the shipped default stops matching the Gboard reference screenshot.

    The owner asked (2026-10-26) for a keyboard that "matches the Gboard screenshot": a clean
    digit row, plain lowercase QWERTY, one 7-icon toolbar and a minimal Gboard bottom row. The
    layout file latn_qwerty_us_gboard_style.xml delivers that, but a layout file alone is not a
    default: three other places decide what a fresh install / English locale / settings preview
    actually shows. These checks run at preBuild (assembleDebug, and therefore CI), so the whole
    chain is pinned here rather than only in a JVM test that CI does not run.
    """
    def fail(msg):
        fatal.append("gboard-default: " + msg)

    gb = "latn_qwerty_us_gboard_style"
    layout_path = "src/main/layouts/%s.xml" % gb

    # (1) The layout itself must stay the calm Gboard arrangement.
    try:
        root = ET.parse(layout_path).getroot()
    except Exception as ex:  # missing / broken file is itself a regression
        fail("cannot parse %s: %s" % (layout_path, ex))
        return
    if root.get("embedded_number_row") != "true":
        fail("%s lost embedded_number_row=true (digit row would be doubled or missing)" % gb)
    if root.get("bottom_row") != "false":
        fail("%s lost bottom_row=false (the Ctrl/Fn/123+ PC row would come back)" % gb)
    rows = list(root)
    centers = []
    for row in rows:
        centers.append([canonical_key_value(k.get("c")) for k in row if k.get("c") is not None])
    if not centers or centers[0] != list("1234567890"):
        fail("%s first row is not the clean 1234567890 digit row: %s" % (gb, centers[0] if centers else None))
    # The 26 letter keys must carry NO corner hints (Gboard's calm look). `loc` placement
    # markers never render. Non-letter keys (shift/backspace/space/enter) legitimately keep
    # functional corner commands — CornerLabelPolicy hides those because their centre is not a
    # character, so they never appear as clutter on screen.
    for i, row in enumerate(rows):
        for k in row:
            c = k.get("c")
            if c is None or not re.fullmatch(r"[a-z]", c):
                continue
            for attr in ("nw", "ne", "sw", "se", "w", "e", "n", "s"):
                v = k.get(attr)
                if v is not None and not v.startswith("loc "):
                    fail("%s letter key %r has a visible corner hint %r (clutter)" % (gb, c, v))

    # (2) method.xml: the generic `en` and the English regional subtypes must all resolve to it.
    try:
        method = open("res/xml/method.xml", encoding="utf-8").read()
    except Exception as ex:
        fail("cannot read res/xml/method.xml: %s" % ex)
        return
    subtypes = re.findall(r"<subtype\b[^>]*/>", method)
    en_default = {}
    for s in subtypes:
        tag = re.search(r'android:languageTag="([^"]+)"', s)
        lay = re.search(r"default_layout=([^,\"]+)", s)
        if tag and tag.group(1).startswith("en"):
            en_default[tag.group(1)] = lay.group(1) if lay else None
    if "en" not in en_default or en_default.get("en") != gb:
        fail("generic en subtype default_layout is %r, expected %r" % (en_default.get("en"), gb))
    for tag in ("en-US", "en-GB", "en-GH", "en-IN", "en-AU", "en-CA"):
        if tag not in en_default:
            fail("missing English subtype %s" % tag)
        elif en_default[tag] != gb:
            fail("subtype %s default_layout is %r, expected %r" % (tag, en_default[tag], gb))
    if not any('android:imeSubtypeLocale="en_GH"' in s for s in subtypes):
        fail("no en-GH (Ghana) subtype declared")

    # (3) The no-subtype runtime fallback must be the same clean layout, not the dense one.
    try:
        graph = open("src/main/kotlin/tribixbite/cleverkeys/wiring/KeyboardComponentGraph.kt",
                     encoding="utf-8").read()
    except Exception as ex:
        fail("cannot read KeyboardComponentGraph.kt: %s" % ex)
        return
    if "R.raw.%s" % gb not in graph:
        fail("no-subtype fallback in KeyboardComponentGraph no longer loads R.raw.%s" % gb)
    if re.search(r"\?:\s*KeyboardData\.load\(resources,\s*R\.raw\.latn_qwerty_us\)", graph):
        fail("no-subtype fallback fell back to the dense latn_qwerty_us again")

    # (4) Settings/theme previews must preview the keyboard the user actually types on.
    previews = [
        "src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewHost.kt",
        "src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewView.kt",
        "src/main/kotlin/tribixbite/cleverkeys/theme/ThemeEditorPreview.kt",
    ]
    for p in previews:
        try:
            src = open(p, encoding="utf-8").read()
        except Exception as ex:
            fail("cannot read preview %s: %s" % (p, ex))
            continue
        if "R.raw.%s" % gb not in src:
            fail("preview %s no longer loads R.raw.%s" % (p, gb))


check_gboard_default()

for fname in sorted(glob.glob("src/main/layouts/*.xml")):
    layout_id, _ = os.path.splitext(os.path.basename(fname))
    if layout_id in KNOWN_NOT_LAYOUT:
        continue
    layout_file_name = layout_id
    layout = parse_layout(fname)
    if layout == None:
        warn("Not a layout file")
    else:
        check_layout(layout)

with open("check_layout.output", "w") as out:
    for w in warnings:
        print(w, file=out)

if fatal:
    for f in fatal:
        print("check_layout.py: " + f, file=sys.stderr)
    sys.exit(1)
