# Exterior cover, Always On Display, and power

**Status after phone testing:** the user reported that the exterior brightness slider had no effect, with all other functions behaving as expected. At the user's request, dimming and AOD are shelved for the EPUB prototype (0.3.0). The new reader has no brightness control or override. The research below is retained as background.

Research date: October 26, 2026. AOD operation has not been tested on the physical phone by this project.

## Supported conclusion

**No supported public integration was found that lets BookCover place arbitrary artwork into true Samsung AOD on only the exterior panel while the interior remains an interactive reader.** This is a bounded finding from the reviewed Android/Samsung documentation, not a claim that Samsung could never implement it.

Samsung describes AOD as its screen-off/lock-screen feature. On supported models it can show lock-screen wallpaper, music information and widgets. The settings, content and availability vary by model/software. A user's ability to display wallpaper through Samsung's settings does not establish a third-party API for concurrent independent AOD on the outer screen during inside reading. [Samsung AOD/widget guidance](https://www.samsung.com/ca/support/mobile-devices/use-widgets-on-your-galaxy-phone/).

Android defines display DOZE/DOZE_SUSPEND states as low-power states for system-provided content while the device is non-interactive. `Display.getState()` can report them; this is not a public command for an ordinary app to put a physical panel into that state. This is also distinct from background CPU/network restrictions commonly called Android Doze. [Android Display reference](https://developer.android.com/reference/android/view/Display).

The Android platform's `DreamService.startDozing()` and doze-display-state/brightness controls are explicitly hidden for System UI use. They are not ordinary SDK methods BookCover can use. Creating a normal screensaver or keeping a window awake does not grant access to those AOD controls. [AOSP DreamService source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/service/dreams/DreamService.java).

BookCover currently owns a normal public WindowArea presentation while its foreground activity remains visible. That route has worked on the user's phone. Locking/screen-off deliberately stops our experiment, and the system may also terminate it as eligibility changes. Replacing that session with an AOD wallpaper would be a separate system/vendor feature, not a rendering switch. [Android foldable display modes](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes).

## What version 0.2.2 implements

- **Tall-screen matching background:** sample the decoded image's edges, pick a dominant color family, and fill the presentation view with that color. Keep the whole cover centered with aspect ratio preserved. The artwork is neither stretched nor cropped. Transparent-only edge samples fall back to a dark neutral background. Root image bounds use match-parent and are logged when laid out so we can verify actual available window geometry.
- **Optional outer-window brightness request:** default to the phone's existing brightness policy; provide a 10–100% request slider and Use phone setting action in setup and the revealed reader controls. Only the presentation window receives this request. No global brightness setting, extra permission, wallpaper, or AOD setting is changed.
- **Static content:** there is no cover animation or per-frame bitmap update loop. The OEM/system still controls physical panel refresh and power behavior.

Android exposes a per-window `screenBrightness` override; values below zero restore normal policy and 0–1 request a brightness level. This is normal display operation, not AOD. Whether Samsung honors an independent request on the exterior presentation must be checked on the phone; the UI labels it a request. [Window brightness reference](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness).

No battery percentage saving is claimed. A matching colored background lights more area than black margins can, so it should not itself be treated as a power-saving feature. Brightness, artwork, both active panels and the OEM's refresh policy all affect results. Measure realistic reading sessions before making an energy claim.

## Next phone check

1. Start an exterior-cover session while fully unfolded. Confirm the entire artwork is present and matching color fills the top/bottom area of the physical outside display.
2. Reveal reader controls and lower Outer cover brightness. Check that the exterior visibly changes and that the inside remains comfortable/unchanged.
3. Use phone setting to restore the normal policy. If no independent change occurs, record that the request was ignored or shared by this firmware; do not call it working from an API assignment alone.
4. Confirm Stop still removes the exterior cover. The existing three-minute diagnostic timeout remains in place.

Further options include a darker matching-background choice or a verified lower-refresh request. Those remain future experiments, and none should be described as true AOD. Samsung-native integration or a documented vendor extension would be needed to revisit the true AOD route.
