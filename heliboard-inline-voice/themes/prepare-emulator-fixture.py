#!/usr/bin/env python3
"""Enable a bilingual configuration before the installed APK's live typing test.

Only runs on an emulator and preserves all existing preferences, including the
appearance whose persistence is being checked. Registering a subtype immediately
before switching refreshes HeliBoard's subtype cache and can make its normal
same-subtype fast path skip the test's keyboard reload.
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

package = sys.argv[1]
if package not in ("helium314.keyboard.compactdebug", "helium314.keyboard.compactthemesdebug"):
    raise SystemExit("Fixture is restricted to the two experimental test packages")

def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)

emulator = [adb("shell", "getprop", prop, check=True).stdout.strip() for prop in ("ro.kernel.qemu", "ro.boot.qemu")]
if b"1" not in emulator:
    raise SystemExit("Fixture must only be used on an Android emulator")

adb("shell", "am", "force-stop", package, check=True)
directory = f"/data/user_de/0/{package}/shared_prefs"
path = f"{directory}/{package}_preferences.xml"
existing = adb("exec-out", "run-as", package, "cat", path)
if existing.returncode == 0:
    root = ET.fromstring(existing.stdout)
elif b"No such file" in existing.stderr:
    root = ET.Element("map")
else:
    raise SystemExit(existing.stderr.decode(errors="replace"))

for key, value in (
    ("enabled_subtypes", "ar§KeyboardLayoutSet=MAIN:arabic;en-US§KeyboardLayoutSet=MAIN:qwerty"),
    ("selected_subtype", "ar§KeyboardLayoutSet=MAIN:arabic"),
):
    for old in list(root):
        if old.get("name") == key:
            root.remove(old)
    ET.SubElement(root, "string", name=key).text = value

payload = ET.tostring(root, encoding="utf-8", xml_declaration=True)
adb("shell", "run-as", package, "mkdir", "-p", directory, check=True)
adb("shell", "run-as", package, "tee", path, input=payload, check=True)
print("Prepared Arabic/English emulator fixture; appearance preferences retained")
