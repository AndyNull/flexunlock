# Changelog

FlexUnlock follows semantic versioning. Build versions are sourced from the root `gradle.properties`.

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
