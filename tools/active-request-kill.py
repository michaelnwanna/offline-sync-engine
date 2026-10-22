import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid


class CommitGate:
    def __init__(self):
        self.ready = threading.Event()
        self.release = threading.Event()
        self.error = None
        self.committed_at = None
        self.requests = 0

    def handler(self):
        gate = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *values):
                pass

            def do_POST(self):
                backend = http.client.HTTPConnection("127.0.0.1", 5087, timeout=20)
                try:
                    gate.requests += 1
                    if gate.requests != 1 or self.path != "/v1/sync/push":
                        raise RuntimeError("Gate accepts exactly one sync push")
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 1_048_576 or self.headers.get("Transfer-Encoding"):
                        raise RuntimeError("Expected bounded Content-Length request")
                    payload = self.rfile.read(length)
                    if len(payload) != length:
                        raise RuntimeError("Truncated sync request")
                    headers = {name: value for name, value in self.headers.items()
                               if name.lower() not in ("host", "connection", "content-length")}
                    backend.request("POST", self.path, body=payload, headers=headers)
                    response = backend.getresponse()
                    body = response.read(1_048_577)
                    if response.status != 200 or len(body) > 1_048_576:
                        raise RuntimeError("Backend must return a bounded successful response")
                    gate.committed_at = time.monotonic()
                    gate.ready.set()
                    if not gate.release.wait(30):
                        raise RuntimeError("Coordinator did not terminate Android before gate expiry")
                except Exception as failure:
                    gate.error = failure
                    gate.ready.set()
                finally:
                    backend.close()
                    self.close_connection = True

        return Handler


def main():
    parser = argparse.ArgumentParser(description="Kill Android while a real committed sync response is withheld.")
    parser.add_argument("serial", help="Dedicated emulator adb serial; sample process will be force-stopped")
    arguments = parser.parse_args()
    if not arguments.serial.startswith("emulator-"):
        parser.error("Use a dedicated emulator")
    root = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", arguments.serial]
    boot = subprocess.check_output(adb + ["shell", "getprop", "sys.boot_completed"], text=True, timeout=30)
    if boot.strip() != "1":
        raise RuntimeError("Emulator is not fully booted")
    for apk in ("debug/sample-debug.apk", "androidTest/debug/sample-debug-androidTest.apk"):
        subprocess.run(adb + ["install", "-r", str(root / "android/sample/build/outputs/apk" / apk)], check=True, timeout=120)
    workspace = str(uuid.uuid4())

    def instrumentation(phase):
        return adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                      "dev.offlinesync.sample.ActiveRequestInteropTests", "-e", "serverInterop", "true",
                      "-e", "workspace", workspace, "-e", "requestPhase", phase,
                      "dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner"]

    gate = CommitGate()
    with ThreadingHTTPServer(("127.0.0.1", 5088), gate.handler()) as server:
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        process = None
        try:
            process = subprocess.Popen(instrumentation("push"), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if not gate.ready.wait(90):
                raise RuntimeError("No committed response reached the gate")
            if gate.error:
                raise RuntimeError("Commit gate failed") from gate.error
            subprocess.run(adb + ["shell", "am", "force-stop", "dev.offlinesync.sample"], check=True, timeout=10)
            if time.monotonic() - gate.committed_at >= 20:
                raise RuntimeError("Process termination missed the active-request window")
            pid_check = subprocess.run(adb + ["shell", "pidof", "dev.offlinesync.sample"], capture_output=True, text=True, timeout=10)
            if pid_check.returncode != 1 or pid_check.stdout.strip():
                raise RuntimeError("Sample process is still running or PID check failed")
            gate.release.set()
            output, _ = process.communicate(timeout=30)
            if "OK (1 test)" in output:
                raise RuntimeError("Push instrumentation unexpectedly completed before termination")
            if gate.error or gate.requests != 1:
                raise RuntimeError("Commit gate did not hold exactly one request")
            print("Committed response withheld; Android process terminated before acknowledgment.", flush=True)
            verified = subprocess.check_output(instrumentation("verify"), text=True, stderr=subprocess.STDOUT, timeout=180)
            print(verified, flush=True)
            if "OK (1 test)" not in verified or "FAILURES!!!" in verified:
                raise RuntimeError("Replay verification failed")
            print(f"PASS: actual active-request process termination and exact replay, workspace {workspace}", flush=True)
        finally:
            gate.release.set()
            if process is not None and process.poll() is None:
                subprocess.run(adb + ["shell", "am", "force-stop", "dev.offlinesync.sample"], timeout=15, check=False)
                try:
                    process.communicate(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
            server.shutdown()
            serving.join(timeout=5)


if __name__ == "__main__":
    main()