# How to send me the diagnostics

There is nothing to build or install for this. The settings screen already has a copy button,
added for exactly this purpose.

## The short version

1. Open the keyboard's **Settings**.
2. Go to **📋 Information & Actions**.
3. Find the **⏱ Keyboard speed** card.
4. **Long-press it.** A toast says *"Keyboard speed copied"* and the whole report is on your
   clipboard.
5. Paste it to me.

Tap the card first if you want it re-read at that moment; tap refreshes, long-press copies.

## Why long-press and not a button

The card's own text is the content, so there is no separate "copy" affordance to hunt for — the
hint line at the bottom of the card names both gestures. If the long-press does nothing, that is
itself a bug worth telling me about.

## What is in the report

Numbers and counts only. Nothing you type is recorded, and nothing is sent anywhere — it goes to
your clipboard and stays there until you paste it.

The lines cover:

- **Build** — version, commit, and whether the build is debuggable. Check this first: the commit
  tells me exactly which code produced the numbers.
- **Starting** — how long set-up took, and the four slowest set-up steps by name.
- **Opening** — how many times the keyboard opened, average and worst.
- **The keyboard's own work in an opening** — the part that is this app's code, as opposed to the
  system drawing the window.
- **Key presses** — count, average and worst handling time.
- **Frames** — count, average, worst, and how many were slower than 16.7 ms.
- **The keyboard's own layout** — measure and layout timings.
- **Suggestions and text reads** — letter-to-suggestions and after-caret-read timings.
- **While open** — height changes, key re-attachments, view re-installs.

There is also a line above the build about how often the keyboard had to be set up from nothing
lately, which is the first thing to look at if it feels slow: a phone that kills the keyboard
pays set-up cost every time you open it.

## Two things that make the numbers trustworthy

- **Open the keyboard and type a bit before you copy.** The report is "since the keyboard last
  started"; on a fresh install with nothing typed it will honestly say so rather than invent
  figures.
- **Do not restart the phone between using the keyboard and copying.** A restart empties the
  counters, and the card will say the keyboard has not been opened since.

## If the copy is too long to paste

Paste it in as many pieces as it takes, or just the **Build** line plus the **Frames** and
**Key presses** lines — that is enough for me to tell slow-from-fast. The Build line alone tells
me which build you are on, so send that even if nothing else.
