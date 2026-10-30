# AdExit AI — Android project

This is a native Android Accessibility Service app intended for Android 15 and the Motorola Moto G Power 5G (2024).

## What it does
- Watches accessibility window/content events.
- Looks for accessible controls explicitly labelled Close, Skip, Dismiss, X, ×, or similar.
- Re-scans for up to 60 seconds after a screen change, so delayed close buttons can become available after an ad countdown.
- If Google Play (`com.android.vending`) opens, tries Android Back first. If that does not return to the prior app, it uses Home and relaunches the last foreground app it observed.

## Important limitations
- This is rule-based automation, not a trained visual AI.
- It can only reliably find controls exposed through Android accessibility. Some ad SDKs draw X buttons into a canvas/video or use unlabeled icons; those cannot be reliably identified by this implementation.
- It deliberately does not tap arbitrary screen coordinates or buttons labelled Install/Download/Open/Buy.
- Android may restrict background service behavior, and some apps may block accessibility interaction.
- The Google Play fallback may relaunch an app rather than restore its exact previous screen.
- It cannot guarantee closing every advertisement.

## Build an APK
1. Install Android Studio with Android SDK Platform 35 and a Java 17 JDK.
2. Open this folder in Android Studio.
3. Allow Gradle sync to finish.
4. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

For a release APK, create a signing key in Android Studio and use **Build > Generate Signed Bundle / APK**.

## Install and enable
1. Install the APK on the phone.
2. Open AdExit AI.
3. Tap **Enable Accessibility Service**.
4. In Android Accessibility settings, open AdExit AI and enable the service.
5. Read and accept Android's accessibility permission warning.
6. Return to AdExit AI. The service runs while enabled; open the app again to disable it through Android Accessibility settings.

Accessibility access is powerful: the service can inspect on-screen text and interact with controls. Only install builds you trust.
