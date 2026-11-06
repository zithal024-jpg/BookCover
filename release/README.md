# BookCover

BookCover is a private, offline e-book reader for Android. On a foldable phone such as the
Samsung Galaxy Z Fold, it can show the cover of the book you are reading on the outside
screen while you read on the inside screen.

**[Download the latest version](../../releases/latest)** (the file ending in `.apk`).

## What it does

- Reads your own **DRM-free EPUB and PDF** files, stored privately inside the app.
- Library with Continue reading, search, sort and collections.
- Comfortable reading: text size, fonts (including OpenDyslexic), margins and spacing,
  Paper / Sepia / Night pages, publisher styles on or off, an optional paper page turn,
  edge taps and volume-key page turns.
- Contents, chapter progress, time left estimates, named bookmarks and in-book search.
- A gentle reading streak (5 minutes a day) and collectable "relics" and cover edges you earn
  by reading, which can decorate the outside cover.
- Backup and restore of positions, bookmarks, collections, reading history and relics.
- **No internet access at all.** See [Privacy](PRIVACY.md).

Two short sample books are included so you can try it straight away.

## Requirements

- Android 11 or newer.
- The outside-cover feature needs a foldable with an outer screen that Android lets apps use
  (it has been tested on a Samsung Galaxy Z Fold). On other phones and tablets BookCover works
  as a normal reader and the cover option simply says the outside screen is not available.

## Install

1. On your phone, open the [latest release](../../releases/latest) and tap the `.apk` file to
   download it.
2. Open the downloaded file (from the browser's downloads or the Files app).
3. If Android asks, allow that app to **install unknown apps**, then go back and tap
   **Install**. This is Android's normal warning for apps that don't come from Play Store.
4. Open **BookCover**. To add books, tap **Import** and choose EPUB or PDF files on your phone.

Optional check that the file is genuine: the release lists the file's SHA-256 fingerprint,
and the app is signed with the certificate whose SHA-256 is
`8c166f2695498803fb23ab5483f2bb622078a8c040f1fbd664965f3ecf1393c6`.

## Updating

Download the newer `.apk` and install it over the existing app; choose **Update**. Your
library, positions and bookmarks are kept.

**Don't uninstall BookCover to update it.** Uninstalling deletes its library. Before any big
change, save a backup: **Library → Settings → Backup → Save backup**. A backup holds your
positions, bookmarks, collections, reading history and relics, but not the book files
themselves, so keep your original EPUB and PDF files too.

## Known limits

- Tested mainly on one Samsung Galaxy Z Fold. Other devices should work but are less tested.
- The outside cover uses an Android feature that phone makers can change; how it looks
  depends on the device.
- DRM-protected books (for example from Kindle or most online shops) can't be opened.
- PDF **Text view** needs Android 15 or newer and PDFs with selectable text.
- No cloud sync; everything stays on the phone.

## Problems and suggestions

Please report them in this page's **Issues** tab. Describe your phone model, Android version
and what happened. Please don't attach your own books or screenshots showing their text.

## Licence

Copyright © 2026 Frank. All rights reserved. BookCover is free to download and use.
Third-party components keep their own licences; they are listed in the app under
**Settings → About → Licences** and in [NOTICE](NOTICE).
