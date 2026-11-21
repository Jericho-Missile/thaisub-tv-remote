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

Changed on 21 November 2026, from the original's commit `6cd9e5b`.

## What it still talks to

- **The PS5 and the TV**, on the home network.
- **Sony**, once, when the browser's one-time code is exchanged for the account ID.
- **vitranslator.app**, for the translation memory (reviewed translations), as the original does.
- **A translation service**, only when the user chooses one and gives their own key.

## Use a second PSN account

PS Remote Play here goes through [chiaki-ng](https://github.com/streetpea/chiaki-ng), which Sony does
not endorse. Use a second PSN account, with its own password and no payment method, never the
account you play with.
