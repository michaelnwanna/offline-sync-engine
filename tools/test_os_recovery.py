import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("os_recovery", Path(__file__).with_name("os-recovery.py"))
os_recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(os_recovery)


class OsRecoveryTests(unittest.TestCase):
    def test_disabled_or_unknown_deep_doze_stops_before_install_or_radio_changes(self):
        for enabled in ("0\n", "\n", "unexpected\n"):
            with self.subTest(enabled=enabled), tempfile.TemporaryDirectory() as directory:
                with patch("sys.argv", ["os-recovery.py", "emulator-5556", "doze", "prepare"]), \
                        patch.object(os_recovery.tempfile, "gettempdir", return_value=directory), \
                        patch.object(os_recovery.subprocess, "check_output",
                                     side_effect=["1\n", "Service phone: found\n", enabled]) as shell, \
                        patch.object(os_recovery.subprocess, "run") as install:
                    with self.assertRaisesRegex(RuntimeError, "Deep Doze is disabled"):
                        os_recovery.main()
                    self.assertEqual([
                        ["getprop", "sys.boot_completed"],
                        ["service", "check", "phone"],
                        ["dumpsys", "deviceidle", "enabled", "deep"],
                    ], [call.args[0][4:] for call in shell.call_args_list])
                    install.assert_not_called()
                    self.assertEqual([], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
