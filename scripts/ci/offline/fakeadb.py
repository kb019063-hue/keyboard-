#!/usr/bin/env python3
"""A fake `adb` that models just enough of an emulator to test sections 7b/7d of
`scripts/ci/emulator-smoke.sh` without one.

CI cannot be debugged interactively: logs are served from a host outside the dev sandbox's
allowlist, artifact contents come from Azure blob storage (also outside it), and a smoke run costs
about fifteen minutes. Annotations and artifact NAMES are the only channels that get through, which
makes every failed run expensive. This model exists so a change to the hunt logic can be checked
against nine scenarios in twenty seconds instead.

What it models, and why each detail is there:

* A settings screen whose rows live in ONE `verticalScroll` Column (`SettingsScreen.kt:106-139`), so
  the search bar scrolls away with the content and a climb-to-top is required before anything else.
* Viewport pruning: rows outside the visible band are absent from the dump, which is what makes
  "below the fold" the dominant failure mode the real suite keeps hitting.
* Collapsed sections compose NO children, expanded ones compose all of them.
* The search bar, its result rows, and `executeSearchAction`'s collapse-all / expand-one /
  scroll-the-control-to-the-top behaviour (`SettingsSearch.kt:42-67`).
* A `SettingsSwitch` row as TWO nodes -- a `TextView` title with `checkable="false"` and an
  unlabelled `Switch` carrying the state -- because that is what run 39789396340's annotation
  reported from a real device, and reading the title node instead silently reports `false` forever.
* Text fields bring the IME up -- the settings search bar as well as the launcher's test field,
  because `am start` from a shell implies NEW_TASK and run 39804287846 proved that navigating away
  to find an editor does not actually navigate anywhere.
* BACK with an IME shown hides the keyboard and keeps the activity; only a second BACK pops it.
* `dumpsys window windows` whose input-method frame narrows only when the floating switch is on.

Swipes apply NO fling momentum, so a device that flings further than it is dragged still passes
what passes here. State lives in `$NFOFF_DIR/state.json`; every call is recorded so a failed
scenario can be replayed call by call.
"""
import json
import os
import re
import sys

NFOFF = os.environ.get("NFOFF_DIR", "/tmp/nfoff")
ST = os.path.join(NFOFF, "state.json")
SDCARD = os.path.join(NFOFF, "sdcard-ui.xml")

VIEW_TOP = 100      # screen y where the scrollable content starts
VIEW_H = 2300       # content px visible at once
SCREEN_W, SCREEN_H = 1080, 2400
PKG_DEFAULT = "tribixbite.cleverkeys.debug"


def load():
    with open(ST) as f:
        return json.load(f)


def save(s):
    with open(ST, "w") as f:
        json.dump(s, f)


# --- the settings list, shaped like the real one -------------------------------------------------
def appearance_children():
    kids = [("text", "Theme"), ("switch", "Dark Mode"), ("slider", "Key Border Width"),
            ("switch", "Show Number Row"), ("dropdown", "Keyboard Height"),
            ("text", "One-handed mode"), ("switch", "One-Handed Mode"),
            ("slider", "One-Handed Width"), ("switch", "Swipe Trail"),
            ("text", "Window"), ("switch", "Haptic Feedback"), ("slider", "Animation Speed")]
    # the phase-3 controls sit DEEP in the section, past the fold on a real device too
    kids += [("switch", "Floating Keyboard"), ("slider", "Floating Keyboard Width"),
             ("switch", "Split Keyboard"), ("slider", "Split Gap")]
    return kids


