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
# While the IME is up, settings rows that would render inside this bottom band are not on screen
# and must not win a hit test against a key. (The toolbar itself lives at the TOP of the IME
# frame -- see ime_nodes -- but the keyboard's body occludes the bottom of the screen.)
IME_BAND_TOP = SCREEN_H - 130
IME_SHEET_TOP = 1488   # top of the IME window (ime_frame); an open drawer sheet ends here
# The toolbar's height in px: 40dp at the 420dpi `wm density` reports. This is the band 7e's
# coordinate fallback and 7g's more-key tap compute their points inside.
TOOLBAR_H = 105
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
    ("input", "\u2328 Input Behavior",
     [("button", "Manage Keyboard Layouts"), ("button", "Extra Keys Configuration"),
      ("dropdown", "Voice Typing"), ("switch", "Dictation Self-Test"),
      ("switch", "Auto-Capitalisation"), ("switch", "Autocorrect")]),
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
    ("Dictation Self-Test", "input"),
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


def chrome_rows(s):
    """Pinned chrome: app bar (title + hamburger) and the search bar / results under it.

    Phase 4 part 4 pinned all of this above the pane -- the hamburger and the bar NEVER scroll
    away, which is the whole point of the redesign and the property every Tier-1/Tier-2b hunt
    now relies on. The real results Card caps at 200dp and scrolls internally; the fake lists
    results flat, which is fine because every query the smoke types matches at most two rows.
    """
    out = [("title", "Settings", 90, None), ("hamburger", "", 90, None)]
    if not s.get("no_search_bar"):
        # The bar renders whatever the field holds: the placeholder while empty, the typed query
        # while searching, and -- the thing 7e asserts on -- the transcript dictation committed.
        out.append(("searchbar", s.get("field_text") or "Search settings\u2026", 144, None))
    q = s.get("query", "")
    if q and not s.get("search_broken"):
        for title, sec in INDEX:
            if q.lower() in title.lower():
                out.append(("result", title, 96, sec))
    return out


def drawer_rows(s):
    """The open ModalNavigationDrawer sheet: its title and every section item.

    The sheet replaces the whole screen in a dump. This SECTIONS order need not match the
    product's SettingsDrawerItems.ORDER; the smoke hunts match items by title, never position.
    """
    out = [("drawertitle", "Settings", 90, None)]
    for key, header, _kids in SECTIONS:
        out.append(("draweritem", header, 100, key))
    return out


def pane_rows(s):
    """The one section the drawer last selected (default testKeyboard), header first."""
    sel = s.get("selected_section") or "testKeyboard"
    out = []
    for key, header, kids in SECTIONS:
        if key != sel:
            continue
        out.append(("header", header, 130, key))
        if s["expanded"].get(key):
            for kind, text in kids:
                out.append((kind, text, 170 if kind == "slider" else 150, key))
    return out


def max_scroll(s):
    """How far the scrollable region can still move up.

    Closed screen: only the pane scrolls, in the viewport that is left below the pinned chrome.
    Open drawer: the sheet scrolls in ITS viewport -- above the keyboard top when the IME is up
    (the activity is adjustResize, so the sheet cannot extend under the keyboard), else the full
    screen. The closed-screen case deliberately keeps the old IME_BAND_TOP suppression model
    instead of resizing the pane: a documented fake simplification the scenarios calibrate to.
    """
    if s.get("drawer_open"):
        view = (IME_SHEET_TOP if s.get("ime_shown") else SCREEN_H) - VIEW_TOP
        return max(0, sum(r[2] + 16 for r in drawer_rows(s)) - view)
    chrome = sum(r[2] + 16 for r in chrome_rows(s))
    pane = sum(r[2] + 16 for r in pane_rows(s))
    return max(0, chrome + pane - VIEW_H)


def laid_out(s):
    """(kind, text, height, section, top) for every row currently laid out on the settings screen.

    Pinned chrome keeps its position regardless of scroll; only the pane (closed screen) or the
    sheet (open drawer) moves. An open drawer sheet ends at the IME top when the keyboard is up,
    so rows below that are not rendered -- the sheet scrolls instead, exactly like the resized
    ModalDrawerSheet on a real adjustResize activity.
    """
    out = []
    y = VIEW_TOP
    if s.get("drawer_open"):
        for kind, text, h, sec in drawer_rows(s):
            out.append((kind, text, h, sec, y - s["scroll"]))
            y += h + 16
        return out
    for kind, text, h, sec in chrome_rows(s):
        out.append((kind, text, h, sec, y))
        y += h + 16
    for kind, text, h, sec in pane_rows(s):
        out.append((kind, text, h, sec, y - s["scroll"]))
        y += h + 16
    return out


