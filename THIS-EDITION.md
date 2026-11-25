# ซับไทยขึ้นทีวี รีโมท

A modified edition of [VI Translator for Android](https://github.com/vi-translator/vi-translator-android)
by Fouad Almalki, for a Thai community of players. It is not the original app and is not made or
endorsed by its author. Like the original, it is free software under the GNU Affero General Public
License, version 3 (see `LICENSE`); this edition's source is this repository.

It needs the original **VI Translator TV app** on the TV (Android TV / Google TV, LG webOS or Samsung
Tizen), which draws the translations over the game.

## What this edition changes

- **No PSN sign-in inside the app.** The original can show Sony's sign-in page inside the app. That
  screen is removed here: the PSN password is only ever typed in the user's own browser, on Sony's
  page, and the app is given the address the browser ends on (a one-time code), or an account ID
  typed by hand. The app keeps the account ID and the PS5's Remote Play keys, as the original does.
- **PlayStation 5 only.** The Xbox player (which signs in to a Microsoft account inside the app) is
  removed.
- **No self-update.** Releases are built from the `play` flavour, which has no updater and does not
  ask for the permission to install packages.
- **Its own name, icon and application ID** (`app.thaisubtv.remote`), so it is never taken for the
  original and installs beside it. The code keeps the original package names.
- **Thai for the phone's own phrases** (`app/src/main/assets/i18n-phone/th.json`).
- **A swear switch for Thai, as the camera app ซับไทยขึ้นทีวี 1.0.9 has it** (Settings → Translator,
  shown when the language is Thai; on by default). On: English swearing becomes Thai street swearing
  just as strong, with กู/มึง only on a line that swears; a calm line keeps ฉัน and นาย/เธอ. Off:
  nothing crude. Claude and ChatGPT follow it; with it on, a line that swears comes from them rather
  than from a reviewed translation. Every Thai line loses the words that tell the speaker's sex
  (ครับ, ค่ะ, ผม). Switching forgets the lines the phone remembered. This needs a change to the
  shared core (MIT-licensed, from the desktop app's repository), kept here as a patch on the commit
  in `native/core.ref`: `native/core-patches/0001-thai-swear-switch.patch` (a new `core/src/thai.rs`,
  with its tests, and a `swear` setting). CI applies it before building and runs its tests.
- **A sharper, quicker PS5 picture and reading (0.1.2).** The stream is H.265 where the phone has a
  hardware decoder for it at the stream's size, else H.264 (`ps/VideoCodecs.kt`); an H.265 stream
  that shows no picture within 10 seconds is started again in H.264, which that phone keeps. The
  quality is 1080p by default (720p is still in Source). The reading starts at every 120 ms with
  150 ms to settle (the shared core's 250 and 300 are for a PC), moved to once; a timing the player
  chose stays (`core/EditionDefaults.kt`). The timing sliders have Thai labels.
- **The TV page on port 8795 (0.1.2)**, not the desktop's 8791, which the camera app ซับไทยขึ้นทีวี
  holds on many of the same phones. A TV connected to an earlier test build is connected again once.
  Connecting a TV opens the page first if it isn't open, and says so when another app holds the port,
  instead of telling the user the phone isn't on Wi-Fi.

Changed on 21 November 2026, from the original's commit `6cd9e5b`; the original's later fixes up to `95485f4` (the PS5 picture read through the GPU, no more green striped picture) are merged in.

## What it still talks to

- **The PS5 and the TV**, on the home network.
- **Sony**, once, when the browser's one-time code is exchanged for the account ID.
- **vitranslator.app**, for the translation memory (reviewed translations), as the original does.
- **A translation service**, only when the user chooses one and gives their own key.

## Use a second PSN account

PS Remote Play here goes through [chiaki-ng](https://github.com/streetpea/chiaki-ng), which Sony does
not endorse. Use a second PSN account, with its own password and no payment method, never the
account you play with.