SECTIONS = [
    ("appearance", "\U0001f3a8 Appearance", appearance_children()),
    ("swipeTyping", "\U0001f446 Swipe Typing",
     [("switch", "Enable Swipe Typing"), ("slider", "Swipe Speed"), ("text", "Geometric Settings")]),
    ("toolbar", "\U0001f920 Toolbar",
     [("switch", "Show Toolbar"), ("button", "Add"), ("button", "Reset"),
      ("dropdown", "Toolbar Position"), ("slider", "Toolbar Height"), ("text", "10 / 12")]),
    ("oneHanded", "\u270b One-Handed", [("switch", "One-Handed Mode"), ("dropdown", "Side")]),
    ("input", "\u2328 Input Behavior", [("switch", "Auto-Capitalisation"), ("switch", "Autocorrect")]),
    ("swipeCorrections", "\U0001f4dd Corrections", [("switch", "Swipe Final Autocorrect")]),
    ("gestureTuning", "\U0001f3af Gesture Tuning", [("slider", "Long Press Delay"), ("text", "Calibrate")]),
    ("accessibility", "\u267f Accessibility", [("switch", "Key Sound"), ("slider", "Key Sound Volume")]),
    ("clipboard", "\U0001f4cb Clipboard", [("switch", "Clipboard History"), ("slider", "Retention")]),
    ("activities", "\U0001f4f1 Activities", [("text", "Themes"), ("text", "Dictionary"), ("text", "Layouts")]),
    ("multiLang", "\U0001f310 Multi-Language", [("switch", "Enable Multi-Language"), ("text", "Languages")]),
    ("gif", "\U0001f3ac GIF Panel", [("switch", "Enable GIF Panel"), ("button", "Import Pack")]),
    ("privacy", "\U0001f512 Privacy & Data",
     [("text", "Privacy intro paragraph"), ("switch", "Incognito Mode"),
      ("text", "On-device learning"), ("switch", "On-Device Learning"),
      ("button", "Forget Learned"), ("text", "Learned data"),
      ("text", "Collected Data"), ("switch", "Swipe Pattern Storage")]),
    ("advanced", "\U0001f527 Advanced", [("switch", "Verbose Logging"), ("text", "Custom Terminal")]),
    ("backupRestore", "\U0001f4be Backup & Restore", [("button", "Export"), ("button", "Import")]),
    ("swipeTrail", "\U0001f310 Swipe Trail", [("slider", "Trail Length")]),
    ("testKeyboard", "\U0001f9ea Test Keyboard", [("text", "Open Test Field")]),
    ("help", "\u2753 Help", [("text", "FAQ"), ("text", "What's New")]),
    ("info", "\u2139 Info", [("text", "Version"), ("text", "Licences")]),
]

# what the generated search index returns for the queries 7d uses, in index order
INDEX = [
    ("Floating Keyboard", "appearance"),
    ("Split Keyboard", "appearance"),
    ("Split Gap", "appearance"),
    ("Floating Keyboard Width", "appearance"),
    ("Incognito Mode", "privacy"),
    ("One-Handed Mode", "oneHanded"),
    ("Key Sound", "accessibility"),
]

SWITCHES = ["Incognito Mode", "Floating Keyboard", "Split Keyboard", "One-Handed Mode", "Key Sound",
            "Dark Mode", "Show Number Row", "Swipe Trail", "Haptic Feedback"]


def esc(t):
    out = []
    for ch in t:
        if ord(ch) > 126:
            out.append("&#%d;" % ord(ch))
        elif ch == "&":
            out.append("&amp;")
        elif ch == "<":
            out.append("&lt;")
        elif ch == '"':
            out.append("&quot;")
        else:
            out.append(ch)
    return "".join(out)


def unesc(t):
    return re.sub(r"&#(\d+);", lambda m: chr(int(m.group(1))), t).replace("&amp;", "&")


def rows(s):
    """Layout rows in content space: (kind, text, height, section|None)."""
    out = [("title", "Settings", 90, None), ("desc", "Everything about your keyboard", 60, None)]
    if not s.get("no_search_bar"):
        out.append(("searchbar", "Search settings\u2026", 144, None))
    q = s.get("query", "")
    if q and not s.get("search_broken"):
        for title, sec in INDEX:
            if q.lower() in title.lower():
                out.append(("result", title, 96, sec))
    for key, header, kids in SECTIONS:
        out.append(("header", header, 130, key))
        if s["expanded"].get(key):
            for kind, text in kids:
                out.append((kind, text, 170 if kind == "slider" else 150, key))
    return out


