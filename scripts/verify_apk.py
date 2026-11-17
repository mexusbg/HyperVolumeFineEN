#!/usr/bin/env python3
"""Verify a built APK against project metadata and write its SHA-256 checksum."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    apk = parser.parse_args().apk.resolve()
    root = Path(__file__).resolve().parents[1]
    android = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(root / "AndroidManifest.xml").getroot()
    sdk = manifest.find("uses-sdk")
    expected_package = manifest.attrib["package"]
    expected_code = manifest.attrib[android + "versionCode"]
    expected_version = manifest.attrib[android + "versionName"]
    strings = ET.parse(root / "res/values/strings.xml").getroot()
    expected_label = strings.find("string[@name='app_name']").text

    # Parse every Android resource so malformed, even unused, XML fails CI.
    for resource in (root / "res").rglob("*.xml"):
        ET.parse(resource)

    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / os.environ["BUILD_TOOLS"]
    suffix = ".exe" if os.name == "nt" else ""
    badging = subprocess.check_output(
        [str(build_tools / ("aapt2" + suffix)), "dump", "badging", str(apk)],
        encoding="utf-8",
    )
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging, re.MULTILINE,
    )
    if package is None or package.groups() != (expected_package, expected_code, expected_version):
        raise ValueError("APK package/version does not match AndroidManifest.xml")
    for field, value in (
        ("minSdkVersion", sdk.attrib[android + "minSdkVersion"]),
        ("targetSdkVersion", sdk.attrib[android + "targetSdkVersion"]),
        ("application-label", expected_label),
    ):
        if f"{field}:'{value}'" not in badging:
            raise ValueError(f"APK has an unexpected {field}")
    if f"launchable-activity: name='{expected_package}.SettingsActivity'" not in badging:
        raise ValueError("APK is missing the settings launcher activity")

    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f"Corrupt APK entry: {corrupt}")
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("APK is missing valid DEX bytecode")
        metadata = root / "src/main/resources/META-INF/xposed"
        for name in ("module.prop", "java_init.list", "scope.list"):
            packaged = archive.read("META-INF/xposed/" + name).decode("utf-8").splitlines()
            expected = (metadata / name).read_text(encoding="utf-8").splitlines()
            if packaged != expected:
                raise ValueError(f"Packaged Xposed metadata differs: {name}")
        for icon in (
            "res/mipmap-anydpi-v26/ic_launcher.xml",
            "res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "res/mipmap-anydpi-v33/ic_launcher.xml",
            "res/mipmap-anydpi-v33/ic_launcher_round.xml",
        ):
            if icon not in archive.namelist():
                raise ValueError(f"APK is missing a launcher icon: {icon}")

    # build.sh verifies the APK signature; check alignment independently here.
    subprocess.run(
        [str(build_tools / ("zipalign" + suffix)), "-c", "-p", "4", str(apk)],
        check=True,
    )
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksum_line = f"{checksum}  {apk.name}\n"
    (apk.parent / "SHA256SUMS.txt").write_text(checksum_line, encoding="utf-8")
    print(f"Verified {expected_package} {expected_version} (code {expected_code})")
    print(checksum_line, end="")


if __name__ == "__main__":
    main()
