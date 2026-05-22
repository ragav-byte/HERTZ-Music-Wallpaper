# HERTZ: Music Wallpaper

HERTZ is a music-inspired live wallpaper for Android. It turns the current song artwork into a fluid color gradient and can show optional music cards on the lock screen, home screen, or both.

## What it does

- Reads the current playing song title, artist, timeline, and artwork from the active media session on your phone
- Builds a fluid moving gradient from the dominant colors in the cover art instead of showing the cover art as the wallpaper background
- Freezes the fluid motion when playback is paused and resumes when music starts again
- Lets you show the card on the lock screen only, home screen only, both, or neither
- Hides the cards instantly when playback stops or the source player closes, while keeping the gradient wallpaper active
- Lets you tune the artwork card size, text card width, card radius, frost amount, blur, text size, and vertical placement
- Uses marquee text for long song names or artist names inside the wallpaper card

## Artwork cache

HERTZ keeps a small local artwork cache so song changes can feel faster.

- Recent artwork is cached in memory for quick reuse
- Recent artwork is also cached on disk so reopening the app or wallpaper can feel faster
- Cached items that are not used for 24 hours are deleted automatically
- The cache is capped to stay lightweight:
  - around 40 MB in memory
  - up to 200 files on disk
  - about 80 MB max disk usage

The cache is local-only and is used only to improve artwork loading speed.

## Why it gets smoother as you use it

The first time a brand-new song plays, HERTZ must wait for Android or the music app to publish the artwork and timeline through the media session or notification. That can create a short delay, especially with high-resolution album art.

After a song has appeared once, HERTZ can reuse its cached artwork and remembered duration by matching the title and artist. This means repeated songs should load much faster, often close to instantly. Text, artist, and timeline updates are designed to appear first, so the wallpaper does not feel stuck while artwork is still arriving.

If a new song starts but the cover art is not ready yet, HERTZ avoids showing the previous song artwork as if it were current. The card updates with the new text first, then fills in the artwork as soon as Android provides it.

## Privacy

HERTZ does not collect, upload, or sell sensitive personal data.

The app works locally on your device and only uses:

- notification access to read active media metadata from your current player
- wallpaper access to render the live wallpaper

No account login, remote analytics, or cloud sync is required for the core wallpaper experience.

## Install the APK

1. Copy the generated APK file to your mobile.
2. Install it using APKMirror Installer.
3. Open HERTZ.
4. Enable media access if Android asks for it.
5. Tap `Set wallpaper` and choose `HERTZ Wallpaper`.

APK path after build:

`app/build/outputs/apk/debug/app-debug.apk`

## Notes

- HERTZ is currently being tested before a broader release.
- It will be released to the app store shortly after final testing is complete.
- Some Android phones allow lock-screen-only live wallpapers, while others apply the wallpaper to both home and lock screen.
