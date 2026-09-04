<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ktv_logo.png" width="140" alt="Maidong KTV Logo">
</p>

<h1 align="center">Maidong KTV</h1>

<p align="center">A local karaoke application for Android TV and landscape song-selection devices</p>

<p align="center">Remote Control · Song Discovery · Request Queue · Download Management · Stable Playback</p>

## Core Features

- Full TV remote navigation with directional keys, confirmation, back, and long-press actions.
- Consistent focus feedback for buttons, lists, cards, switches, and dialogs.
- Home entries for charts, song titles, singers, frequently played songs, favorites, categories, and local songs.
- Online and local search with language filters, paging, and initial-letter keyboard input.
- Singer browsing with portrait tags and singer-specific song lists.
- Song ordering, batch ordering, queue prioritization, favorites, and file management.
- Real-time synchronization across queued, played, and downloaded song lists.
- New requests join the end of the queue without interrupting the current requested song.
- Automatic playback when requested songs are available, including smooth transitions from public playback.

## Playback

- IJK-based playback for local and downloaded songs.
- Continuous playback while navigating between pages or switching between windowed and full-screen modes.
- Play, pause, skip, replay, progress display, and seeking controls.
- Original-vocal and accompaniment switching without changing the current play or pause state.
- Synchronized playback state across windowed controls, full-screen controls, and status overlays.
- Song title overlays, temporary mode prompts, and persistent pause indicators.
- Video ratio, automatic full screen, audio-video synchronization, and public playback settings.
- Maximum song volume and configurable original-vocal and accompaniment behavior.

## Downloads And Data

- Background downloads with progress, resume support, and automatic retry.
- Automatic cleanup and retry after download or decryption failures.
- Recognition, counting, and playback of downloaded local songs.
- Segmented database download, verification, merge, and atomic update.
- Directory creation, database scanning, and download startup only after storage permission is granted.
- Local disk and USB song scanning, reserved storage settings, and automatic cleanup.
- Unified Maidong KTV data storage to avoid conflicts with other applications.

## Settings

- Data settings for library updates, local scanning, reserved space, automatic cleanup, and database maintenance.
- Playback settings for public playback, automatic full screen, video ratio, synchronization, and title overlays.
- Sound settings for volume behavior, maximum song volume, original vocals, and accompaniment.
- Interface settings for language, floating controls, carousel content, and song title overlays.
- Dialog selections remain temporary until confirmed and provide clear remote-control focus feedback.

## Interface And Interaction

- Landscape TV layout optimized for long-distance viewing.
- Consistent state and meaning across top, windowed, and full-screen playback controls.
- Stable focus during list refreshes, paging, tab changes, and song ordering.
- Focus changes color only and preserves each control's original dimensions, corners, and layout.
- A consistent circular Maidong logo across the home screen, player, and application information.
