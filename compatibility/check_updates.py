#!/usr/bin/env python3
"""Install two signed fixture revisions on a disposable emulator."""
import argparse
from pathlib import Path
import subprocess
import time
import uuid

from check_preview import consent, expect_closed, launch, live, serial, shell, wait_ui


def install(directory, role):
    subprocess.run(["adb", "-s", serial, "install", "-r", "-d", str(directory / (role + ".apk"))],
                   check=True, timeout=90)


def probe(host, extension):
    stage = uuid.uuid4().hex
    expected = f"{stage} host={host} extension={extension}/{extension}"
    for attempt in range(25):
        # Package replacement can race Android restoring the previous Activity intent.
        if attempt % 5 == 0:
            shell("am", "start", "-W", "-n", "com.xlythe.test.host/.UpdateProbe", "--es", "stage", stage)
        logs = shell("logcat", "-d", "-s", "ExtensionUpdate:I")
        if expected in logs:
            return
        time.sleep(0.2)
    raise AssertionError(f"Missing {expected}: {logs[-1000:]}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("updated", type=Path)
    parser.add_argument("--embedded-only", action="store_true")
    args = parser.parse_args()

    install(args.original, "host")
    install(args.original, "extension")
    probe(1, 1)
    install(args.updated, "extension")
    probe(1, 2)
    install(args.updated, "host")
    probe(2, 2)
    print("PASS: latest embedded code and resources after either APK is replaced", flush=True)

    if not args.embedded_only:
        install(args.original, "previewHost")
        install(args.original, "previewExtension")
        if int(shell("getprop", "ro.build.version.sdk")) >= 23:
            shell("pm", "grant", "com.xlythe.test.previewextension", "android.permission.CAMERA")
        launch()
        consent()
        live()
        install(args.updated, "previewExtension")
        wait_ui("Provider disconnected")
        expect_closed()
        consent()
        live()
        install(args.updated, "previewHost")
        expect_closed()
        launch()
        consent()
        live()
        wait_ui("Stop sharing", tap=True)
        expect_closed()
        print("PASS: live service closed and reopened after each APK update")
