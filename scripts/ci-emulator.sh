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

# 6) Home on the face toggles AOD; Home in AOD must wake the screen and it must STAY awake.
#    One log for the whole sequence, analysed afterwards (logcat -c is not reliable on the emulator).
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool aod_enabled --es ci_str aod_charging_mode=normal; sleep 4
adb shell input keyevent KEYCODE_HOME; sleep 4
adb logcat -c; sleep 1
for k in 1 2 3 4; do adb shell input keyevent KEYCODE_HOME; sleep 5; shot "x_3${k}_home_press" 0; done
sleep 2
adb logcat -d | grep -E "WL-AOD|WL-Home" > $OUT/aod_home_check.txt || true
python3 - "$OUT" <<'PY' >> $OUT/aod_home_check.txt
import sys, re, datetime
out = sys.argv[1]
ev = []
for line in open(f"{out}/aod_home_check.txt"):
    m = re.match(r"\d+-\d+ (\d+):(\d+):([\d.]+) .*?(WL-AOD|WL-Home)\s*: (.*)", line)
    if not m: continue
    t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    kind = "home" if "home on face" in m.group(5) else m.group(5).split()[0]
    ev.append((t, kind))
ok = True; wakes = 0
for i, (t, k) in enumerate(ev):
    if k == "exit":
        # an exit followed (within 0.1 s) by a Home press = woken by Home
        if any(k2 == "home" and 0 <= t2 - t < 0.1 for t2, k2 in ev):
            wakes += 1
            # no AOD entry in the next 4 s unless Home was pressed again
            for t2, k2 in ev:
                if 0.1 < t2 - t < 4 and k2 == "home": break
                if 0.1 < t2 - t < 4 and k2 == "enter": ok = False
print(f"woken by Home: {wakes}, stayed awake: {ok}")
if wakes == 0 or not ok:
    open(f"{out}/CRASHED", "a").write("Home in AOD did not wake the screen and keep it on\n")
PY

# 6b) Double tap on the face → AOD (start from the awake face: Home toggles AOD)
adb logcat -c; adb shell input keyevent KEYCODE_HOME; sleep 2.5
if adb logcat -d | grep -q "WL-AOD  : enter"; then adb shell input keyevent KEYCODE_HOME; sleep 2.5; fi
adb logcat -c
adb shell "input tap 160 200 & sleep 0.12; input tap 160 200"; sleep 3
shot "x_32_double_tap_aod" 1
adb logcat -d | grep -E "WL-AOD|WL-Home" > $OUT/double_tap_check.txt || true
if ! grep -q "WL-AOD  : enter" $OUT/double_tap_check.txt; then echo "Double tap on the face did not enter AOD" > $OUT/CRASHED; fi
adb shell input keyevent KEYCODE_HOME; sleep 2