def max_scroll(s):
    return max(0, sum(r[2] + 16 for r in rows(s)) - VIEW_H)


def settings_nodes(s):
    n = []
    y = 0
    for kind, text, h, _sec in rows(s):
        top = VIEW_TOP + y - s["scroll"]
        y += h + 16
        if not (-h < top < SCREEN_H):
            continue
        y1, y2 = max(0, top), min(SCREEN_H, top + h)
        state = s["checked"].get(text)
        if kind == "switch" and not s.get("hide_checked") and state is not None:
            # two nodes, exactly as a real dump reports them (run 39789396340)
            n.append((top, '<node index="%d" text="%s" resource-id="" class="android.widget.TextView" '
                           'package="%s" content-desc="" checkable="false" checked="false" '
                           'clickable="true" enabled="true" focusable="false" focused="false" '
                           'scrollable="false" bounds="[16,%d][900,%d]" />'
                      % (len(n), esc(text), s["pkg"], top + 40, top + 110)))
            n.append((top, '<node index="%d" text="" resource-id="" class="android.widget.Switch" '
                           'package="%s" content-desc="" checkable="true" checked="%s" '
                           'clickable="true" enabled="true" focusable="false" focused="false" '
                           'scrollable="false" bounds="[900,%d][1064,%d]" />'
                      % (len(n) + 1, s["pkg"], "true" if state else "false", top + 45, top + 105)))
            continue
        cls = {"searchbar": "android.widget.EditText",
               "button": "android.widget.Button"}.get(kind, "android.view.View")
        togg = "" if s.get("hide_checked") else 'checkable="%s" checked="%s" ' % (
            "true" if kind == "switch" else "false", "true" if state else "false")
        n.append((top, '<node index="%d" text="%s" resource-id="" class="%s" package="%s" '
                       'content-desc="" %sclickable="true" enabled="true" focusable="%s" '
                       'focused="%s" scrollable="false" bounds="[16,%d][1064,%d]" />'
                  % (len(n), esc(text), cls, s["pkg"], togg,
                     "true" if kind == "searchbar" else "false",
                     "true" if (kind == "searchbar" and s.get("focused_field") == "search") else "false",
                     y1, y2)))
    return n


def launcher_nodes(s):
    cards = [("text", "Mr. Brown's Keyboard"), ("button", "Enable Keyboard"),
             ("button", "Select Keyboard"), ("text", "3"),
             ("button", "Calibrate Per-Key Gestures")]
    if not s.get("no_launcher_field"):
        cards.append(("field", "Test your new keyboard here"))
    n = []
    y = 120
    for kind, text in cards:
        h = 150
        top = y - s.get("launcher_scroll", 0)
        y += h + 30
        if not (-h < top < SCREEN_H):
            continue
        cls = "android.widget.EditText" if kind == "field" else (
            "android.widget.Button" if kind == "button" else "android.view.View")
        n.append((top, '<node index="%d" text="%s" resource-id="" class="%s" package="%s" '
                       'content-desc="" checkable="false" checked="false" clickable="true" '
                       'enabled="true" focusable="%s" focused="false" bounds="[32,%d][1048,%d]" />'
                  % (len(n), esc(text), cls, s["pkg"],
                     "true" if kind == "field" else "false",
                     max(0, top), min(SCREEN_H, top + h))))
    return n


def nodes(s):
    if s["screen"] == "launcher":
        return launcher_nodes(s)
    if s["screen"] != "settings":
        return [(0, '<node index="0" text="Theme preview" class="android.view.View" package="%s" '
                    'bounds="[0,0][1080,2400]" />' % s["pkg"])]
    return settings_nodes(s)


