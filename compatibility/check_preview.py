#!/usr/bin/env python3
"""Exercise the two preview debug APKs on a disposable English-language emulator."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

HOST = "com.xlythe.test.previewhost"
EXTENSION = "com.xlythe.test.previewextension"
serial = os.environ["ANDROID_SERIAL"]
if not re.fullmatch(r"emulator-\d+", serial):
    raise SystemExit("Select the disposable fixture emulator with ANDROID_SERIAL")


def shell(*args):
    return subprocess.check_output(["adb", "-s", serial, "shell", *args], text=True, timeout=15)


def wait_ui(label, tap=False):
    deadline = time.monotonic() + 25
    labels = []
    while time.monotonic() < deadline:
        path = "/data/local/tmp/extension-engine-preview-ui.xml"
        if "dumped to:" not in shell("uiautomator", "dump", path):
            continue
        root = ET.fromstring(shell("cat", path))
        labels = []
        for node in root.iter("node"):
            text = node.get("text", "")
            if text:
                labels.append(text)
            if text.lower() != label.lower() and not node.get("resource-id", "").endswith("/" + label):
                continue
            if tap:
                left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            return
    raise AssertionError(f"Missing {label!r}, last UI contained {labels}")


def camera_active():
    dump = shell("dumpsys", "media.camera")
    if "Active Camera Clients:" in dump:
        clients = dump.split("Active Camera Clients:", 1)[1].split("Allowed", 1)[0]
        return EXTENSION in clients
    # Android 5.1 reports only the PID of the current client.
    client = re.search(r"Client\[\d+\].*?PID: (\d+)", dump)
    if client is None:
        return False
    return any(line.split()[1] == client.group(1) and line.split()[-1] == EXTENSION
               for line in shell("ps").splitlines() if len(line.split()) > 2)


def expect_closed():
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if not camera_active():
            return
        time.sleep(0.15)
    raise AssertionError("Extension still owns the camera")


def importance():
    package = shell("pm", "list", "packages", "-U", EXTENSION)
    uid = re.search(r"uid:(\d+)", package).group(1)
    return shell("cmd", "activity", "get-uid-state", uid).strip()


def launch():
    shell("am", "start", "-W", "-n", HOST + "/com.xlythe.test.preview.PreviewActivity")


def consent():
    wait_ui("Share live preview", tap=True)
    wait_ui("OK", tap=True)


def live():
    # The host displays this status after the fifth TextureView frame update.
    wait_ui("Live camera preview")
    assert camera_active(), "Expected the extension to own an active camera"


def check_preview(sdk):
    # Reset only these two test APKs, so the OS permission prompt is exercised on every run.
    for package in (HOST, EXTENSION):
        assert "Success" in shell("pm", "clear", package)
    assert "android.permission.CAMERA" not in shell("dumpsys", "package", HOST)
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    if sdk >= 26:
        shell("wm", "dismiss-keyguard")
    else:
        shell("input", "keyevent", "82")
    launch()
    wait_ui("Share live preview", tap=True)
    wait_ui("Cancel", tap=True)
    wait_ui("Sharing declined")
    expect_closed()
    if sdk >= 23:
        wait_ui("Scan barcode in extension", tap=True)
        wait_ui("permission_deny_button", tap=True)
        wait_ui("Scan canceled")
        expect_closed()
    consent()
    if sdk >= 23:
        allow_button = "permission_allow_foreground_only_button" if sdk >= 30 else "permission_allow_button"
        wait_ui(allow_button, tap=True)
    live()
    if sdk < 30:
        wait_ui("Stop sharing", tap=True)
        expect_closed()
        print(f"PASS: API {sdk} consent, camera permission, host frames, camera owner and stop")
        return
    print("Extension UID state with a live foreground client:", importance())
    wait_ui("Stop sharing", tap=True)
    expect_closed()
    consent()
    live()
    shell("input", "keyevent", "KEYCODE_HOME")
    expect_closed()
    print("Extension UID state after the host left:", importance())
    launch()
    consent()
    live()
    try:
        shell("wm", "user-rotation", "lock", "1")
        expect_closed()
    finally:
        shell("wm", "user-rotation", "free")
    consent()
    live()
    shell("pm", "revoke", EXTENSION, "android.permission.CAMERA")
    expect_closed()
    consent()
    wait_ui("permission_allow_foreground_only_button", tap=True)
    live()
    shell("am", "force-stop", EXTENSION)
    wait_ui("Provider disconnected")
    expect_closed()
    consent()
    live()
    shell("am", "force-stop", HOST)
    expect_closed()
    print("PASS: consent, OS deny/grant, host frames, camera owner, stop, background, rotation, revocation and both process deaths")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expect-barcode", help="decoded text when the emulator camera points at a barcode")
    options = parser.parse_args()
    check_preview(int(shell("getprop", "ro.build.version.sdk")))
    if options.expect_barcode:
        sdk = int(shell("getprop", "ro.build.version.sdk"))
        if sdk >= 23:
            shell("pm", "revoke", EXTENSION, "android.permission.CAMERA")
        launch()
        wait_ui("Scan barcode in extension", tap=True)
        if sdk >= 23:
            allow = "permission_allow_foreground_only_button" if sdk >= 30 else "permission_allow_button"
            wait_ui(allow, tap=True)
        wait_ui(options.expect_barcode)
        expect_closed()
        print("PASS: barcode text returned to host")
