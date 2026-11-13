# Installing WatchLite

1. **Developer options**: Settings → About → tap *Build number* 7 times → enable *USB debugging*.
2. **Install**: copy `app-release.apk` to the watch and open it, or `adb install -r app-release.apk`
   (no data cable? use *Wireless debugging* / `adb connect <watch-ip>:5555`).
3. **Set as home**: press Home → WatchLite → Always.
4. **Permissions** (all optional): Watch Settings → Permissions: notification access, lock screen (device admin),
   power menu (accessibility), DND access, modify system settings, location.
5. **Crown not working?** Settings → Gestures & buttons → *Buttons & wheel test*, then use *Learn up / Learn down*.
6. **Go back to the stock launcher**: Watch Settings → Permissions → Default home app. Safe mode opens automatically after repeated crashes.
