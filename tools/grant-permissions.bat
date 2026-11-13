@echo off
chcp 65001 >nul
echo ================================================
echo   WatchLite - one-time permission setup
echo   Connect the watch (USB debugging ON) and wait.
echo ================================================
adb wait-for-device
set P=com.watchlite.launcher
adb shell pm grant %P% android.permission.WRITE_SECURE_SETTINGS
adb shell appops set %P% WRITE_SETTINGS allow
adb shell appops set %P% GET_USAGE_STATS allow
adb shell dpm set-active-admin %P%/.system.AdminReceiver
adb shell cmd notification allow_listener %P%/%P%.notifications.NotifListener
adb shell settings put secure enabled_accessibility_services %P%/%P%.system.PowerAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd package set-home-activity %P%/.home.HomeActivity
adb shell am force-stop %P%
adb shell input keyevent KEYCODE_HOME
echo.
echo Done. You can unplug the watch.
pause