def settings_nodes(s):
    n = []
    for kind, text, h, _sec, top in laid_out(s):
        if not (-h < top < SCREEN_H):
            continue
        band = IME_SHEET_TOP if s.get("drawer_open") else IME_BAND_TOP
        if s.get("ime_shown") and top + h > band:
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
        if kind == "hamburger":
            # the TopAppBar navigation icon: no text at all, found by its content-desc, which is
            # what the smoke's nodecenter_desc 'sections menu' taps
            n.append((top, '<node index="%d" text="" resource-id="" class="android.widget.ImageView" '
                           'package="%s" content-desc="Open sections menu" checkable="false" checked="false" '
                           'clickable="true" enabled="true" focusable="true" focused="false" '
                           'scrollable="false" bounds="[16,%d][120,%d]" />'
                      % (len(n), s["pkg"], y1, y2)))
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


def ime_frame(s):
    """The input-method window's frame -- the same rect the fake's `dumpsys window` reports."""
    floating = bool(s["checked"].get("Floating Keyboard")) and s.get("window_mode", "narrow") != "full"
    return [108, 1488, 972, 2340] if floating else [0, 1488, 1080, 2400]


def ime_nodes(s, for_hit=False):
    """The keyboard's own nodes. Only composed while the IME is up, as on a device.

    for_hit=True composes them even under no_ime_nodes: a finger touches what the screen
    renders, not what the accessibility dump exposes. Run 40's emulator renders the toolbar --
    taps land and logcat proves it -- while its dumps carry no IME nodes at all, and the fake
    must keep those two channels independently blind or the coordinate routes cannot be tested.
    """
    if not s.get("ime_shown") or (s.get("no_ime_nodes") and not for_hit):
        # no_ime_nodes models what runs 39 and 40's emulator actually produced: dumpsys says the
        # IME is shown, yet the accessibility dump carries none of its nodes.
        return []
    left, top, right, bottom = ime_frame(s)
    # The view fills its window, so its bottom edge equals the frame's -- unless kb_overflow says
    # the measure pass over-reserved the nav inset, which is the defect 7f exists to catch.
    view_bottom = bottom + (132 if s.get("kb_overflow") else 0)
    # Known simplification: the root's TOP stays at the occlusion band, not the frame top. A real
    # root spans the whole frame and would swallow every hunt tap on a settings row below the
    # frame top -- rows the real settings window never renders there anyway (adjustResize ends
    # the list at the keyboard), but which this fake still exposes down to IME_BAND_TOP. Nothing
    # reads the root's top edge: 7f measures the bottom, DUMPDIAG counts classes.
    n = [(IME_BAND_TOP - 1,
          '<node index="0" text="" resource-id="" class="tribixbite.cleverkeys.Keyboard2View" '
          'package="%s" content-desc="" checkable="false" checked="false" clickable="true" '
          'enabled="true" focusable="true" focused="false" scrollable="false" '
          'bounds="[%d,%d][%d,%d]" />' % (s["pkg"], left, IME_BAND_TOP, right, view_bottom))]
    # The toolbar: KeyboardToolbar's SEVEN default buttons (ToolbarConfig.DEFAULT_KEYS_STRING;
    # phase 4 part 5 cut the old ten to Gboard's seven), evenly weighted across the top 40dp of
    # the frame -- the geometry 7e/7g's coordinate taps compute against (TB_N=7), so a formula bug
    # fails here in thirty seconds instead of on the emulator in fifteen minutes. More Tools holds
    # position 0 and the mic is LAST (index 6: voice_typing is 7th of 7). Keeping the mic at the
    # old index 1 of ten is exactly the stale model that let a green run prove nothing: with
    # TB_N=7 the computed mic point (13/14 of the frame width) landed on "Cursor Right" instead.
    # no_toolbar_mic deletes the mic and the rest re-space, like a user removing the button.
    descs = ["More Tools", "Undo", "Select Word", "Clipboard", "Settings", "GIF", "Voice Typing"]
    if s.get("no_toolbar_mic"):
        descs.remove("Voice Typing")
    slot = (right - left) / len(descs)
    for i, desc in enumerate(descs):
        n.append((IME_BAND_TOP,
                  '<node index="%d" text="" resource-id="" class="android.widget.ImageButton" '
                  'package="%s" content-desc="%s" checkable="false" checked="false" '
                  'clickable="true" enabled="true" focusable="true" focused="false" '
                  'scrollable="false" bounds="[%d,%d][%d,%d]" />'
                  % (len(n), s["pkg"], esc(desc),
                     int(left + i * slot), top, int(left + (i + 1) * slot), top + TOOLBAR_H)))
    return n


