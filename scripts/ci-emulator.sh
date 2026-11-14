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

# 3) Arabic + light mode
adb shell am force-stop $PKG
cat > /tmp/light.sh <<'X'
f=/data/data/com.watchlite.launcher/shared_prefs/watchlite.xml
grep -q ui_mode $f || sed -i 's#</map>#    <string name="ui_mode">light</string>\n    <string name="icon_style">wl_line</string>\n</map>#' $f
X
adb push /tmp/light.sh /data/local/tmp/light.sh
adb shell run-as $PKG sh /data/local/tmp/light.sh || true
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

# 4) AOD inside another app: enable AOD, open system Settings, press power, expect the AOD screen
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool aod_enabled; sleep 4
adb shell input keyevent KEYCODE_HOME; sleep 5
adb shell am start -a android.settings.SETTINGS; sleep 4
shot "x_10_settings_app" 1
adb shell input keyevent KEYCODE_POWER; sleep 4
shot "x_11_aod_after_power" 1
adb shell "input tap 160 200 & sleep 0.12; input tap 160 200"; sleep 2
shot "x_12_back_to_app" 1
adb shell input keyevent KEYCODE_POWER; sleep 3
adb shell input keyevent KEYCODE_POWER; sleep 3
shot "x_13_after_double_power" 1

# 5) Charging + "off while charging": Home on the face must NOT enter AOD
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool aod_enabled --es ci_str aod_charging_mode=never; sleep 4
adb shell dumpsys battery set ac 1; adb shell dumpsys battery set status 2
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell input keyevent KEYCODE_WAKEUP; sleep 1
adb logcat -c
adb shell input keyevent KEYCODE_HOME; sleep 3
shot "x_20_home_press_while_charging" 1
adb logcat -d | grep "WL-AOD" > $OUT/aod_charging_check.txt || true
if grep -q "WL-AOD  : enter" $OUT/aod_charging_check.txt; then echo "AOD entered while charging with mode=never" > $OUT/CRASHED; fi
adb shell dumpsys battery reset

# 6) Home on the face → AOD, Home again → screen wakes and STAYS awake
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool aod_enabled --es ci_str aod_charging_mode=normal; sleep 4
adb shell input keyevent KEYCODE_HOME; sleep 4
# press Home until the launcher reports it entered AOD (start state may vary)
for try in 1 2 3; do
  adb logcat -c; adb shell input keyevent KEYCODE_HOME; sleep 4
  adb logcat -d | grep -E "WL-AOD|WL-Home" >> $OUT/aod_home_setup.txt || true
  if adb logcat -d | grep -q "WL-AOD  : enter" && ! adb logcat -d | grep -q "WL-AOD  : exit"; then break; fi
done
shot "x_30_aod_by_home" 1
adb logcat -d | grep -E "WL-AOD|WL-Home" > $OUT/aod_home_before.txt || true
adb logcat -c
adb shell input keyevent KEYCODE_HOME; sleep 5
shot "x_31_home_wakes" 1
adb logcat -d | grep -E "WL-AOD|WL-Home" > $OUT/aod_home_check.txt || true
if ! grep -q "WL-AOD  : exit" $OUT/aod_home_check.txt || grep -q "WL-AOD  : enter" $OUT/aod_home_check.txt; then
  echo "Home in AOD did not wake the screen" > $OUT/CRASHED
fi

# 7) Phone companion UI smoke test (renders, no crash)
if [ -f app/build/outputs/apk/phone-release.apk ]; then
  adb install -r -g app/build/outputs/apk/phone-release.apk || true
  adb shell am start -n com.watchlite.companion/.MainActivity; sleep 6
  adb shell input keyevent KEYCODE_BACK; sleep 1   # dismiss a permission / BT prompt if any
  adb shell am start -n com.watchlite.companion/.MainActivity; sleep 3
  for i in 1 2 3 4 5 6; do shot "p_0$i" 1; swipe 160 330 160 90 300; done
  adb shell am start -n com.watchlite.companion/.ThemeDesignerActivity; sleep 4
  for i in 1 2 3 4 5; do shot "p_d$i" 1; swipe 160 330 160 90 300; done
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell am start -n com.watchlite.companion/.ThemePickerActivity; sleep 3
  shot "p_picker" 1
  adb shell am force-stop com.watchlite.companion
fi

adb logcat -d > $OUT/logcat_full.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|WL-" $OUT/logcat_full.txt > $OUT/logcat_app.txt || true
if grep -q "FATAL EXCEPTION" $OUT/logcat_full.txt && grep -A3 "FATAL EXCEPTION" $OUT/logcat_full.txt | grep -qE "$PKG|com.watchlite.companion"; then
  echo "APP CRASHED" > $OUT/CRASHED
fi
exit 0
