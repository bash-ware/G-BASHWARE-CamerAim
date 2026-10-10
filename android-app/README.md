# G-BASHWARE CamerAim Android prototype

This is the phase-2 dashboard and background-monitoring prototype. It is a
read-only CNC monitor: it has no machine movement, spindle, or job-start
commands.

## Toolchain

- Android Studio Quail 4 (2026.1.4)
- Android SDK 37 / Build Tools 36.0.0
- Android Gradle Plugin 9.4.0
- Gradle wrapper 9.6.0
- Kotlin and Compose compiler 2.3.21
- Compose BOM 2026.08.00
- Java 17 bytecode target; Android Studio's bundled JDK may run Gradle

Build and test from the repository root:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\android-app\gradlew.bat -p android-app testDebugUnitTest lintDebug assembleDebug
~~~

The debug APK is written to
android-app/app/build/outputs/apk/debug/app-debug.apk.

On a cloud-synchronized drive, a failed Gradle process can leave transient
Kotlin/test-output locks. Stop Gradle and remove only the generated
android-app/app/build directory before a clean retry.

## Connect to the development server

1. Start the server with the Lan environment only on a trusted private LAN.
2. Read server/data/credentials.json locally and enter only ReadToken in the
   app. Never send this file or add it to Git.
3. Enter the server computer's LAN address, for example
   http://192.168.1.20:5187, and the configured machine ID.
4. Tap **Osveži**, then **Pokreni praćenje** and allow notifications.

For an emulator, either use http://10.0.2.2:5187 with the LAN-bound server or
run adb reverse tcp:5187 tcp:5187 and use http://127.0.0.1:5187.

HTTP cleartext is enabled only for this prototype and is called out by Android
lint. Bearer tokens can be observed on an untrusted network, so do not use the
prototype over public Wi-Fi or the internet. Release deployment needs HTTPS or
a documented VPN.

## Background behavior under test

The service is user-started and declared as connectedDevice, matching a
continuous connection to another local network device. It does not start after
boot and uses START_NOT_STICKY. A durable event cursor is saved before an event
notification is shown; reconnect sends afterSequence so missed events are
recovered without repeating notifications.

The service writes a five-second local heartbeat. When the app is next opened,
a heartbeat older than 15 seconds is shown as interrupted monitoring rather
than as a live service.

The connectedDevice type was selected from Android's current foreground
service guidance:

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
- https://developer.android.com/develop/background-work/services/fgs/timeout

This design avoids falsely classifying the connection as dataSync; it is not a
guarantee that a vendor's Android build will keep the process alive.

## Required physical-device test

Before deciding that direct background monitoring is sustainable, run these on
the intended phone:

- unlocked app, then locked screen;
- background for 15 minutes and for several hours;
- Wi-Fi off/on and server restart, checking cursor recovery;
- remove from Recent apps;
- battery saver enabled;
- Force stop (must remain stopped and report interruption on next open);
- phone restart (must not auto-start);
- a job longer than six hours;
- verify one notification each for completed, alarm, and disconnected.

Record the phone model, Android version, battery setting, duration, reconnect
latency, and duplicate/missed event counts in docs/ANDROID-DEVICE-TESTS.md.