def more_tools_sheet(s):
    """What the more key does (7g): the service logs the press, then the sheet it shows.

    mt_deaf models the one defect 7g exists to catch -- the command dispatched, the dialog never
    came up: press in logcat, no shown line. The state in the shown line mirrors the five rows
    MoreToolsSheet.rows carries, read from the same switches 7d toggles.
    """
    s.setdefault("loglines", []).append("I MoreToolsSheet: press")
    if s.get("mt_deaf"):
        return
    st = s["checked"]
    s["loglines"].append(
        "I MoreToolsSheet: shown rows=5 " + " ".join(
            "%s=%s" % (label, "on" if st.get(name) else "off")
            for label, name in (("incognito", "Incognito Mode"), ("floating", "Floating Keyboard"),
                                ("split", "Split Keyboard"), ("one_handed", "One-Handed Mode"),
                                ("sound", "Key Sound"))))


def voice_mic(s):
    """What the mic key does, per DictationPolicy: self-test commits, everything else falls back.

    The emulator image has no speech service, so the recogniser is never available here; that is
    why the decision line is recorded at all -- it is the evidence 7e ships as VOICE-DIAG.
    """
    selftest = bool(s["checked"].get("Dictation Self-Test"))
    if selftest and not s.get("voice_deaf"):
        decision = "COMMIT_SELF_TEST"
    elif s.get("voice_ime_available"):
        decision = "SWITCH_TO_VOICE_IME"
    else:
        decision = "REPORT_NO_VOICE_INPUT"
    s.setdefault("loglines", []).append(
        "I DictationController: press mode=DICTATION selfTest=%s listening=false recognizer=%s "
        "permission=%s voiceIme=%s decision=%s"
        % ("true" if selftest else "false",
           "true" if s.get("voice_recognizer") else "false",
           "true" if s.get("permission_granted") else "false",
           "true" if s.get("voice_ime_available") else "false",
           decision))
    if decision == "COMMIT_SELF_TEST":
        # DictationPolicy.commitPrefix: a joining space only after real text.
        prefix = " " if s.get("field_text") else ""
        s["field_text"] = (s.get("field_text") or "") + prefix + "voice dictation self test"


def nodes(s, for_hit=False):
    if s["screen"] == "launcher":
        return launcher_nodes(s)
    if s["screen"] != "settings":
        return [(0, '<node index="0" text="Theme preview" class="android.view.View" package="%s" '
                    'bounds="[0,0][1080,2400]" />' % s["pkg"])]
    return settings_nodes(s) + ime_nodes(s, for_hit)


def dump(s, for_hit=False):
    body = "\n".join(t for _, t in sorted(nodes(s, for_hit), key=lambda p: p[0]))
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
            '<hierarchy rotation="0">\n%s\n</hierarchy>\n' % body)


