# QuickShare

QuickShare is an Android app for transferring files between nearby Android devices over the same local Wi-Fi network, without uploading files to a cloud service.

## Initial MVP
- Choose a file with Android's system file picker.
- Start a receiving server on port 8988.
- Send a selected file to the receiver's local IP address.
- Save received files under **Downloads/QuickShare**.
- Transfer work runs off the main UI thread with basic status/progress feedback.

## Build
Open this project in Android Studio, or run `gradle assembleDebug` with Gradle 8.9 and JDK 17. GitHub Actions builds a debug APK and uploads it as a workflow artifact.

## How to use
1. Connect both phones to the same Wi-Fi network or hotspot.
2. On the receiving phone, tap **Start receiving**.
3. Find the receiving phone's local IP address in Android Wi-Fi settings.
4. On the sending phone, enter that IP, choose a file, and tap **Send file**.

Both devices must be on a network that allows local device-to-device traffic. This first MVP does not yet include QR pairing, Wi-Fi Direct setup, resumable transfers, app migration, or end-to-end encryption.

## Platform
- Native Android / Kotlin
- Minimum Android version: 10 (API 29)
