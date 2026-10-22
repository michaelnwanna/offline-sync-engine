import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description="Verify real WorkManager delivery after emulator network restoration.")
    parser.add_argument("serial", help="Dedicated emulator adb serial; radios will be temporarily disabled")
    arguments = parser.parse_args()
    if not arguments.serial.startswith("emulator-"):
        parser.error("Only dedicated emulators are supported")
    root = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    command = [str(sdk / "platform-tools/adb"), "-s", arguments.serial]

    def shell(*values):
        return subprocess.check_output(command + ["shell", *values], text=True, stderr=subprocess.STDOUT, timeout=300)

    if shell("getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError("Emulator must finish booting first")
    original_wifi = shell("settings", "get", "global", "wifi_on").strip()
    original_data = shell("settings", "get", "global", "mobile_data").strip()
    if original_wifi not in ("0", "1") or original_data not in ("0", "1"):
        raise RuntimeError("Cannot determine original radio settings")
    for apk in ("debug/sample-debug.apk", "androidTest/debug/sample-debug-androidTest.apk"):
        subprocess.run(command + ["install", "-r", str(root / "android/sample/build/outputs/apk" / apk)], check=True, timeout=120)

    def phase(name):
        output = shell("am", "instrument", "-w", "-r", "-e", "class",
                       "dev.offlinesync.sample.WorkManagerInteropTests#offlineEditDeliveredAfterNetworkReconnect",
                       "-e", "serverInterop", "true", "-e", "networkPhase", name,
                       "dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner")
        print(f"{name}\n{output}", flush=True)
        if "OK (1 test)" not in output or "FAILURES!!!" in output:
            raise RuntimeError(f"Instrumentation failed: {name}")

    try:
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        phase("offline")
        shell("svc", "wifi", "enable")
        shell("svc", "data", "enable")
        phase("reconnect")
        print("PASS: offline constraint blocking and automatic real-worker delivery after reconnect", flush=True)
    finally:
        try:
            shell("svc", "wifi", "enable" if original_wifi == "1" else "disable")
        finally:
            shell("svc", "data", "enable" if original_data == "1" else "disable")


if __name__ == "__main__":
    main()