def hit(s, x, y):
    # The LAST node containing the point, not the first: the keyboard view node spans the whole
    # IME band including its toolbar, and a tap must land on the key inside it, the way a real
    # dispatch lands on the deepest view. Non-overlapping nodes -- everything else the model
    # emits -- have the same first and last match, so this changes nothing for them.
    found = None
    # for_hit: a finger touches what the screen renders, not what the dump exposes -- under
    # no_ime_nodes the toolbar is invisible to `uiautomator dump` but still takes the tap.
    for m in re.finditer(r'<node[^>]*>', dump(s, for_hit=True)):
        tag = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x1 <= x <= x2 and y1 <= y <= y2:
            found = tag
    return found


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
    """The control's y inside the PANE content; scrolling to it puts it at the pane top."""
    y = 0
    for kind, text, h, _sec in pane_rows(s):
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
    # logcat is a HOST-side adb command -- the smoke script calls `adb logcat`, not
    # `adb shell logcat` -- so it must be served before the shell gate below. Until this existed
    # the fake swallowed every logcat call, which is why VOICE-DIAG always read log='none' even
    # in scenarios where voice_mic had appended the controller's press line.
    if a[:1] == ["logcat"]:
        save(s)
        for line in s.get("loglines", []):
            print(line)
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
        s["field_text"] = s["query"]
        save(s)
        return 0

    if cmd[:2] == ["input", "keyevent"]:
        if cmd[2] == "KEYCODE_BACK" and s["screen"] == "settings":
            if s.get("drawer_open"):
                # ModalNavigationDrawer: BACK closes the sheet before anything else consumes it
                s["drawer_open"] = False
                s["scroll"] = 0
            elif s.get("ime_shown"):
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
            desc = re.search(r'content-desc="([^"]*)"', tag)
            if desc and unesc(desc.group(1)) == "Voice Typing":
                # 7e: the toolbar's mic key, not the settings row that shares its label.
                voice_mic(s)
                save(s)
                return 0
            if desc and unesc(desc.group(1)) == "More Tools":
                # 7g: the toolbar's more key dispatches more_tools; press, then shown.
                more_tools_sheet(s)
                save(s)
                return 0
            if desc and unesc(desc.group(1)) == "Open sections menu" and s["screen"] == "settings":
                # phase 4 part 4: the TopAppBar hamburger opens the drawer sheet. The sheet has
                # its own scroll state, fresh at its top on every open (the single s["scroll"]
                # variable is shared: a documented fake simplification, reset on open/close).
                s["drawer_open"] = True
                s["scroll"] = 0
                save(s)
                return 0
            if s["screen"] == "launcher":
                if "EditText" in cls:
                    s["focused_field"] = "launcher"
                    s["ime_shown"] = True
            elif "EditText" in cls:
                # the settings search bar is an OutlinedTextField, so focusing it shows the IME
                # exactly like the launcher's test field does -- that is what 7d's frame probe uses
                s["focused_field"] = "search"
                s["ime_shown"] = True
            elif s.get("drawer_open"):
                # a tap while the sheet is open: an item selects AND expands its section, closes
                # the drawer, and starts the pane at its top; any other tap just closes it
                for key, header, _kids in SECTIONS:
                    if text == header:
                        s["selected_section"] = key
                        s["expanded"][key] = True
                        break
                s["drawer_open"] = False
                s["scroll"] = 0
            elif (s.get("query") and not s.get("search_broken")
                  and text in [t for t, _ in INDEX]):
                # executeSearchAction: clear the query, collapse everything, expand the target
                # section, then scroll the control to the top of the viewport.
                sec = next(sec for t, sec in INDEX if t == text)
                s["query"] = ""
                s["field_text"] = ""
                for k in s["expanded"]:
                    s["expanded"][k] = False
                s["expanded"][sec] = True
                # executeSearchAction also SELECTS the section now: the pane shows one section
                s["selected_section"] = sec
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
        modern = s.get("frame_format") == "modern"
        mode = s.get("window_mode", "narrow")
        frame = ime_frame(s)

        def win(num, title, rect, ime=False):
            print("  Window #%d Window{%s u0 %s}" % (num, hex(0xaaa + num)[2:], title))
            if ime:
                print("    mAttrs={(%d,%d)(wrap x wrap) sim={adjust=resize} ty=INPUT_METHOD fl=81820100}"
                      % (rect[0], rect[1]))
            if modern:
                print("    Frames: containing=[0,0][1080,2400] parent=[0,0][1080,2400]")
                print("    frame=[%d,%d][%d,%d]" % tuple(rect))
            else:
                print("    mFrame=[%d,%d][%d,%d]" % tuple(rect))

        print("WINDOW MANAGER WINDOWS")
        win(3, "StatusBar", [0, 0, 1080, 73])
        if s.get("ime_shown"):
            # how the IME window is titled, and in which dialect, is what run 39877368710 could not
            # tell us: conf=0 with an empty frame means neither the header nor the key was as guessed
            if mode == "pkg":
                win(2, "%s/tribixbite.cleverkeys.CleverKeysService" % s["pkg"], frame, ime=modern)
            else:
                win(2, "InputMethodService", frame, ime=modern)
        win(1, "com.android.launcher3/QuickstepLauncher", [0, 0, 1080, 2400])
        return 0

    if cmd[:2] == ["dumpsys", "window"]:
        save(s)
        act = {"settings": "SettingsActivity", "launcher": "LauncherActivity"}.get(s["screen"], "Other")
        print("mCurrentFocus=Window{f0cus u0 %s/tribixbite.cleverkeys.%s}" % (s["pkg"], act))
        return 0

    if cmd[:2] == ["wm", "size"]:
        save(s)
        print("Physical size: %dx%d" % (SCREEN_W, SCREEN_H))
        return 0
    if cmd[:2] == ["wm", "density"]:
        save(s)
        print("Physical density: 420")
        return 0
    if cmd[:2] == ["settings", "get"]:
        save(s)
        print("%s/tribixbite.cleverkeys.CleverKeysService" % s["pkg"])
        return 0
    if cmd[:2] == ["pm", "grant"]:
        s["permission_granted"] = True
        save(s)
        return 0
    if cmd[:1] == ["logcat"]:
        save(s)
        for line in s.get("loglines", []):
            print(line)
        return 0
    save(s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