# 6c) Control Center tiles: toggle each safe tile, the Wi-Fi tile must settle (no flicker), no crash
python3 -m pip install -q --user pillow >/dev/null 2>&1 || pip3 install -q pillow >/dev/null 2>&1 || true
tile_xy() {  # prints "x y" centre of the view whose content-desc matches $1
  adb shell uiautomator dump /sdcard/ui.xml >> $OUT/uidump_log.txt 2>&1
  adb shell cat /sdcard/ui.xml > $OUT/ui_last.xml 2>/dev/null
  cat $OUT/ui_last.xml | tr '>' '\n' | grep "content-desc=\"$1\"" | head -1 | \
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
adb shell svc wifi enable; sleep 3
# make sure we're on the awake face (Home toggles AOD)
adb logcat -c; adb shell input keyevent KEYCODE_HOME; sleep 3
if adb logcat -d | grep -q "WL-AOD  : enter"; then adb shell input keyevent KEYCODE_HOME; sleep 3; fi
swipe 30 190 290 190; sleep 2
shot "x_39_control_open" 0
XY=$(tile_xy "واي فاي"); [ -z "$XY" ] && XY=$(tile_xy "Wi-Fi")
echo "wifi tile at $XY" > $OUT/control_check.txt
if [ -n "$XY" ]; then
  adb shell input tap $XY; sleep 2.5
  shot "x_40_wifi_off" 0
  for k in 1 2 3 4; do sleep 0.4; adb exec-out screencap -p > $OUT/screens/x_41_wifi_frame$k.png; done
  adb shell dumpsys wifi | grep -m1 "Wi-Fi is" >> $OUT/control_check.txt
  adb shell input tap $XY; sleep 3
  shot "x_42_wifi_on" 0
  adb shell dumpsys wifi | grep -m1 "Wi-Fi is" >> $OUT/control_check.txt
  python3 - "$OUT" "$XY" <<'PY' >> $OUT/control_check.txt
import sys
from PIL import Image
out, xy = sys.argv[1], sys.argv[2].split()
x, y = int(xy[0]), int(xy[1])
box = (x-25, y-25, x+25, y+25)
frames = [Image.open(f"{out}/screens/x_41_wifi_frame{k}.png").convert("RGB").crop(box).tobytes() for k in range(1, 5)]
stable = all(f == frames[0] for f in frames)
print("wifi tile stable after toggle:", stable)
if not stable:
    open(f"{out}/CRASHED", "w").write("Wi-Fi tile keeps changing after toggling\n")
PY
fi
for name in "دائمة" "توفير" "عدم الإزعاج" "بلوتوث"; do
  XY=$(tile_xy "$name"); echo "$name -> $XY" >> $OUT/control_check.txt
  [ -z "$XY" ] && continue
  adb shell input tap $XY; sleep 2; shot "x_43_tile_$(echo $name | md5sum | cut -c1-6)" 0
  adb shell dumpsys window | grep -q "mCurrentFocus.*watchlite.launcher/.home.HomeActivity" || { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
  XY=$(tile_xy "$name"); [ -n "$XY" ] && { adb shell input tap $XY; sleep 1.5; }
  adb shell dumpsys window | grep -q "mCurrentFocus.*watchlite.launcher/.home.HomeActivity" || { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
done
adb shell input keyevent KEYCODE_HOME; sleep 2

# 6d) Music widget: simulated "now playing" from the phone, with cover art
B64=$(python3 - <<'PY'
import base64, io, json
from PIL import Image, ImageDraw
im = Image.new("RGB", (104, 104))
d = ImageDraw.Draw(im)
for y in range(104): d.line([(0, y), (104, y)], fill=(40 + y, 20, 120 + y))
d.ellipse([30, 30, 74, 74], fill=(255, 200, 80))
b = io.BytesIO(); im.save(b, "JPEG", quality=78)
msg = {"type": "media.state", "payload": {"title": "Test Track Title", "artist": "Artist Name", "playing": True, "art": base64.b64encode(b.getvalue()).decode()}}
print(base64.b64encode(json.dumps(msg).encode()).decode())
PY
)
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool link_enabled; sleep 3
adb shell am start -n $PKG/.settings.SettingsActivity --es ci_link "$B64"; sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell input swipe 160 380 160 60 180; sleep 3
shot "x_50_music_1" 1
adb shell input keyevent KEYCODE_BACK; sleep 1

# 6e) Widget order: battery first, music hidden is not possible via strings, so only order here
adb shell am start -S -n $PKG/.settings.SettingsActivity --es ci_bool link_enabled --es ci_str 'widget_order=battery;prayer;steps'; sleep 3
adb shell am start -n $PKG/.settings.SettingsActivity --es screen widgets; sleep 3
shot "x_60_widgets_settings" 1
swipe 160 300 160 100 300; shot "x_61_widgets_settings2" 1
adb shell input keyevent KEYCODE_HOME; sleep 2
adb logcat -c; adb shell input keyevent KEYCODE_HOME; sleep 2.5
if adb logcat -d | grep -q "WL-AOD  : enter"; then adb shell input keyevent KEYCODE_HOME; sleep 2.5; fi
adb shell input swipe 160 380 160 60 180; sleep 3
shot "x_62_widgets_page" 1
adb shell input keyevent KEYCODE_BACK; sleep 1

# 6f) File received from the phone (simulated link messages) → Download/WatchLite + Files screen
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE || true
python3 - > $OUT/file_msgs.txt <<'PY'
import base64, json
data = ("WatchLite file transfer test\n" * 3000).encode()   # ~87 KB → 3 chunks
fid = "cifile01"
msgs = [{"type": "file.start", "payload": {"fid": fid, "name": "ci-test.txt", "size": len(data)}}]
for i in range(0, len(data), 32768):
    msgs.append({"type": "file.chunk", "payload": {"fid": fid, "i": i // 32768, "data": base64.b64encode(data[i:i+32768]).decode()}})
msgs.append({"type": "file.end", "payload": {"fid": fid, "n": len(msgs) - 1}})
for m in msgs: print(base64.b64encode(json.dumps(m).encode()).decode())
PY
adb shell am force-stop $PKG; sleep 2    # no old Settings task: each message must reach a fresh activity
while read -r M; do adb shell am start -n $PKG/.settings.SettingsActivity --activity-clear-task --es ci_link "$M" >> $OUT/files_am.txt 2>&1 < /dev/null; sleep 1.5; done < $OUT/file_msgs.txt
sleep 2
adb shell ls -l /sdcard/Download/WatchLite/ > $OUT/files_check.txt 2>&1 || true
adb shell ls -l /sdcard/Android/data/$PKG/files/received/ >> $OUT/files_check.txt 2>&1 || true
grep -q "ci-test.txt" $OUT/files_check.txt || echo "Received file not saved" >> $OUT/CRASHED
adb shell am start -n $PKG/.files.FilesActivity; sleep 3
shot "x_63_files" 1
swipe 160 300 160 100 300; shot "x_64_files2" 1
adb shell input keyevent KEYCODE_HOME; sleep 2

# 6g) Charging animation: plug in → animation for ~4 s → gone
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell dumpsys battery unplug; sleep 2
adb shell dumpsys battery set level 64
adb logcat -c
adb shell dumpsys battery set ac 1; sleep 1.3
shot "x_70_charge_anim_1" 0
sleep 0.9; shot "x_71_charge_anim_2" 0
sleep 4; shot "x_72_charge_anim_gone" 0
adb logcat -d | grep -E "WL-Charge" > $OUT/charge_check.txt || true
grep -q "power connected" $OUT/charge_check.txt || echo "Charging animation did not start" >> $OUT/CRASHED
adb shell dumpsys battery reset; sleep 1

# 6h) Calls: simulated ringing → in call → ended
msg() { python3 -c "import base64,json,sys; print(base64.b64encode(json.dumps({'type':'call.state','payload':json.loads(sys.argv[1])}).encode()).decode())" "$1"; }
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $PKG/.settings.SettingsActivity --activity-clear-task --es ci_link "$(msg '{"state":"ringing","number":"+201001234567","name":"Ahmed Ali","ringer":"normal"}')" < /dev/null; sleep 3
shot "x_80_call_ringing" 0
adb shell am start -n $PKG/.settings.SettingsActivity --activity-clear-task --es ci_link "$(msg '{"state":"offhook","number":"+201001234567","name":"Ahmed Ali","speaker":true,"elapsed":65000}')" < /dev/null; sleep 3
shot "x_81_call_ongoing" 0
adb shell am start -n $PKG/.settings.SettingsActivity --activity-clear-task --es ci_link "$(msg '{"state":"idle"}')" < /dev/null; sleep 3
shot "x_82_call_ended" 0
adb shell input keyevent KEYCODE_HOME; sleep 1

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
