# Changelog

All notable changes to HomeCast are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- Sign-in prompt for pages behind HTTP authentication (e.g. Basic auth): a
  username/password dialog, with a show/hide password toggle.

## [0.1.0] - 2026-10-17

First public release.

### Added

- In-app browser that finds the video on a web page, including players inside
  iframes: HLS (`.m3u8`) streams and direct video files (`.mp4`, `.webm`, `.mkv`).
- Ad and popup blocking: a bundled host blocklist, popups and script-opened
  windows are dropped, and redirects to another site without a tap are
  blocked; a tapped link that would open a new tab opens in the same page.
- "Play on TV" to Chromecast and Google TV devices through the Default Media
  Receiver, using HomeCast's own Cast implementation (no Google Play Services).
- Automatic TV discovery on the Wi-Fi network, connect by IP address, and saved
  TVs that are remembered; a discovered TV that moved to a new IP address is
  found again and its new address saved.
- Local proxy on the phone that adds the headers the video server expects
  (Referer, Origin, User-Agent, cookies) so the TV is not turned away.
- Remote control: play/pause, seek bar, back/forward 10 seconds, volume and mute,
  stop, and the phone's volume keys control the TV volume.
- "Play again on TV" when the TV dropped the video, from where it was.
- The page's own video is paused on the phone when it plays on the TV.
- Notification and lock-screen controls, with playback kept alive while the
  screen is off (and the phone allowed to sleep again once the TV stops); a
  prompt to exempt HomeCast from battery optimisation.
- Subtitles captured from the page's player, plus your own `.srt` files
  (Windows-1254 / ISO-8859-9 Turkish files are converted automatically).
- Live HLS streams, shown with a LIVE label instead of a seek bar.
- Series flow: go back to the browser, open the next episode, and one tap
  replaces the video on the TV.

[Unreleased]: https://github.com/YunusErdemSihhatli/homecast/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/YunusErdemSihhatli/homecast/releases/tag/v0.1.0
