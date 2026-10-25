---
name: tv-hardware
description: Use when changing the launcher's manifest permissions or hardware requirements. Keeps optional TV hardware from becoming an implied installation filter and checks the packaged APK, not only the source.
---

# Optional TV hardware

Home works over Ethernet, Wi-Fi or offline. A permission used to show a network's name must not
make Wi-Fi hardware a requirement.

## Rules

- Declare hardware Home can run without with `android:required="false"`. Omitting the attribute
  means true; omitting the feature can let a permission imply a requirement.
- Keep permissions needed on devices with the hardware. A feature declaration does not grant a
  permission, and marking Wi-Fi optional does not replace `ACCESS_WIFI_STATE`.
- Preserve the runtime fallback. `MainActivity.updateNetworkIndicator` checks Ethernet first,
  handles a missing Wi-Fi service, shows Offline when there is no active network, and uses an
  Ethernet glyph on Ethernet or when the device does not support Wi-Fi.
- Check the APK with `aapt dump badging`, as well as `lintRelease`. Source declarations alone
  do not prove what an installer or catalogue sees. Feature requirements filter catalogues
  such as Google Play; Android's sideload installer does not enforce them.
- Do not add unrelated feature declarations just to silence lint. In particular, Android's
  location-permission filtering depends on target SDK; read the hardware guide first.

## Before and after

With only `ACCESS_WIFI_STATE`, the release APK reported
`uses-implied-feature: name='android.hardware.wifi'`. Declaring Wi-Fi optional removes that
required feature and reports `uses-feature-not-required` instead. The Home screen and its
Ethernet caption stay the same.

Run `python3 -m unittest discover -s tools/tests -p test_launcher_manifest.py` for the source
contract, then inspect the built debug and release APKs with the SDK's
`build-tools/34.0.0/aapt dump badging <apk>`. Neither may require Wi-Fi.

## Sources

- [Android: Handle TV hardware](https://developer.android.com/training/tv/get-started/hardware)
- [Android: uses-feature and permission-implied features](https://developer.android.com/guide/topics/manifest/uses-feature-element)
- [Android: Improve your code with lint](https://developer.android.com/studio/write/lint)
