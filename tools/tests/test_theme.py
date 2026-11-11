"""Offline theme validation, transport and safe-reload checks."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import theme
import tv


class ThemeFilesTests(unittest.TestCase):
    def test_reads_relative_to_caller_and_accepts_bom(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder)
            (p / "config.json").write_bytes(b'\xef\xbb\xbf{"columns":6}')
            (p / "wallpaper.jpg").write_bytes(b"wall")
            files = theme.read_theme(p)
            self.assertEqual(files["wallpaper.jpg"], b"wall")
            self.assertTrue(files["config.json"].startswith(b"\xef\xbb\xbf"))

    def test_missing_wallpaper_is_an_explicit_removal(self):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "config.json").write_text("{}")
            self.assertIsNone(theme.read_theme(folder)["wallpaper.jpg"])

    def test_rejects_bad_json_nonobjects_large_files_and_raised_dock(self):
        for text in ('{', '[]', 'null', '{"rows_below_dock":1}', ' ' * (65536 + 1)):
            with self.subTest(text=text[:40]), tempfile.TemporaryDirectory() as folder:
                (Path(folder) / "config.json").write_text(text)
                with self.assertRaises(ValueError):
                    theme.read_theme(folder)

    def test_all_shipped_themes_are_valid(self):
        for folder in (Path(__file__).resolve().parents[2] / "themes").iterdir():
            if folder.is_dir():
                self.assertIsInstance(theme.read_theme(folder)["config.json"], bytes)

    def test_invalid_local_file_never_connects(self):
        with mock.patch.object(tv, "connect") as connect:
            with self.assertRaises(SystemExit):
                theme.main(["/missing/theme"])
            connect.assert_not_called()


class ThemeTransportTests(unittest.TestCase):
    def device(self, data, native=True, damaged=False):
        dev = mock.Mock(native_push=native)

        def shell(command):
            if command.startswith("md5sum "):
                name = command.split()[1].rsplit("/", 1)[1].removesuffix(".part")
                digest = "damaged" if damaged else hashlib.md5(data[name]).hexdigest()
                return digest + " file\n\ntvhome_theme_ok\n"
            if command == theme.RELOAD:
                return "Status: ok\n"
            return "\ntvhome_theme_ok\n"

        dev.shell.side_effect = shell
        return dev

    def test_native_uploads_verified_before_replacement_and_removes_old_wall(self):
        files = {"config.json": b"{}", "wallpaper.jpg": None}
        dev = self.device(files)
        paths = []

        def push(local, remote):
            paths.append(local)
            self.assertEqual(Path(local).read_bytes(), b"{}")
            self.assertTrue(remote.endswith("config.json.part"))

        dev.push.side_effect = push
        theme.apply(dev, files)
        calls = [c.args[0] for c in dev.shell.call_args_list]
        self.assertLess(next(i for i, c in enumerate(calls) if c.startswith("md5sum")),
                        next(i for i, c in enumerate(calls) if c.startswith("mv -f")))
        self.assertTrue(any(c.startswith("rm -f " + theme.REMOTE_DIR + "/wallpaper.jpg &&") for c in calls))
        self.assertTrue(all(not os.path.exists(path) for path in paths))

    def test_python_transport_uses_checked_base64_uploads(self):
        files = {"config.json": b"{}", "wallpaper.jpg": b"wall"}
        dev = self.device(files, native=False)
        with mock.patch.object(theme.b64push, "push_base64", return_value=True) as push:
            theme.apply(dev, files)
            self.assertEqual(push.call_count, 2)
            self.assertEqual(push.call_args_list[1].args[1], b"wall")
        dev.push.assert_not_called()

    def test_base64_failure_does_not_replace_theme(self):
        files = {"config.json": b"{}", "wallpaper.jpg": None}
        dev = self.device(files, native=False)
        with mock.patch.object(theme.b64push, "push_base64", return_value=False):
            with self.assertRaisesRegex(tv.TvError, "could not send"):
                theme.apply(dev, files)
        self.assertFalse(any(c.args[0].startswith("mv -f") for c in dev.shell.call_args_list))

    def test_checksum_failure_does_not_replace_theme(self):
        files = {"config.json": b"{}", "wallpaper.jpg": b"wall"}
        dev = self.device(files, damaged=True)
        with self.assertRaisesRegex(tv.TvError, "arrived damaged"):
            theme.apply(dev, files)
        self.assertFalse(any(c.args[0].startswith("mv -f") for c in dev.shell.call_args_list))

    def test_remote_failure_is_not_reported_as_success(self):
        dev = mock.Mock()
        dev.shell.return_value = "Permission denied\n"
        with self.assertRaisesRegex(tv.TvError, "theme command failed"):
            theme.checked_shell(dev, "mkdir -p somewhere")

    def test_reload_never_stops_the_package(self):
        dev = mock.Mock()
        dev.shell.return_value = "Status: ok\n"
        theme.reload_home(dev)
        command = dev.shell.call_args.args[0]
        self.assertIn("RELOAD_THEME true", command)
        self.assertIn("default_config false", command)
        self.assertNotIn("force-stop", command)
        self.assertNotIn("am kill", command)

    def test_reload_error_is_explicit(self):
        dev = mock.Mock()
        dev.shell.return_value = "Error: Activity class does not exist\n"
        with self.assertRaisesRegex(tv.TvError, "did not confirm"):
            theme.reload_home(dev)

    def test_reset_removes_only_theme_files_and_closes_connection(self):
        dev = self.device({})
        with mock.patch.object(tv, "connect", return_value=dev):
            theme.main(["--reset", "--serial", "emulator-5554"])
        commands = [c.args[0] for c in dev.shell.call_args_list]
        self.assertEqual(len(commands), 2)
        self.assertIn("/config.json ", commands[0])
        self.assertIn("/wallpaper.jpg ", commands[0])
        self.assertNotIn("top-shelf", commands[0])
        self.assertNotIn("rm -r", commands[0])
        dev.close.assert_called_once()

    def test_connection_is_closed_on_failure(self):
        dev = self.device({})
        dev.shell.side_effect = tv.TvError("offline")
        with mock.patch.object(tv, "connect", return_value=dev):
            with self.assertRaisesRegex(SystemExit, "offline"):
                theme.main(["--reset"])
        dev.close.assert_called_once()
