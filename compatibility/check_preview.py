#!/usr/bin/env python3
"""Exercise the two preview debug APKs on a disposable English-language emulator."""
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
    clients = dump.split("Active Camera Clients:", 1)[1].split("Allowed user IDs", 1)[0]
    return EXTENSION in clients


def expect_closed():
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if not camera_active():
            return
        time.sleep(0.15)
    raise AssertionError("Extension still owns the camera")


def launch():
    shell("am", "start", "-W", "-n", HOST + "/com.xlythe.test.preview.PreviewActivity")


def consent():
    wait_ui("Share live preview", tap=True)
    wait_ui("OK", tap=True)


def live():
    # The host displays this status after the fifth TextureView frame update.
    wait_ui("Live camera preview")
    assert camera_active(), "Expected the extension to own an active camera"


if __name__ == "__main__":
    # Reset only these two test APKs, so the OS permission prompt is exercised on every run.
    for package in (HOST, EXTENSION):
        assert "Success" in shell("pm", "clear", package)
    assert "android.permission.CAMERA" not in shell("dumpsys", "package", HOST)
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    launch()
    wait_ui("Share live preview", tap=True)
    wait_ui("Cancel", tap=True)
    wait_ui("Sharing declined")
    expect_closed()
    consent()
    wait_ui("permission_deny_button", tap=True)
    wait_ui("Sharing declined")
    expect_closed()
    consent()
    wait_ui("permission_allow_foreground_only_button", tap=True)
    live()
    wait_ui("Stop sharing", tap=True)
    expect_closed()
    consent()
    live()
    shell("input", "keyevent", "KEYCODE_HOME")
    expect_closed()
    launch()
    consent()
    live()
    shell("am", "force-stop", EXTENSION)
    wait_ui("Provider disconnected")
    expect_closed()
    consent()
    live()
    shell("am", "force-stop", HOST)
    expect_closed()
    print("PASS: consent, OS deny/grant, host frames, camera owner, stop, background and both process deaths")
