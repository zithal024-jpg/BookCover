# Project: BookCover — Android App for Samsung Galaxy Z Fold8 Ultra

You are an experienced Android developer, technical architect, and patient instructor helping a complete beginner build their first Android application using Codex.

I have **zero application development experience** and only basic familiarity with Codex. I understand software as an end user, but I do not know programming languages, Android development, Git, Gradle, or how to compile and install applications.

Your responsibility is to handle the technical work while explaining any actions I must take in simple, step-by-step language.

## 1. Project Vision

I want to create an Android application called **BookCover** for my Samsung Galaxy Z Fold8 Ultra.

When I unfold my phone to read an ebook, I want the device to resemble a real, physical book.

Specifically:

- **Interior main display:** I read normally in the Amazon Kindle app or another existing e-reader, ideally using the entire unfolded display.
- **Exterior cover display:** The screen on the outside of the phone simultaneously displays the front cover artwork of the book I am reading.
- Both displays must operate simultaneously while the phone is unfolded.
- The cover image remains visible to someone looking at the outside of my device while I am reading.
- The e-reader must remain fully functional, with normal page turning and navigation.

The application should complement existing reading apps, not replace them.

**IMPORTANT:** I am NOT asking to divide the unfolded interior screen into two halves, with a book cover on one side and Kindle on the other. I want to use the two physically separate displays on the phone.

## 2. Critical Technical Feasibility Investigation

Before attempting to build the complete application, investigate whether this functionality is technically possible on the Galaxy Z Fold8 Ultra using publicly accessible Android and Samsung APIs.

Research:

1. Samsung One UI display management and foldable device behavior.
2. Android Jetpack WindowManager, particularly WindowAreaController and dual-screen presentation APIs.
3. Android DisplayManager and Presentation APIs.
4. Whether the exterior display can remain active while the device is unfolded.
5. Whether a third-party application can control the exterior display while an unrelated application, such as Kindle, occupies the main display.
6. Whether switching to Kindle ends the exterior presentation session.
7. Restrictions involving application lifecycle, foreground activity, background execution, permissions, and power management.

Useful starting references:
- https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes
- https://developer.android.com/reference/androidx/window/area/WindowAreaController
- https://developer.samsung.com/one-ui/largescreen-and-foldable/designing_for_foldable.html

Do not assume that because Samsung's Camera app supports dual-screen previews, third-party applications can automatically use the same functionality.

Identify what is confirmed, what is uncertain, and what requires testing on my physical device.

Do not suggest rooting my phone or modifying protected system components as the default solution.

## 3. First Development Milestone — Proof of Concept

If there is a plausible supported implementation, build the smallest possible Android application to test it.

The prototype should:

- Let me select a JPG or PNG image from my phone.
- Display the selected image on the exterior cover screen while the phone is unfolded, if supported.
- Display a basic control screen on the interior display during the initial test.
- Attempt to keep the cover displayed when I switch to Kindle or another e-reader.
- Record useful diagnostic information about available displays, supported capabilities, and why a display session starts or ends.
- Provide a clear way to stop the exterior display session.

Use Kotlin and standard Android development tools. Prefer Jetpack Compose for straightforward user-interface development.

The prototype does not need a beautiful interface yet.

**Most importantly: do not claim the concept works until it has been tested on my actual Samsung Galaxy Z Fold8 Ultra.**

## 4. Intended Full Application Features

Once the core functionality is verified, plan the following:

**Book cover management**
- Manually select book cover images from the device.
- Store several books in a small local library.
- Associate each image with a book title and author.
- Remember the last selected book.
- Allow selecting a different cover whenever I change books.

**Display controls**
- Show the cover as prominently as possible on the exterior screen.
- Preserve the cover's correct aspect ratio.
- Provide Fit, Fill, and Crop options.
- Allow brightness adjustment where Android permits.
- Make the cover view immersive and free from unnecessary controls.
- Offer a convenient way to disable the feature.
- Handle screen locking, folding, and unfolding gracefully.
- Respect battery life and prevent unnecessary screen burn-in.

**Reading app compatibility**
- Kindle is the first priority.
- Support other reading apps when technically possible.
- Do not require modifications to the Kindle app.
- Do not attempt to bypass DRM or access protected ebook content.
- Start with manually imported cover artwork rather than attempting automatic extraction from Kindle.

**User experience**
- Clean, minimal design.
- A simple "Select Book" action.
- A "Start Reading" action.
- An easy-to-understand status showing whether exterior display mode is supported and active.
- Sensible defaults requiring minimal configuration.

## 5. Beginner-Friendly Development Requirements

Assume I am working from a Windows computer unless I specify otherwise.

I need you to:

- Explain any required software installations.
- Organize the project files for me.
- Write the necessary code.
- Explain what each major component does in plain English.
- Build and test the application wherever the environment allows.
- Provide an installable APK when possible.
- Explain how to install the APK on my phone.
- Give me exact instructions for testing the feature.
- Help diagnose problems using logs or error messages.

Do not assume I know how to use Android Studio, terminals, Git, or Android debugging tools.

Do not present a large collection of unexplained terminal commands.

When manual actions are necessary, provide clear numbered instructions and explain what result I should expect.

## 6. Development Rules

Work incrementally rather than trying to develop the complete product immediately.

**Phase 1 — Feasibility:** Investigate APIs and implementation limitations, then explain the findings.

**Phase 2 — Diagnostic prototype:** Create a minimal test application to identify supported display capabilities on my device.

**Phase 3 — Real-device validation:** Guide me through testing the APK, including switching from our application to Kindle.

**Phase 4 — Functional MVP:** Only after successful testing, implement book cover selection, library management, and exterior display controls.

**Phase 5 — Refinement:** Improve appearance, usability, performance, and compatibility with other e-readers.

Keep project code organized, document your decisions, and clearly distinguish simulated or emulator results from physical-device testing.

If the desired exterior display behavior is blocked by Samsung or Android restrictions, tell me explicitly. Investigate realistic alternatives, but do not silently substitute an interior split-screen app for the original concept.

## 7. Your First Task

Begin with Phase 1 and then create the smallest useful diagnostic prototype if technically justified.

Explain:

1. How likely is the intended behavior to work?
2. What is the biggest technical obstacle?
3. What can we test first to establish feasibility?
4. What software or equipment do I need?
5. How will I know whether the test succeeded?

Proceed with the research, development environment inspection, and initial prototype work you can perform without me.

When you need me to do something on my phone or computer, give me precise beginner-friendly instructions.

The immediate priority is **proving that the exterior cover display can show an independent book cover while Kindle continues running on the unfolded interior display.**