def dump(s):
    body = "\n".join(t for _, t in sorted(nodes(s), key=lambda p: p[0]))
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
            '<hierarchy rotation="0">\n%s\n</hierarchy>\n' % body)


def hit(s, x, y):
    for m in re.finditer(r'<node[^>]*>', dump(s)):
        tag = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x1 <= x <= x2 and y1 <= y <= y2:
            return tag
    return None


def row_title(s, tag):
    """The text of the row a node belongs to -- an unlabelled Switch inherits its title's."""
    text = re.search(r'text="([^"]*)"', tag)
    if text and unesc(text.group(1)):
        return unesc(text.group(1))
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        return ""
    _x1, y1, _x2, y2 = map(int, b.groups())
    for m in re.finditer(r'<node[^>]*>', dump(s)):
        other = m.group(0)
        t = re.search(r'text="([^"]*)"', other)
        ob = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', other)
        if not (t and ob and unesc(t.group(1))):
            continue
        ox1, oy1, ox2, oy2 = map(int, ob.groups())
        if oy1 <= (y1 + y2) // 2 <= oy2:
            return unesc(t.group(1))
    return ""


def control_y(s, title):
    y = 0
    for kind, text, h, _sec in rows(s):
        if text == title:
            return y
        y += h + 16
    return None


