import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description="Stage real WorkManager recovery across forced Doze or emulator reboot.")
    parser.add_argument("serial")
    parser.add_argument("transition", choices=("doze", "reboot"))
    parser.add_argument("stage", choices=("prepare", "verify", "cleanup"))
    arguments = parser.parse_args()
    if not arguments.serial.startswith("emulator-") or not arguments.serial[9:].isdigit():
        parser.error("Use a dedicated emulator serial")
    root = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", arguments.serial]
    state_file = Path(tempfile.gettempdir()) / f"offline-sync-os-{arguments.serial}-{arguments.transition}.json"

    def shell(*values):
        try:
            return subprocess.check_output(adb + ["shell", *values], text=True, stderr=subprocess.STDOUT, timeout=330)
        except subprocess.CalledProcessError as failure:
            raise RuntimeError(f"ADB command failed: {' '.join(values)}\n{failure.output}") from failure

    def phase(name):
        output = shell("am", "instrument", "-w", "-r", "-e", "class",
                       "dev.offlinesync.sample.WorkManagerInteropTests#offlineEditDeliveredAfterNetworkReconnect",
                       "-e", "serverInterop", "true", "-e", "networkPhase", name,
                       "-e", "osTransition", arguments.transition,
                       "dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner")
        print(output, flush=True)
        if "OK (1 test)" not in output or "FAILURES!!!" in output:
            raise RuntimeError(f"Instrumentation failed: {name}")

    def restore(state):
        try:
            if arguments.transition == "doze":
                shell("dumpsys", "deviceidle", "unforce")
                shell("dumpsys", "battery", "reset")
                shell("input", "keyevent", "KEYCODE_WAKEUP")
        finally:
            try:
                shell("svc", "wifi", "enable" if state["wifi"] == "1" else "disable")
            finally:
                shell("svc", "data", "enable" if state["data"] == "1" else "disable")

    if shell("getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError("Wait for Android boot completion before this stage; saved cleanup state is retained")
    if arguments.stage == "prepare":
        if state_file.exists():
            raise RuntimeError(f"Unfinished test: verify or cleanup first ({state_file})")
        if shell("service", "check", "phone").strip() != "Service phone: found":
            raise RuntimeError("Android phone service is not ready; no settings were changed")
        if arguments.transition == "doze" and shell("dumpsys", "deviceidle", "enabled", "deep").strip() != "1":
            raise RuntimeError("Deep Doze is disabled; enable it on the dedicated emulator before preparation. No settings were changed")
        state = {"wifi": shell("settings", "get", "global", "wifi_on").strip(),
                 "data": shell("settings", "get", "global", "mobile_data").strip()}
        if any(value not in ("0", "1") for value in state.values()):
            raise RuntimeError("Cannot determine original radio settings")
        for apk in ("debug/sample-debug.apk", "androidTest/debug/sample-debug-androidTest.apk"):
            subprocess.run(adb + ["install", "-r", str(root / "android/sample/build/outputs/apk" / apk)], check=True, timeout=120)
        with state_file.open("x") as saved:
            json.dump(state, saved)
        try:
            shell("svc", "wifi", "disable")
            shell("svc", "data", "disable")
            phase("offline")
            if arguments.transition == "doze":
                shell("input", "keyevent", "KEYCODE_HOME")
                shell("dumpsys", "battery", "unplug")
                shell("input", "keyevent", "KEYCODE_SLEEP")
                print(shell("dumpsys", "deviceidle", "force-idle"), flush=True)
                idle = shell("dumpsys", "deviceidle", "get", "deep").strip()
                if idle != "IDLE":
                    raise RuntimeError(f"Expected forced deep IDLE, got {idle}")
                print("Confirmed deep IDLE; verify stage will exit forced idle before checking work.", flush=True)
            else:
                subprocess.run(adb + ["reboot"], check=True, timeout=30)
            print(f"PREPARED: {arguments.transition}; after boot if needed, run verify. Cleanup state: {state_file}", flush=True)
        except BaseException:
            restore(state)
            state_file.unlink()
            raise
        return
    state = json.loads(state_file.read_text())
    if arguments.stage == "cleanup":
        restore(state)
        state_file.unlink()
        print("Restored test emulator settings; existing app data/work retained.")
        return
    try:
        if arguments.transition == "doze":
            if shell("dumpsys", "deviceidle", "get", "deep").strip() != "IDLE":
                raise RuntimeError("Forced Doze did not persist between stages")
            shell("dumpsys", "deviceidle", "unforce")
            shell("dumpsys", "battery", "reset")
            shell("input", "keyevent", "KEYCODE_WAKEUP")
        phase("checkpoint")
        shell("svc", "wifi", "enable")
        shell("svc", "data", "enable")
        phase("reconnect")
        print(f"PASS: original real WorkManager request recovered after {arguments.transition}", flush=True)
    finally:
        restore(state)
        state_file.unlink()


if __name__ == "__main__":
    main()