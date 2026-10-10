# Changelog

FlexUnlock follows semantic versioning. Build versions are sourced from the root `gradle.properties`.

## 1.8.22 - 2026-10-10

### Fixed

- Removed stale cutout fallback after cropping. Recent apps, Home and Back gesture regions follow the cropped logical dimensions across the full bottom edge.
- Fixed side gaps and incorrect corners at 90 / 270 degrees. All four rotations use cropped rectangular display shapes and matching rounded corners.
- Adapted to Samsung's inlined shape calculation through the target display's rotation cache without modifying the original physical display shape.
- Added a non-dismissible USB card based on real USB_STATE when no native USB options notification is active. Native options notifications are deduplicated; ADB debugging notifications do not hide the card.
- USB card clicks use SystemUI's keyguard-aware launch flow to open the ordinary USB options page and collapse the cover panel, replacing the unsupported generic action and engineering menu entry.
- Clear all checks ongoing and non-clearable flags for each notification and preserves protected entries.

### Verified

- 255 unit tests passed. Release build and signature verification passed.
- Cold-booted Z Flip5 / Android 16 / One UI 8.5 validation covered all four rotations, display cutouts, shapes, rounded corners and Back / Home gestures on the cropped bottom edge.
- Device testing confirmed USB options launch, temporary notification dismissal and preservation of USB and protected system cards. USB configuration was not changed.
- Real QQ messages, cable disconnects and lockscreen authentication challenges were not retested in this round. Flip6 was not device-tested.

## 1.8.21 - 2026-10-10

- Fixed intermittent missing notifications in the original QS notification page by registering the SystemUI listener on the main thread.
- Kept the notification page on the system `NotifPipeline`, including synchronized additions and dismissals.
- Fixed the brightness slider flashing in the last frame while dismissing the notification page.
- Coalesced QS media/brightness layout work to reduce repeated layout cost during unlock and swipe-up transitions.

## 1.8.20 - 2026-10-07

- Added a Simplified Chinese / English language card on Home. Simplified Chinese is the default.
- Translated all module screens, app settings dialogs, status messages, accessibility labels and log notifications.
- Persisted the language selection across app exits, process restarts and upgrades.
- Language selection affects only the module app, not the system or third-party apps.

## 1.8.18 - 2026-09-23

### Fixed

- External lock-screen timeout now ignores only FlexUnlock's own external-display wake lock, so the configured timeout can actually turn the cover display off.
- Scrcpy app-close animation restored to 240 ms based on the stable transition path.
- Removed the experimental wallpaper-target clearing hook that could leave a black frame while returning from an app.
- Avoided repeated native display-size/DPI reapplication when current base metrics already match the target, reducing Launcher and wallpaper recreation during scrcpy reconnects.

### Verified

- `:cover-shell:testReleaseUnitTest` passed.
- Release APK build, signed installation, and device reboot completed.
- Current lock-screen timeout is read from `flexunlock_cover_lockscreen_timeout_ms`; `30,000 ms` is only the configured current value/default fallback.

## Earlier releases

See the Chinese history in [CHANGELOG.md](CHANGELOG.md). The 1.7.x line introduced the current external-display, full DeX, display-mode, cutout-hiding, diagnostics, and scrcpy support.
