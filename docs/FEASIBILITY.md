# BookCover: feasibility investigation

Research date: October 26, 2026. Initial assessment is retained below. **Subsequent physical test:** the user confirmed concurrent exterior cover + interior BookCover controls, but the Kindle handoff failed. See `docs/VALIDATION.md` for evidence and the revised same-activity reader direction in `docs/READER-MISSION.md`.

## Assessment

The requested combination—an independent cover image outside, with Kindle filling the unfolded inside—is **unlikely to be supported reliably through public APIs**. This is an engineering assessment, not a measured probability or a finding about this particular phone. There are two separate gates: Samsung must expose concurrent inner/outer presentation to ordinary apps, and that presentation must survive BookCover losing the foreground to Kindle. A small capability-and-lifecycle diagnostic is justified; a complete library app is premature.

## What is confirmed

**Samsung's documented rear-display feature does not meet this requirement.** Samsung describes public Jetpack rear-display support starting with One UI 6.0, but explicitly says the main screen remains off. Its example calls the activity-transfer operation. That is different from concurrent presentation. [Samsung rear-display implementation](https://developer.samsung.com/sdp/blog/en/2024/09/26/unfold-the-potential-of-galaxy-fold-devices-with-windowmanagers-rear-display-mode).

**Public Android APIs distinguish two modes.** Rear-display mode transfers the activity outward and turns the inner screen off. Dual-screen mode shows different content on both screens through `OPERATION_PRESENT_ON_AREA`. Google's guide names Pixel Fold with Android 14 or later as a supported example; this does not certify a Samsung model. It also warns that a presentation can disappear when the user leaves the primary app. [Android foldable display modes](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes).

**The manufacturer controls whether concurrent mode is exposed.** Android's implementation guidance requires OEM configuration for concurrent internal displays, the rear display, and an app-requestable device state. Installing an app cannot supply missing system configuration. Samsung Camera working on both screens establishes a hardware/system capability, not access for an ordinary app. [AOSP WindowManager Extensions](https://source.android.com/docs/core/display/windowmanager-extensions).

**Ordinary foldable continuity is a different behavior.** Samsung's One UI guidelines describe the app moving from the cover screen to the main screen when unfolded. Their layout and Flex mode guidance concerns adapting app content to the available screen. It is not a promise of two independent physical displays being available simultaneously. [Samsung foldable design guidance](https://developer.samsung.com/one-ui/largescreen-and-foldable/designing_for_foldable.html).

## What DisplayManager and Presentation can establish

`DisplayManager` can enumerate logical displays and presentation candidates. A visible display entry does not establish that it is the outer panel, awake, accessible, or usable concurrently. Display IDs must not be hard-coded. The newer built-in-display category can even return inactive or disabled displays. [DisplayManager reference](https://developer.android.com/reference/android/hardware/display/DisplayManager).

`Presentation` is a second public route worth probing only for eligible secondary displays. Current documentation requires `FLAG_PRESENTATION`, restricts presentations on internal displays, and permits automatic dismissal when eligibility changes. A presentation cannot cover the application's own top visible task. `show()` can throw `InvalidDisplayException`; removal of the display or associated task also cancels it. Therefore finding an ID, creating a virtual display, or avoiding an app's `onStop()` cleanup does not establish outer-panel control. [Presentation reference](https://developer.android.com/reference/android/app/Presentation).

## Lifecycle, permissions, and power

The WindowArea API requires the requesting app to be top visible. Its documented end conditions include losing the foreground, a device-state change, critical heat, and power saver. Switching to Kindle is consequently a central failure risk even if both screens initially light up. The prototype should retain the session when paused/stopped for this experiment, while logging framework callbacks; it must not interpret a successful start callback as proof that Kindle coexistence works. [WindowAreaController reference](https://developer.android.com/reference/androidx/window/area/WindowAreaController).

A foreground service performs user-noticeable background work and shows a notification. It is not a visible activity. **Engineering inference:** adding one cannot override the presentation API's top-visible/activity eligibility rules or grant an unavailable display mode. Android also restricts starting such services from the background. [Foreground services](https://developer.android.com/develop/background-work/services/fgs), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Keeping a screen on consumes battery; Android allows a background app's screen-on flag to stop preventing timeout. A diagnostic should use short sessions, preserve normal lock behavior, and provide Stop. Battery-optimization exemptions, overlays, and wake locks should not be presented as established fixes for OEM display access. [Android screen-on guidance](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).

## What remains uncertain and requires this phone

The exact phone model, region, Android version, One UI version, and installed firmware remain unverified. A marketing name or another region's update does not prove this device's capability. The diagnostic records `Build.MANUFACTURER`, model, Android release, build fingerprint, display flags/state, WindowManager extension level, and reported operation statuses. If needed, confirm the One UI version separately in Settings → About phone → Software information.

Test fully unfolded: select an image, query capabilities, attempt an available concurrent route, visually identify the actual outer panel, switch to full-screen Kindle, and turn pages for at least 60 seconds within the three-minute test session. Then return and export logs. Repeat with lock/unlock, fold/unfold, and power saver as separate tests. An external monitor, virtual display, emulator, or rear-display transfer is not success for the original goal.

## Decision gates and alternatives

Proceed to the functional MVP only after the cover remains outside while Kindle works normally across the full inside display. If concurrent presentation is unsupported, record that the installed firmware exposes no usable public route. If it starts but ends when Kindle takes focus, record that separate obstacle; extra library features will not solve it.

Realistic alternatives are a printed/changeable physical cover, a separate display accessory after a separate design investigation, or a reading app controlled by BookCover that keeps its own activity visible. The latter changes the product and cannot supply Kindle's protected content. A Samsung feature request or partnership could address missing OEM support. None is silently substituted for the requested app; rooting and protected-system changes are outside this prototype.
