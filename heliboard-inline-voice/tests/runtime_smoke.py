#!/usr/bin/env python3
"""ADB exercises against the real APK, plus a separate text-field fixture app."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
from runtime_probe import adb


def install_stable(args, out, report):
    files = list(Path(args.stable_apk_dir).rglob('*.apk'))
    assert len(files) == 1
    (out/'stable-install.txt').write_text(adb('install', str(files[0])), encoding='utf-8')
    adb('shell', 'run-as', 'helium314.keyboard.debug', 'mkdir', '-p', 'files')
    adb('shell', 'run-as', 'helium314.keyboard.debug', 'touch', 'files/compact-preservation-sentinel')
    report['checks']['stable_apk_installed_first'] = True


def fixture(out):
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk/'build-tools/37.0.0'
    jar = sdk/'platforms/android-37/android.jar'
    build = out/'fixture-build'
    build.mkdir(exist_ok=True)
    (build/'classes').mkdir(exist_ok=True)
    manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="org.heliboard.compact.smoke" android:versionCode="1" android:versionName="1">
        <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="31" />
        <application android:label="Compact runtime fixture" android:debuggable="true"
          android:theme="@android:style/Theme.Material.Light.NoActionBar">
          <activity android:name=".SmokeActivity" android:exported="true" />
        </application></manifest>'''
    (build/'AndroidManifest.xml').write_text(manifest)
    def run(command):
        p = subprocess.run([str(c) for c in command], capture_output=True, text=True, timeout=90)
        assert p.returncode == 0, p.stdout+p.stderr
    source = Path(__file__).with_name('SmokeActivity.java')
    run(['javac', '-source', '8', '-target', '8', '-classpath', jar, '-d', build/'classes', source])
    classes = list((build/'classes').rglob('*.class'))
    run([tools/'d8', '--lib', jar, '--min-api', '21', '--output', build, *classes])
    apk = build/'fixture.apk'
    run([tools/'aapt', 'package', '-f', '-M', build/'AndroidManifest.xml', '-I', jar, '-F', apk])
    with zipfile.ZipFile(apk, 'a') as z:
        z.write(build/'classes.dex', 'classes.dex')
    aligned = build/'fixture-aligned.apk'
    run([tools/'zipalign', '-f', '4', apk, aligned])
    run([tools/'apksigner', 'sign', '--ks', Path.home()/'.android/debug.keystore',
         '--ks-pass', 'pass:android', '--key-pass', 'pass:android', aligned])
    adb('install', '-r', str(aligned))


def dump(out, name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/compact-window.xml', check=False)
    text = adb('shell', 'cat', '/sdcard/compact-window.xml')
    (out/(name+'.xml')).write_text(text, encoding='utf-8')
    return ET.fromstring(text)


def bounds(node):
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(values) == 4
    return values


def tap(node):
    a,b,c,d = bounds(node)
    adb('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))
    time.sleep(0.5)


def screenshot(out, name):
    with (out/(name+'.png')).open('wb') as f:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f, check=True, timeout=30)


def preference_setup(package, out):
    en = 'en-US§KeyboardLayoutSet=MAIN:qwerty'
    ar = 'ar§KeyboardLayoutSet=MAIN:arabic'
    root = ET.Element('map')
    for key, value in [('additional_subtypes', en+';'+ar), ('enabled_subtypes', en+';'+ar),
                       ('selected_subtype', en)]:
        ET.SubElement(root, 'string', name=key).text = value
    ET.SubElement(root, 'boolean', name='show_number_row', value='false')
    ET.SubElement(root, 'boolean', name='auto_cap', value='false')
    path = out/'smoke-preferences.xml'
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)
    adb('shell', 'am', 'force-stop', package)
    adb('push', str(path), '/data/local/tmp/compact-prefs.xml')
    folder = '/data/user_de/0/'+package+'/shared_prefs'
    adb('shell', 'run-as', package, 'mkdir', '-p', folder)
    adb('shell', 'run-as', package, 'cp', '/data/local/tmp/compact-prefs.xml',
        folder+'/'+package+'_preferences.xml')


def verify_ime(args, out, report):
    if args.stable_apk_dir:
        adb('shell', 'run-as', 'helium314.keyboard.debug', 'test', '-f', 'files/compact-preservation-sentinel')
        assert 'package:' in adb('shell', 'pm', 'path', 'helium314.keyboard.debug')
        report['checks']['side_by_side_install_and_old_data_sentinel'] = True
    fixture(out)
    preference_setup(args.package, out)
    ime = args.package+'/helium314.keyboard.latin.LatinIME'
    adb('shell', 'ime', 'enable', ime)
    adb('shell', 'ime', 'set', ime)
    adb('shell', 'pm', 'grant', args.package, 'android.permission.RECORD_AUDIO')
    adb('shell', 'am', 'start', '-W', '-n', 'org.heliboard.compact.smoke/.SmokeActivity')
    time.sleep(3)
    tree = dump(out, 'english-before')
    plain = next(n for n in tree.iter('node') if n.get('content-desc') == 'plain-text-field')
    tap(plain)
    tree = dump(out, 'english-keyboard')
    keyboard = next((n for n in tree.iter('node') if n.get('resource-id','').endswith('/keyboard_view')
                     or n.get('class','').endswith('.MainKeyboardView')), None)
    assert keyboard is not None, 'Keyboard view absent; inspect XML and Logcat'
    x1,y1,x2,y2 = bounds(keyboard)
    w,h = x2-x1,y2-y1
    assert w > 0 and h > 0
    report['checks']['ime_enabled_and_visible'] = True
    report['keyboard_bounds'] = [x1,y1,x2,y2]
    def key(rx, ry):
        adb('shell', 'input', 'tap', str(int(x1+w*rx)), str(int(y1+h*ry)))
        time.sleep(0.3)
    key(0.05,0.321)  # first QWERTY key, after the fixed symbol row
    key(0.5/16,0.107)  # opening parenthesis
    tree = dump(out,'english-typed')
    text = next(n for n in tree.iter('node') if n.get('content-desc') == 'plain-text-field').get('text','')
    report['english_field_text'] = text
    assert 'q' in text.lower() and '(' in text, 'Real letter/symbol taps did not insert expected text'
    report['checks']['english_typing_and_programming_symbol'] = True
    screenshot(out,'english-layout')
    # Explicit bottom controls, each at the center of its configured width.
    for name, center in [('Ctrl',.1275), ('Alt',.1975), ('Shift',.2725), ('Tab',.3475), ('Esc',.4125)]:
        key(center,.928)
        if name in ['Ctrl','Alt','Shift']:
            key(center,.928)  # release toggled modifier
    report['checks']['modifier_keys_tapped_without_crash'] = True
    # Tab/Esc may navigate or dismiss the IME in the host editor. Restore the
    # original text field before sending the language-switch gesture.
    tree = dump(out,'after-modifiers')
    plain = next(n for n in tree.iter('node') if n.get('content-desc') == 'plain-text-field')
    tap(plain); time.sleep(1)
    tree = dump(out,'before-language-switch')
    keyboard = next((n for n in tree.iter('node') if n.get('resource-id','').endswith('/keyboard_view')
                     or n.get('class','').endswith('.MainKeyboardView')), None)
    assert keyboard is not None, 'Keyboard did not reopen after modifier checks'
    x1,y1,x2,y2 = bounds(keyboard)
    w,h = x2-x1,y2-y1
    key(.475,.928)  # language switch
    time.sleep(1)
    screenshot(out,'arabic-layout')
    key(.5/11,.321)  # Arabic ض
    tree = dump(out,'arabic-typed')
    text = next(n for n in tree.iter('node') if n.get('content-desc') == 'plain-text-field').get('text','')
    report['bilingual_field_text'] = text
    assert any('\u0600' <= c <= '\u06ff' for c in text), 'Language switch/Arabic key did not insert Arabic'
    report['checks']['language_switch_and_arabic_typing'] = True
    # Hold Arabic alef; actual popup rendering is also captured for inspection.
    px,py = str(int(x1+w*.5)),str(int(y1+h*.535))
    hold = subprocess.Popen(['adb','shell','input','swipe',px,py,px,py,'1800'])
    time.sleep(.9)
    screenshot(out,'arabic-long-press')
    assert hold.wait(timeout=10) == 0
    dump(out,'arabic-long-press')
    adb('shell', 'input', 'keyevent', '4')
    report['checks']['arabic_long_press_gesture_without_crash'] = True
    # Open/close toolbar if its expand button is exposed to accessibility.
    tree = dump(out,'toolbar-before')
    expand = next((n for n in tree.iter('node') if 'expand' in n.get('resource-id','').lower()),None)
    if expand is not None:
        tap(expand); screenshot(out,'toolbar-open'); tap(expand)
        report['checks']['toolbar_open_close'] = True
    else:
        report['toolbar_ui_test'] = 'expand button not exposed; see screenshot'
    tree = dump(out,'microphone-before')
    mic = next((n for n in tree.iter('node') if any(s in n.get('content-desc','').lower()
                for s in ['voice','ميكروفون','صوت'])),None)
    if mic is not None:
        tap(mic); time.sleep(1); screenshot(out,'microphone-after-tap')
        report['checks']['microphone_tap_keeps_ime_selected'] = args.package in adb('shell','settings','get','secure','default_input_method')
    else:
        report['microphone_ui_test'] = 'No voice control/provider available in emulator'
    report['real_audio_transcription_tested'] = False
    tree = dump(out,'field-transition')
    password = next(n for n in tree.iter('node') if n.get('content-desc') == 'password-field')
    tap(password); time.sleep(.5); screenshot(out,'password-field')
    report['checks']['change_to_password_field_without_crash'] = True
    adb('shell','input','keyevent','4')
    tree = dump(out,'hidden-keyboard')
    plain = next(n for n in tree.iter('node') if n.get('content-desc') == 'plain-text-field')
    tap(plain); time.sleep(1)
    tree = dump(out,'reopened-keyboard')
    assert any(n.get('resource-id','').endswith('/keyboard_view') or n.get('class','').endswith('.MainKeyboardView')
               for n in tree.iter('node')), 'IME view absent after reopening'
    report['checks']['hide_and_reopen_without_crash'] = True
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','1'); time.sleep(2)
    screenshot(out,'landscape')
    adb('shell','settings','put','system','user_rotation','0'); time.sleep(1)
    report['checks']['rotation_without_crash'] = True
    adb('shell','am','force-stop',args.package)
    adb('shell','am','start','-W','-n',args.package+'/helium314.keyboard.settings.SettingsActivity')
    time.sleep(2)
    assert adb('shell','pidof',args.package,check=False).strip()
    log = adb('logcat','-d','-v','threadtime')
    assert not ('FATAL EXCEPTION' in log and 'Process: '+args.package in log), 'App crashed during smoke flow'
    report['checks']['settings_relaunch_and_no_fatal_exception'] = True
