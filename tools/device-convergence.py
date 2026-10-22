import argparse
import os
from pathlib import Path
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description="Run staged convergence on two separate Android installations.")
    parser.add_argument("first", help="First adb serial")
    parser.add_argument("second", help="Second adb serial")
    arguments = parser.parse_args()
    if arguments.first == arguments.second:
        parser.error("Two distinct adb serials are required")
    root = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = str(sdk / "platform-tools/adb")
    workspace = str(uuid.uuid4())
    replicas = {}
    for serial in (arguments.first, arguments.second):
        boot = subprocess.check_output([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], text=True).strip()
        if boot != "1":
            raise RuntimeError(f"Device {serial} is not booted")
        for apk in ("debug/sample-debug.apk", "androidTest/debug/sample-debug-androidTest.apk"):
            subprocess.run([adb, "-s", serial, "install", "-r", str(root / "android/sample/build/outputs/apk" / apk)], check=True, timeout=120)

    def phase(role, name):
        serial = arguments.first if role == "first" else arguments.second
        command = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "class", "dev.offlinesync.sample.DeviceConvergenceTests",
                   "-e", "serverInterop", "true", "-e", "workspace", workspace,
                   "-e", "role", role, "-e", "devicePhase", name,
                   "dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner"]
        output = subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=240)
        print(f"{serial} {role}/{name}\n{output}", flush=True)
        if "OK (1 test)" not in output or "FAILURES!!!" in output:
            raise RuntimeError(f"Instrumentation failed: {role}/{name}")
        match = re.search(r"INSTRUMENTATION_STATUS: replicaId=([0-9a-f-]{36})", output)
        if not match:
            raise RuntimeError("Missing replica identity")
        replica = match.group(1)
        if role in replicas and replicas[role] != replica:
            raise RuntimeError("Replica identity changed between phases")
        replicas[role] = replica
        if len(replicas) == 2 and replicas["first"] == replicas["second"]:
            raise RuntimeError("Installations share a replica identity")

    for role, name in (
        ("first", "seed"), ("second", "bootstrap"),
        ("first", "edit"), ("second", "edit"),
        ("first", "sync"), ("second", "sync"),
        ("first", "verify"), ("second", "verify"),
        ("first", "delete"), ("second", "late-edit"),
        ("first", "sync"), ("second", "sync"),
        ("first", "verify-deleted"), ("second", "verify-deleted"),
    ):
        phase(role, name)
    print(f"PASS: 14 phases, distinct stable replicas, workspace {workspace}", flush=True)


if __name__ == "__main__":
    main()