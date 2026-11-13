#!/usr/bin/env bash
# Runs on the CI emulator: renders all themes, then drives the real launcher
# with adb (gestures, settings, Arabic locale) and collects screenshots + metrics.
set -x
PKG=com.watchlite.launcher
OUT=ci-out
mkdir -p $OUT/screens
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 1) theme renders (instrumented test writes PNGs into the app's external files dir)
adb shell am instrument -w $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee $OUT/instrument.txt
adb pull /sdcard/Android/data/$PKG/files/shots $OUT/themes || true

# 2) watch-sized screen
adb shell wm size 320x385
adb shell wm density 200
adb shell cmd package set-home-activity $PKG/.home.HomeActivity || true
adb shell cmd notification allow_listener $PKG/$PKG.notifications.NotifListener || true
adb logcat -c

shot() { sleep "${2:-2.5}"; adb exec-out screencap -p > "$OUT/screens/$1.png"; }
swipe() { adb shell input swipe $1 $2 $3 $4 ${5:-180}; }

run_flow() {
  L=$1
  adb shell input keyevent KEYCODE_HOME; shot "${L}_01_home" 5
  swipe 290 190 30 190; shot "${L}_02_drawer"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  swipe 30 190 290 190; shot "${L}_03_control"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  swipe 160 40 160 340; shot "${L}_04_notifications"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  swipe 160 340 160 40; shot "${L}_05_widgets"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input swipe 160 190 160 191 1200; shot "${L}_06_editor" 4
  adb shell input swipe 250 190 60 190 150; shot "${L}_07_editor_next" 3
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell am start -n $PKG/.settings.SettingsActivity; shot "${L}_08_settings"
  adb shell input swipe 160 300 160 80 300; shot "${L}_09_settings_scrolled"
  adb shell input keyevent KEYCODE_HOME; sleep 2
}

# post a test notification (Android 8 'cmd notification post' may not exist; ignore errors)
adb shell cmd notification post -S bigtext -t "Ahmed" wl_test "Are you coming today?" || true

run_flow en

# metrics on the home screen (animated default theme)
adb shell input keyevent KEYCODE_HOME; sleep 6
adb shell dumpsys gfxinfo $PKG reset > /dev/null
sleep 10
adb shell dumpsys gfxinfo $PKG > $OUT/gfxinfo.txt
adb shell dumpsys meminfo $PKG > $OUT/meminfo.txt
adb shell top -n 1 -b | head -20 > $OUT/top.txt || true

# 3) Arabic
adb root || true
sleep 3
adb shell "setprop persist.sys.locale ar-EG; setprop ctl.restart zygote" || true
sleep 30
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 15
adb shell input keyevent KEYCODE_HOME; sleep 8
adb shell wm size 320x385; adb shell wm density 200
run_flow ar

adb logcat -d > $OUT/logcat_full.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|WL-" $OUT/logcat_full.txt > $OUT/logcat_app.txt || true
if grep -q "FATAL EXCEPTION" $OUT/logcat_full.txt && grep -A3 "FATAL EXCEPTION" $OUT/logcat_full.txt | grep -q "$PKG"; then
  echo "APP CRASHED" > $OUT/CRASHED
fi
exit 0
