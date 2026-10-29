# WatchNC

WatchNC is an experimental Android phone and Wear OS companion for monitoring one FANUC-controlled machine. The phone connects by Ethernet and sends machine readings to a paired watch. The watch app displays patrol-light colors and machine data; WatchNC Dial shows the time, watch battery, and optional readouts over a background that follows the machine lights.

**This is an engineering project, not a safety system.** It has been tested on one machine; controller compatibility, networking, timing and long-term reliability must be checked in your shop. Read the connection and limitations sections below before use. It does not write CNC data.

To get started: [download and install the release](#install-the-release), connect the phone to your machine's Ethernet network, [configure your machine's verified signals](#trying-another-fanuc-machine), then start monitoring. A fresh install or an update from an older version requires signal configuration before monitoring.

## What you need

- An Android phone running Android 8 or later, a USB Ethernet adapter supported by that phone, and a reachable, compatible CNC.
- A paired Wear OS watch. The companion app requires Android API 28 or later; **WatchNC Dial requires Wear OS 4 / API 33 or later**. The install script installs all three apps and requires the latter.
- Google Play services on both devices, a working phone/watch pairing, and notification permission on both.
- A Mac or Linux computer with [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`). Windows users can run the install script from a Bash environment such as Git Bash, with `adb` available on PATH.
- The [latest WatchNC release bundle](https://github.com/JoshuaGessner/watchnc/releases), or Android Studio, JDK 17 or 21, Android SDK platform 35, and internet access if building from source. The first Gradle build downloads its dependencies.

## Machine and connection

The known working machine is a Doosan DNM 4000 with FANUC controller identity type 0, machine M, series D4G3, version 13.0. Other FANUC Ethernet-capable machines may expose similar CNC data, but compatibility has not been established. FANUC controller functions, enabled options, PMC ladder signals, and manufacturer-specific meanings differ by machine and configuration. A successful connection does not validate a status or tool reading.

On the tested machine, patrol-light signals are R0032 bits 4 (yellow), 6 (green), and 7 (red), active high; current spindle tool is read from D450. These are **machine-builder mappings**, not FANUC defaults. Even a controller with the same identity can use different values. Ask your machine builder or dealer for the machine-specific PMC ladder and I/O documentation, and verify any mapping against the physical equipment. Never change or force PMC signals while looking for addresses. Unsupported CNC data displays as a dash; data that returns a value is not automatically validated.

### Trying another FANUC machine

1. Confirm the machine has FANUC FOCAS Ethernet enabled, find its IPv4 address and subnet using the machine documentation or your shop's existing setup, and arrange a phone Ethernet connection with an address and route to that machine. The app uses TCP port 8193; availability and controller options vary. Don't change machine network settings to match the examples below.
2. Find the three patrol-light signals in the machine-specific ladder, signal list, or electrical documentation. An authorized operator may use a read-only PMC symbol/status view to locate and observe signals. Do not edit, force, or override signals. WatchNC currently supports **three distinct bits in one R-area byte**, numbered 0–7, with one common active-high or active-low setting. Signals in different bytes, different PMC areas such as Y, or a combination of polarities cannot be represented by this setup. Contact the machine builder if the mapping is unknown.
3. With monitoring stopped, enter the machine's IP on the phone's Connect page, then open **Configure machine signals**. Enter the R-byte address in decimal and its red, yellow, and green bit numbers. Select active-low only if all three signals are active-low. Leave the D450 spindle-tool option off unless the machine's documentation and testing establish that byte's meaning. Save the settings for that machine's IP address. WatchNC keeps one machine configuration at a time; changing machines requires configuring and saving the new IP and signals.
4. Enter the machine IP, start monitoring, and compare the physical tower with both the phone and watch during ordinary operation. Verify each available light color, lights off, a transition, and reconnect behavior. Check the actual tower for flashes; short changes may be missed. Then compare program, OP, timer, feed, spindle, and any enabled tool value with the controller through normal cycles. An enabled field can still be unavailable or have a different meaning on another control.

If the signals cannot be mapped or do not match, stop monitoring and leave that machine unconfigured until its ladder and documentation can be checked. WatchNC is not a safety device, and a matching relay value does not prove a physical lamp is working. **All machines require a saved, IP-specific signal configuration**, including existing installs and the tested Doosan. The form is prefilled with the tested Doosan values as an example; they are not verified for another machine. D450 is off until explicitly enabled.

Enter the CNC IPv4 address in the phone app. If the phone already has an address and route to the CNC over Ethernet, no DHCP changes are needed. The existing direct cable requires a manually assigned address, however: entering the CNC address alone does not give the phone an address on that subnet. A regular Android app cannot set that static Ethernet address. To avoid changing phone settings for that direct connection, use a compatible USB Ethernet adapter and a DHCP-capable bridge on the machine-side network, configured by someone familiar with the machine's network:

1. Keep the CNC's existing address, e.g. 192.168.1.1, and subnet unchanged. Connect the CNC and the phone's USB Ethernet adapter to the bridge's machine-side Ethernet segment. These addresses are examples only; use your actual machine's configuration.
2. Configure DHCP to give the phone a unique unused address on that subnet (e.g. 192.168.1.20/24), excluding the CNC address. Do not connect the machine segment to an untrusted network. Disable the bridge's DHCP gateway and DNS advertisements if supported, and avoid an overlapping Wi-Fi subnet. The bridge may require its own address too; prevent address conflicts.
3. Android's Ethernet and routing behavior varies by phone and adapter. Even without a gateway, Android may select Ethernet as its default network; the app cannot guarantee other apps' Wi-Fi or cellular routing. Verify simultaneous internet and CNC connectivity on the actual phone before relying on this arrangement. If Android still changes the default route, device-specific networking support or a different isolated bridge design is needed.

The app opens only its CNC socket through Android Ethernet and does not bind the process or change system network settings. It expects TCP port 8193. A bridge must provide DHCP on the same link as the CNC; merely connecting a conventional router's WAN port is not sufficient.

## Install the release

No Android Studio, Java, or source build is needed for the release bundle. The apps remain experimental.

1. Download the **release bundle ZIP** from [GitHub Releases](https://github.com/JoshuaGessner/watchnc/releases) under Assets and extract it. Do not download the automatically generated “Source code” ZIP: it does not contain the ready-to-install APKs. Keep `install.sh` and the `apks` folder together.
2. Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on the computer. Make `adb` available on your PATH, or set the `ADB` environment variable to its full path. The script also checks common Android SDK locations.
3. Enable [developer options and USB debugging](https://developer.android.com/studio/run/device) on the phone, connect it to the computer by USB, unlock it, and accept the computer's debugging authorization prompt.
4. On the watch, enable developer options and [wireless debugging](https://developer.android.com/training/wearables/get-started/debug-wifi). Connect the watch and computer to the same Wi-Fi network. Wi-Fi is used for installation; the CNC connection uses Ethernet.
5. Open a Bash terminal in the extracted folder and run:

   ```sh
   bash install.sh
   ```

   The installer selects one connected phone and watch automatically. If it cannot find a watch, it offers to pair one: enter the **pairing IP:port**, type the code when `adb` asks for it, then enter the watch's **connection IP:port**. These ports are usually different. If several phones or watches are connected, choose the appropriate serial when asked. Windows users can run the script in Git Bash with Platform-Tools on PATH.
6. Open WatchNC on both devices and allow notifications when prompted. Long-press the watch face to select **WatchNC Dial**, then select its **CNC patrol light** complication if necessary. You can disable debugging after installation.

The script never uninstalls apps or clears data. Android will reject an update signed with a different key, including many earlier source-built debug versions. If that happens, back up anything you need and remove the incompatible previous WatchNC apps from the affected devices before installing the release; uninstalling can erase app data. Earlier installs may have succeeded if a later install fails.

The 0.1.0 beta used a different signing key. To install 0.2.0 or later over that beta, remove the old WatchNC phone app, watch app, and WatchNC Dial first. Their settings may be erased. Future releases using the same release key can be installed as updates.

### Connect the watch manually

If pairing through the installer does not work, use the addresses shown by the watch in a terminal:

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb devices
bash install.sh
```

Both devices should appear as `device` in the list. If a device is `unauthorized`, unlock it and accept the debugging prompt. If the watch is missing, repeat the pairing and connect steps and verify the network allows devices to reach one another. To select devices explicitly, run `./install.sh --phone SERIAL --watch SERIAL`; run `./install.sh --help` for more options.

## Build from source

Open this folder in Android Studio and install the SDK components it requests. Alternatively, set `ANDROID_HOME` to your Android SDK directory and build from a terminal in this folder:

```sh
./gradlew :phone:assembleDebug :wear:assembleDebug :face:assembleDebug :common:test
bash install.sh
```

On Windows, use `gradlew.bat` in Command Prompt or PowerShell to build, then Git Bash to run the install script. The script uses APKs from the Gradle debug build directories when no release bundle's `apks` folder is present. To install from a different folder containing `phone.apk`, `wear.apk`, and `face.apk` (or `phone-debug.apk`, `wear-debug.apk`, and `face-debug.apk`), pass `--apk-dir /path/to/apks`.

Build the three APKs together with the **same signing key**, especially the phone and watch apps: their shared application ID and matching signing certificates are required for phone/watch data exchange. The watch face and companion should also be built and signed as a matching set so the face can use its intended complication source. Android normally creates a debug signing key per computer; separately built APKs from different computers may not communicate, and Android rejects an update signed with a different key. Keep your signing key for future updates. A debug key is intended for development, so published binaries should use a privately held release key and a consistent, documented signing process. Do not share a private key. Reinstalling after removing an incompatible existing app can erase its data; back up anything you need first.

For maintainers, `release-signing.init.gradle` enables signing with a PKCS#12 keystore containing the alias `watchnc` and using the same password for the keystore and key. Set `WATCHNC_KEYSTORE` and `WATCHNC_PASSWORD_FILE` to absolute paths to private files, then run `./gradlew -I release-signing.init.gradle :phone:assembleRelease :wear:assembleRelease :face:assembleRelease`. Keep those files out of the repository and maintain a separate secure backup. Without the init script, release builds are unsigned.

Troubleshooting: if `adb` is missing, add Platform-Tools to PATH or set `ADB` to its full executable path. If wireless debugging says the watch is not on Wi-Fi, connect the watch to the same Wi-Fi network as the computer and check its wireless-debugging screen again. If the watch face has no machine background, edit the face and choose the **CNC patrol light** complication. On Android versions that restrict sideloading, follow the device's prompts and install only APKs from a source you trust.

Connect to the machine network over Ethernet, enter the CNC IPv4 address, configure and save the machine signals as described above, and tap **Start monitoring**. Stop monitoring before changing the address or signal mapping. A phone foreground notification shows connection status. Open WatchNC on the watch: its display requests that the screen stay on while the app is visible; tap the display to switch this off and conserve battery. The OS may still suspend or close it.

The phone's **Machine** tab and the watch show additional read-only controller data when available: the running program number, spindle and feed readings, and the controller-reported N sequence number labeled **OP**. The operator reported that the sequence number matched the controller in an initial test; it still needs verification across programs and operating states. It can remain at the last numbered block when subsequent blocks have no N number. The phone also shows raw controller status and mode codes; their meanings are controller-specific. Spindle and feed scaling and units have not been confirmed against the machine display, so the values are shown without units. Unsupported reads appear as a dash. Compare all readouts with the controller before relying on them.

Use **Settings → Watch face** in the phone app to select which readouts appear beneath the clock. The watch confirms when settings have been applied. The watch app displays the controller timer instead of spindle speed. The cycle timer reads controller parameters 6757 and 6758. It matched an initial comparison on the tested Doosan; reset and hold behavior, units, and meanings can vary. Compare it against the CNC's timer during normal operation before relying on it; if unavailable it displays a dash. The CNC light and clock remain visible regardless of selected face readouts.

The optional **Current tool** face readout uses D450, an experimental machine-specific PMC byte. An initial T10-to-T6 observation matched this DNM 4000 while the program preselected the next tool. Confirm the readout against the controller across tool changes, manual operation, resets, and reconnects before relying on it. Controller identity alone does not establish this mapping on another machine.

Long-press the watch face and select **WatchNC Dial**, or add the **CNC patrol light** complication to another compatible face. If the WatchNC Dial does not automatically select the source, edit the face and choose that complication. The watch posts an alert when a fresh, known lamp state changes to another known lamp state. Each alert replaces the previous one and expires after four seconds. The notification channel requests sound and vibration; the watch's settings determine whether either occurs. The first reading and transitions to or from unknown do not alert. Delivery may be delayed or suppressed by watch settings; alerts are not a complete event log.

WatchNC uses the watch's notification sound instead of selecting a tone in the app. On a Galaxy Watch, adjust it under **Settings → Sounds and vibration → Notification sound**; this can affect other notifications too. The watch's sound mode, volume, notification permissions, Do Not Disturb, and notification-channel settings control sound and vibration; for example, Galaxy Watch may need **Vibrate with sound** enabled. Android preserves existing channel settings, so a previously customized or muted WatchNC channel can override the default. To verify sound and vibration, observe a normal change between two known lamp states and check the alert on the watch.

Stop the service from the phone app when finished; start it again after reboot or if Android stops it. Polling aims for one second between the start of successive read cycles; slow controller responses make it slower. Data Layer and complication updates can be delayed or throttled, and the watch app shows no current data after 15 seconds without a fresh sample; the complication's live value is valid only for that period. Phone and watch clocks must be synchronized; a significant clock difference may make readings unknown. A disconnection or invalid response also produces unknown. Brief flashes and transitions can be missed.

The R0032 mapping was observed on one machine; flashing and long-term reliability remain unverified. Relay readings do not prove that the physical lamp works. Compare the display with the actual tower during normal operation before using it for informational monitoring. It is not a safety indication and must not be the sole indication of machine state. No CNC writes are implemented.

## License and feedback

WatchNC is free software, licensed under the [GNU General Public License, version 3](LICENSE) (GPL-3.0-only). Copyright © 2026 WatchNC contributors. The license permits commercial use and sale; distributing modified versions comes with obligations to provide the corresponding source under the same license. Third-party dependencies and the Gradle wrapper remain subject to their own licenses.

To report an issue, open a GitHub issue. Please include the phone and watch models, Android and Wear OS versions, controller identity, and what you observed. Do not include network addresses, credentials, proprietary controller data, or other sensitive shop information.