def main(argv):
    s = load()
    s.setdefault("calls", [])
    a = argv[1:]
    s["calls"] = (s["calls"] + [" ".join(a)])[-600:]

    if a[:1] in (["wait-for-device"], ["devices"]):
        save(s)
        print("device")
        return 0
    if a[:2] == ["exec-out", "screencap"]:
        save(s)
        sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\nfakeshot")
        return 0
    if a[:1] == ["pull"]:
        save(s)
        if len(a) > 2 and os.path.exists(SDCARD):
            with open(SDCARD) as f, open(a[2], "w") as g:
                g.write(f.read())
        return 0
    if a[:1] != ["shell"]:
        save(s)
        return 0
    cmd = a[1:]

    if cmd[:2] == ["uiautomator", "dump"]:
        with open(SDCARD, "w") as f:
            f.write(dump(s))
        save(s)
        print("UI hierchary dumped to: /sdcard/ui-tb.xml")
        return 0

    if cmd[:2] == ["input", "swipe"]:
        x1, y1, x2, y2 = map(int, cmd[2:6])
        dy = y2 - y1
        if s["screen"] == "settings":
            s["scroll"] = max(0, min(max_scroll(s), s["scroll"] - dy))
        else:
            s["launcher_scroll"] = max(0, min(1200, s.get("launcher_scroll", 0) - dy))
        save(s)
        return 0

    if cmd[:2] == ["input", "text"]:
        s["query"] = cmd[2].replace("%s", " ")
        save(s)
        return 0

    if cmd[:2] == ["input", "keyevent"]:
        if cmd[2] == "KEYCODE_BACK" and s["screen"] == "settings":
            if s.get("ime_shown"):
                # the input method consumes BACK first and hides; the activity survives
                s["ime_shown"] = False
                s["focused_field"] = None
            else:
                # BACK out of SettingsActivity lands on the launcher: the mistake run 39783242401
                # made, when it pressed BACK to "dismiss a keyboard" that was never up
                s["screen"] = "launcher"
                s["focused_field"] = None
        save(s)
        return 0

    if cmd[:2] == ["input", "tap"]:
        x, y = int(cmd[2]), int(cmd[3])
        tag = hit(s, x, y)
        text = row_title(s, tag) if tag else ""
        s["taps"] = s.get("taps", []) + ["%d,%d %s" % (x, y, text or "MISS")]
        if tag:
            cls = re.search(r'class="([^"]*)"', tag).group(1)
            if s["screen"] == "launcher":
                if "EditText" in cls:
                    s["focused_field"] = "launcher"
                    s["ime_shown"] = True
            elif "EditText" in cls:
                # the settings search bar is an OutlinedTextField, so focusing it shows the IME
                # exactly like the launcher's test field does -- that is what 7d's frame probe uses
                s["focused_field"] = "search"
                s["ime_shown"] = True
            elif (s.get("query") and not s.get("search_broken")
                  and text in [t for t, _ in INDEX]):
                # executeSearchAction: clear the query, collapse everything, expand the target
                # section, then scroll the control to the top of the viewport.
                sec = next(sec for t, sec in INDEX if t == text)
                s["query"] = ""
                for k in s["expanded"]:
                    s["expanded"][k] = False
                s["expanded"][sec] = True
                cy = control_y(s, text)
                if cy is not None and not s.get("no_scroll_to"):
                    s["scroll"] = max(0, min(max_scroll(s), cy))
            else:
                for key, header, _kids in SECTIONS:
                    if text == header:
                        s["expanded"][key] = not s["expanded"].get(key, False)
                        break
                else:
                    if text in s["checked"] and not s.get("tap_deaf"):
                        s["checked"][text] = not s["checked"][text]
        save(s)
        return 0

    if cmd[:2] == ["am", "start"]:
        comp = ""
        for i, tok in enumerate(cmd):
            if tok == "-n" and i + 1 < len(cmd):
                comp = cmd[i + 1]
        if "SettingsActivity" in comp:
            s["screen"] = "settings"          # bring-to-front: scroll and expansion survive
        elif "LauncherActivity" in comp:
            s["screen"] = "launcher"
            s["ime_shown"] = s.get("focused_field") == "launcher"
            s["focused_field"] = None
        else:
            s["screen"] = "other"
            s["focused_field"] = None
        print("Status: ok")
        save(s)
        return 0

    if cmd[:2] == ["dumpsys", "input_method"]:
        save(s)
        print("mInputShown=%s" % ("true" if s.get("ime_shown") else "false"))
        print("mCurId=%s/tribixbite.cleverkeys.CleverKeysService" % s["pkg"])
        return 0

    if cmd[:3] == ["dumpsys", "window", "windows"]:
        save(s)
        print("WINDOW MANAGER WINDOWS")
        print("  Window #3 Window{aaa u0 StatusBar}")
        print("    mFrame=[0,0][1080,73]")
        if s.get("ime_shown"):
            mode = s.get("window_mode", "narrow")
            if s["checked"].get("Floating Keyboard") and mode == "pkg":
                print("  Window #2 Window{bbb u0 %s/tribixbite.cleverkeys.CleverKeysService}" % s["pkg"])
                print("    mFrame=[108,1488][972,2340]")
            elif s["checked"].get("Floating Keyboard") and mode != "full":
                print("  Window #2 Window{bbb u0 InputMethodService}")
                print("    mFrame=[108,1488][972,2340]")
            else:
                print("  Window #2 Window{bbb u0 InputMethodService}")
                print("    mFrame=[0,1488][1080,2400]")
        print("  Window #1 Window{ccc u0 com.android.launcher3/QuickstepLauncher}")
        print("    mFrame=[0,0][1080,2400]")
        return 0

    if cmd[:2] == ["dumpsys", "window"]:
        save(s)
        act = {"settings": "SettingsActivity", "launcher": "LauncherActivity"}.get(s["screen"], "Other")
        print("mCurrentFocus=Window{f0cus u0 %s/tribixbite.cleverkeys.%s}" % (s["pkg"], act))
        return 0

    if cmd[:2] == ["settings", "get"]:
        save(s)
        print("%s/tribixbite.cleverkeys.CleverKeysService" % s["pkg"])
        return 0
    if cmd[:1] == ["logcat"]:
        save(s)
        return 0
    save(s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
