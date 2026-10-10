# FlexUnlock	[中文](https://github.com/AndyNull/flexunlock/blob/main/README.md)/English

FlexUnlock is an LSPosed module for the external display of the Samsung Galaxy Z Flip5 / Flip6.

> Current version: `1.8.22`
>
> Target devices: Samsung Galaxy Z Flip5 / Z Flip6 with One UI 8.5. This round was device-tested on a Z Flip5 running Android 16.

## Features

- Samsung desktop, app drawer, taskbar, and recents on the external display.
- Switch between the original external desktop and full DeX desktop.
- Switch between original QS and full QS layouts.
- App allowlist, search, global scaling, and per-app display profiles.
- External display resolution, DPI, rotation, and layout controls.
- Independent HDMI, DisplayPort, and scrcpy virtual-display targets.
- Independent wired-display resolution and refresh-rate selection from real supported modes.
- scrcpy desktop, taskbar, app menu, recents, wallpaper, and navigation isolation.
- Hide the cover cutout with synchronized display shapes, rounded corners and touch regions. Recent apps, Home and Back each use one third of the cropped display's full bottom edge.
- 0°, 90°, 180°, and 270° rotation synchronized with the system rotation lock.
- External notifications, brightness, media, real flashlight, and lock-screen widgets.
- The notification page shares SystemUI's notification collection. When no native USB options notification is available, a non-dismissible card reflects the real USB state and opens the system USB settings.
- Individual and bulk notification dismissal preserve ongoing and non-clearable notifications.
- Independent lock-screen timeout for the external display; the system timeout is retained after unlock.
- Keep-screen-on handling for video and navigation applications.
- Honeyboard compact keyboard mode with percentage adjustment.
- Samsung system-update block and restore switch.
- Feedback and diagnostic logs with compact/full capture, size limits, sharing, and deletion.
- Light, dark, and system themes with in-app update checks.
- Simplified Chinese by default, with an English switch on Home and a saved language preference across app restarts.

## Changelog
[View changes](CHANGELOG-en.md)

### 1.8.22 Verification

- 255 unit tests passed. Release APK build and signature verification passed.
- Cold-booted Z Flip5 verification covered cropped display shapes, rounded corners and gesture regions at 0 / 90 / 180 / 270 degrees. Back and Home gestures worked on the new bottom edge.
- The USB card opened the ordinary USB options page. Clear all preserved USB and protected system cards without changing USB functions.
- Real incoming QQ messages, cable disconnects and lockscreen authentication challenges were not retested in this round. Flip6 was not device-tested in this round.

## Requirements

1. Samsung Galaxy Z Flip5 or Z Flip6.
2. One UI 8.5 firmware.
3. Magisk, Zygisk, and LSPosed installed and enabled.
4. FlexUnlock enabled in LSPosed with the required scope.

## Installation

1. Download the signed APK from GitHub Releases.
2. Install it; upgrades can be installed over the existing APK.
3. Enable FlexUnlock and its scope in LSPosed.
4. Reboot the phone fully so `system_server`, SystemUI, and Launcher hooks reload.

## Usage

- **Apps**: choose applications allowed to launch on the external display and set scaling.
- **Tiles**: choose original or full QS layout.
- **Controls**: configure desktop, DeX, resolution, refresh rate, rotation, cutout hiding, and timeout.
- **External Display**: select HDMI, DisplayPort, or scrcpy and its display mode.
- **Theme**: select the module UI theme.
- **About / Feedback & Diagnostics**: capture and share compact or full logs.

## Build

Requirements: JDK 17, Android SDK 34, and Gradle 8.x. The repository does not commit a Gradle Wrapper; use a local Gradle distribution:

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" `
  :cover-shell:testReleaseUnitTest `
  :cover-shell:lintVitalRelease `
  :cover-shell:assembleRelease `
  --no-daemon
```

APK output: `cover-shell/build/outputs/apk/release/cover-shell-release.apk`

## Troubleshooting

### The module has no effect

- Verify that FlexUnlock and the required scope are enabled in LSPosed.
- Reboot fully after installation.
- Check for `FlexUnlock-SystemBridge` and `FlexUnlock-CoverShell` in logs.

### External timeout does not behave as expected

- Re-select the timeout in the module Controls page.
- While locked, the configured FlexUnlock timeout is used; after unlock, Android `SCREEN_OFF_TIMEOUT` is used.
- `scrcpy --stay-awake`, `svc power stayon true`, and other keep-screen-on applications affect testing.

### scrcpy cannot display or interact

- Create a virtual display with `scrcpy --new-display=1920x1080`.
- Re-select the target display after connecting scrcpy.
- Keep one scrcpy instance while testing; multiple instances can create duplicate display tasks.

## Limitations

- The module depends on Samsung system components and may require updates after firmware changes.
- Other devices and firmware versions are not fully validated.
- Third-party IME sizing depends on the IME implementation; the system keyboard has the most complete support.
- Reboot after changing full DeX, display targets, or LSPosed scope.

## Release Artifacts

- `FlexUnlock-1.8.18-release.apk`: signed APK.
- `FlexUnlock-1.8.18-source.zip`: cleaned source archive.

[GitHub Releases](https://github.com/AndyNull/flexunlock/releases)
