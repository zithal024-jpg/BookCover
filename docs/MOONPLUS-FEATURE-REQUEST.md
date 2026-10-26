# Moon+ Reader inquiry draft

**Draft only. No message has been sent.**

Subject: Native dual-screen book-cover support on Samsung foldables

Hello Moon+ Reader team,

I would like Moon+ to show the current book's cover on the physical exterior screen of an unfolded Samsung foldable while I read normally across the full interior screen.

We built a small Kotlin diagnostic using public AndroidX WindowArea dual-screen presentation APIs. On my Samsung SM-F976U running Android 17/API 37, I observed the selected cover on the physical outside screen while the diagnostic controls remained functional on the inside. Switching to Kindle ended the exterior-cover behavior. Android documents that leaving the presentation host's primary activity can cause the session to end.

Would you consider a native Moon+ option using `OPERATION_PRESENT_ON_AREA` / `WindowAreaController.presentContentOnWindowArea`, with the reading activity itself owning the exterior-cover session?

Alternatively, is there an officially supported SDK, plugin, or other integration mechanism for adding this feature? I found file-opening and library/sync integrations in your documentation, but could not find an interface for this use case.

The desired feature would preserve the cover's aspect ratio, expose a simple enable/stop control, and handle unsupported modes, locking, folding, power saver, and system termination gracefully. I am not asking to split the unfolded interior display or extract protected Kindle content.

I can provide the diagnostic APK and device logs if helpful. The same-activity display behavior is user-observed; sustained reading inside Moon+ has not been tested.

Thank you.

---

Developer reference: https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes

Contact details should be obtained from the current [official publisher listing](https://play.google.com/store/apps/details?id=com.flyersoft.moonreaderp) at the time of sending. Do not infer an address or send this draft automatically.
