#!/usr/bin/env python3
"""Scenarios for the offline 7d harness, and what each one must produce.

`write <name>`  -> a fresh state.json for fakeadb.py
`expect <name>` -> "<fails> <nf-verdict> <frame-assertion> <required-warning-substring>"
`names`         -> every scenario, in run order

Each scenario is a way the real device has already behaved, or a way it could behave that must not
turn into a false accusation against the keyboard:

happy        everything works; the frame probe asserts and passes
run30        the state runs 39781367258 and 39783242401 died in: two sections left expanded by 7b/7c
             and the list scrolled to the bottom, so the search bar itself is off screen
nosearch     search renders no results, so the scroll fallback must carry all three checks, including
             the second header tap for a section an earlier check left open
noscrollto   executeSearchAction expands the section but the control never registers a scroll
             position, so the control is composed somewhere below the fold
nochecked    dumps expose no checked/checkable attributes at all -> the frame probe must stand down
             and say so, never guess
tapdeaf      the tap lands but the switch does not flip -> same: stand down, do not blame the product
framefull    floating IS confirmed on and the input-method window is still full width -> the one
             genuine defect this probe exists to catch, so it must FAIL
framepkg     the input-method window is titled with the package rather than InputMethodService and
             carries nothing else to identify it -> report, do not assert
framemodern  the Android 12+ dialect: no mFrame=, a lowercase frame= under a Frames: block, and the
             window identified only by ty=INPUT_METHOD inside it. This is the shape run 39877368710
             most likely met, since it reported conf=0 with an empty frame
noime        no text field anywhere to focus, so no window to measure -> report, do not assert
             (also loses the search route, so the scroll fallback has to carry all three checks)
voicedeaf    7e: the mic key is tapped with the self-test confirmed on but nothing reaches the field
             -> the one genuine dictation defect this check exists to catch, so it must FAIL
voicenokey   7e: no toolbar node carries the mic key's content-desc -> report, do not assert

Every scenario also runs 7e, so each one states the voice verdict it must produce: `ok` (the
transcript landed in the field), `sw` (the self-test switch never confirmed on, so the check stands
down), `key` (no mic node to tap), `commit` (tapped, nothing committed).
"""
import json
import os
import sys

NFOFF = os.environ.get("NFOFF_DIR", "/tmp/nfoff")

SWITCHES = ["Incognito Mode", "Floating Keyboard", "Split Keyboard", "One-Handed Mode", "Key Sound",
            "Dark Mode", "Show Number Row", "Swipe Trail", "Haptic Feedback", "Dictation Self-Test"]


def base():
    return {
        "pkg": "tribixbite.cleverkeys.debug",
        "screen": "settings",
        "scroll": 0,
        "launcher_scroll": 0,
        "expanded": {},
        "query": "",
        "checked": {k: False for k in SWITCHES},
        "focused_field": None,
        "ime_shown": False,
        "window_mode": "narrow",
        "frame_format": "mframe",
        "search_broken": False,
        "no_scroll_to": False,
        "hide_checked": False,
        "tap_deaf": False,
        "no_launcher_field": False,
        # 7e (phase 4 part 1): what the field holds, what the mic key logged, and the three ways the
        # dictation check can be starved of evidence. The emulator image has no speech service, so
        # voice_recognizer stays False everywhere and the self-test switch carries the assertion.
        "field_text": "",
        "loglines": [],
        "permission_granted": False,
        "voice_recognizer": False,
        "voice_ime_available": False,
        "no_toolbar_mic": False,
        "voice_deaf": False,
        "calls": [],
        "taps": [],
    }


# name -> (state mutations, expected fails, expected nf verdict,
#          expected frame assertion [pass|fail|none], expected voice verdict,
#          required warning substring -- LAST, because it is the one field that may hold spaces)
SCENARIOS = {
    "happy":       ({},                                                        0, "ok",  "pass", "ok", ""),
    "run30":       ({"expanded": {"appearance": True, "toolbar": True},
                     "scroll": 10 ** 6},                                       0, "ok",  "pass", "ok", ""),
    "nosearch":    ({"expanded": {"appearance": True, "toolbar": True},
                     "scroll": 10 ** 6, "search_broken": True},                0, "ok",  "pass", "ok", ""),
    "noscrollto":  ({"no_scroll_to": True},                                    0, "ok",  "pass", "ok", ""),
    "nochecked":   ({"hide_checked": True},                                    0, "ok",  "none", "sw", "NFFRAME skipped"),
    "tapdeaf":     ({"tap_deaf": True},                                        0, "ok",  "none", "sw", "NFFRAME skipped"),
    "framefull":   ({"window_mode": "full"},                                   1, "frm", "fail", "ok", ""),
    "framepkg":    ({"window_mode": "pkg"},                                    0, "ok",  "none", "ok", "conf=1"),
    "framemodern": ({"window_mode": "pkg", "frame_format": "modern"},          0, "ok",  "pass", "ok", "conf=2"),
    "noime":       ({"no_search_bar": True, "search_broken": True},             0, "ok",  "none", "key", "no text field"),
    "voicedeaf":   ({"voice_deaf": True},                                      1, "ok",  "pass", "commit", "verdict=commit"),
    "voicenokey":  ({"no_toolbar_mic": True},                                  0, "ok",  "pass", "key", "no toolbar node"),
}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    if cmd == "names":
        print(" ".join(SCENARIOS))
        return 0
    name = sys.argv[2]
    if name not in SCENARIOS:
        sys.stderr.write("unknown scenario %s\n" % name)
        return 2
    if cmd == "write":
        state = base()
        state.update(SCENARIOS[name][0])
        os.makedirs(NFOFF, exist_ok=True)
        with open(os.path.join(NFOFF, "state.json"), "w") as f:
            json.dump(state, f)
        return 0
    if cmd == "expect":
        _m, fails, nf, frame, voice, warn = SCENARIOS[name]
        print("%d %s %s %s %s" % (fails, nf, frame, voice, warn))
        return 0
    sys.stderr.write("unknown command %s\n" % cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main())
