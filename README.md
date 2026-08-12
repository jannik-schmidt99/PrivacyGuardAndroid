# Privacy Guard for Android

Proof-of-concept Android privacy firewall.

## What it does
- Lists launchable/user-installed apps.
- Shows requested privacy-sensitive permissions (camera, microphone, location, contacts, calendar, phone, SMS).
- Shows Wi-Fi RX/TX totals for the last 24 hours after Android "Usage access" is granted.
- Blocks all IPv4/IPv6 internet traffic for selected apps using Android `VpnService`, without root.
- Stores the block list locally on the device.

## Important limitation
The app does **not** decrypt HTTPS/TLS and therefore cannot reliably tell which exact personal field an app uploads (for example, whether WeChat uploaded a specific contact or message). It shows permissions and network-volume metadata. A later version can add DNS/endpoint logging with a real user-space VPN forwarding engine.

## Build
1. Open the folder in a recent Android Studio version.
2. Let Android Studio install Android SDK 36 and sync Gradle.
3. Run on a physical Android device (min Android 9 / API 28).
4. Grant Usage Access when prompted if you want per-app network totals.
5. The first time you block an app, Android will ask permission to create a local VPN.

## Testing the firewall
1. Install WeChat or another test app.
2. Search for it in Privacy Guard.
3. Enable "Sperren".
4. Try loading online content in that app. It should have no internet connectivity while other apps remain online.

## Distribution note
`QUERY_ALL_PACKAGES` and foreground VPN behavior are subject to Google Play policy/review. For private/sideloaded use this is simpler; for Play Store distribution, document the security/firewall purpose carefully.
