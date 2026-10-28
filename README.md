# WatchNC

WatchNC is an experimental Android phone and Wear OS companion for one observed Doosan CNC controller. The phone reads one machine over Ethernet and sends its patrol-light state to the paired watch. The watch app displays all active colors, lights off, or unknown. WatchNC Dial shows the time over a colored background; when multiple lights are active, red takes precedence over yellow, then green. The background becomes neutral when data expires.

**This is an engineering project, not a safety system.** It has been tested on one machine; controller compatibility, networking, timing and long-term reliability must be checked in your shop. Read the connection and limitations sections below before use. It does not write CNC data.

## What you need

- An Android phone running Android 8 or later, a USB Ethernet adapter supported by that phone, and a reachable, compatible CNC.
- A paired Wear OS watch. The companion app requires Android API 28 or later; **WatchNC Dial requires Wear OS 4 / API 33 or later**. The install script installs all three apps and requires the latter.
- Google Play services on both devices, a working phone/watch pairing, and notification permission on both.
- A Mac or Linux computer with [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`). Windows users can run the install script from a Bash environment such as Git Bash, with `adb` available on PATH.
- Either three trusted APKs built together, or Android Studio, JDK 17 or 21, Android SDK platform 35, and internet access to build them from this source. The first Gradle build downloads its dependencies.

## Machine and connection

This version supports the observed controller identity only: type 0, machine M, series D4G3, version 13.0. It reads the fixed R0032 byte, with bit 4 yellow, bit 6 green and bit 7 red, active high. Identity is checked before reading the relay. This identity check does not prove that another machine with the same identity has the same wiring.

Enter the CNC IPv4 address in the phone app. If the phone already has an address and route to the CNC over Ethernet, no DHCP changes are needed. The existing direct cable requires a manually assigned address, however: entering the CNC address alone does not give the phone an address on that subnet. A regular Android app cannot set that static Ethernet address. To avoid changing phone settings for that direct connection, use a compatible USB Ethernet adapter and a DHCP-capable bridge on the machine-side network, configured by someone familiar with the machine's network:

1. Keep the CNC's existing address, e.g. 192.168.1.1, and subnet unchanged. Connect the CNC and the phone's USB Ethernet adapter to the bridge's machine-side Ethernet segment.
2. Configure DHCP to give the phone a unique unused address on that subnet (e.g. 192.168.1.20/24), excluding the CNC address. Do not connect the machine segment to an untrusted network. Disable the bridge's DHCP gateway and DNS advertisements if supported, and avoid an overlapping Wi-Fi subnet. The bridge may require its own address too; prevent address conflicts.
3. Android's Ethernet and routing behavior varies by phone and adapter. Even without a gateway, Android may select Ethernet as its default network; the app cannot guarantee other apps' Wi-Fi or cellular routing. Verify simultaneous internet and CNC connectivity on the actual phone before relying on this arrangement. If Android still changes the default route, device-specific networking support or a different isolated bridge design is needed.

The app opens only its CNC socket through Android Ethernet and does not bind the process or change system network settings. It expects TCP port 8193. A bridge must provide DHCP on the same link as the CNC; merely connecting a conventional router's WAN port is not sufficient.

## Build and install

1. If building from source, open this folder in Android Studio and install the SDK components it requests. Alternatively, set `ANDROID_HOME` to your Android SDK directory and build from a terminal in this folder:

   ```sh
   ./gradlew :phone:assembleDebug :wear:assembleDebug :face:assembleDebug :common:test
   ```

   On Windows, use `gradlew.bat` in Command Prompt or PowerShell. The APKs are generated under each module's `build/outputs/apk/debug/` directory. These are debug builds, not Play Store releases.

2. Enable [developer options and USB debugging](https://developer.android.com/studio/run/device) on the phone, connect it by USB, unlock it, and accept the computer's debugging authorization prompt.
3. Connect the watch to the computer. Most watches use [wireless debugging over Wi-Fi](https://developer.android.com/training/wearables/get-started/debug-wifi): put the watch and computer on the same Wi-Fi network, enable ADB and wireless debugging on the watch, then use the pairing address and code displayed by the watch:

   ```sh
   adb pair WATCH_IP:PAIRING_PORT
   adb connect WATCH_IP:CONNECTION_PORT
   adb devices
   ```

   The connection port is usually different from the pairing port. Both devices should appear as `device` in the list. Wi-Fi is needed here for wireless installation, not for the CNC connection.
4. In a Bash terminal in this directory, run:

   ```sh
   ./install.sh
   ```

   The script installs the phone app on the phone and the companion and dial on the watch. It selects a single detected phone and watch automatically; if several are connected, it asks which serial to use. For an unattended install, provide `--phone SERIAL --watch SERIAL` using the values from `adb devices`. For a folder of trusted, prebuilt APKs named `phone-debug.apk`, `wear-debug.apk` and `face-debug.apk`, use `./install.sh --apk-dir /path/to/apks`. Run `./install.sh --help` for options. The script does not uninstall apps or erase their data; if an install fails after an earlier one succeeded, it reports the partial installation.
5. Open WatchNC on both devices and allow notifications when prompted. You can turn off USB and wireless debugging after installation.

Build the three APKs together with the **same signing key**, especially the phone and watch apps: their shared application ID and matching signing certificates are required for phone/watch data exchange. The watch face and companion should also be built and signed as a matching set so the face can use its intended complication source. Android normally creates a debug signing key per computer; separately built APKs from different computers may not communicate, and Android rejects an update signed with a different key. Keep your signing key for future updates. A debug key is intended for development, so published binaries should use a privately held release key and a consistent, documented signing process. Do not share a private key. Reinstalling after removing an incompatible existing app can erase its data; back up anything you need first.

Troubleshooting: if a device is `unauthorized`, unlock it and accept the prompt. If the watch is missing, repeat the wireless pairing/connect steps and verify that the network allows devices to reach each other. If `adb` is missing, add Platform-Tools to PATH or set `ADB` to its full executable path. If the watch face has no machine background, edit the face and choose the **CNC patrol light** complication. On Android versions that restrict sideloading, follow the device's prompts and install only APKs from a source you trust.

Connect to the machine network over Ethernet, enter the CNC IPv4 address, and tap **Start monitoring**. Stop monitoring before changing the address. A phone foreground notification shows connection status. Open WatchNC on the watch: its display requests that the screen stay on while the app is visible; tap the display to switch this off and conserve battery. The OS may still suspend or close it.

Long-press the watch face and select **WatchNC Dial**, or add the **CNC patrol light** complication to another compatible face. If the WatchNC Dial does not automatically select the source, edit the face and choose that complication. The watch posts an alert when a fresh, known lamp state changes to another known lamp state. Each alert replaces the previous one and expires after four seconds. The notification channel requests sound and vibration; the watch's settings determine whether either occurs. The first reading and transitions to or from unknown do not alert. Delivery may be delayed or suppressed by watch settings; alerts are not a complete event log.

WatchNC uses the watch's notification sound instead of selecting a tone in the app. On a Galaxy Watch, adjust it under **Settings → Sounds and vibration → Notification sound**; this can affect other notifications too. The watch's sound mode, volume, notification permissions, Do Not Disturb, and notification-channel settings control sound and vibration; for example, Galaxy Watch may need **Vibrate with sound** enabled. Android preserves existing channel settings, so a previously customized or muted WatchNC channel can override the default. See [the sound test plan](SOUND_TEST_PLAN.md) for verification and troubleshooting.

Stop the service from the phone app when finished; start it again after reboot or if Android stops it. Polling and watch updates are approximately every three seconds. Data Layer and complication updates can be delayed or throttled, and the watch app shows no current data after 15 seconds without a fresh sample; the complication's live value is valid only for that period. Phone and watch clocks must be synchronized; a significant clock difference may make readings unknown. A disconnection, invalid response, or identity mismatch also produces unknown. Brief flashes and transitions can be missed.

The R0032 mapping was observed on one machine; flashing and long-term reliability remain unverified. Relay readings do not prove that the physical lamp works. Compare the display with the actual tower during normal operation before using it for informational monitoring. It is not a safety indication and must not be the sole indication of machine state. No CNC writes are implemented.

## License and feedback

WatchNC is available under the [PolyForm Noncommercial License 1.0.0](LICENSE). It permits qualifying noncommercial use, including personal use, subject to its terms; commercial use requires a separate license. For a commercial license or to report an issue, open a GitHub issue. Please include the phone and watch models, Android and Wear OS versions, controller identity, and what you observed. Do not include network addresses, credentials, proprietary controller data, or other sensitive shop information.
