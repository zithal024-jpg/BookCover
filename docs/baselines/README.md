# Original display diagnostic

`MainActivity-0.1.kt` preserves the original source before the same-activity sample-reader integration. It is reference material outside the application's source directory and is not compiled into the app.

The original installable APK remains locally at `output/BookCover-diagnostic.apk`, version 0.1-diagnostic, application ID `com.bookcover.diagnostic`. Its SHA-256 is recorded in docs/VALIDATION.md. Version 0.2 uses the same package and debug signing key to update in place; its artifact is `output/BookCover-reader-test.apk`. The new build script does not overwrite the original APK.
