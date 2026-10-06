#!/usr/bin/env python3
"""Exercise the real harness launch/readiness/EXIT cleanup without Docker or Java."""

import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parent.parent
HARNESS = ROOT / "scripts/test-payment-full-stack.sh"


def process(pid, field):
    return subprocess.run(
        ["ps", "-p", str(pid), "-o", f"{field}="],
        capture_output=True, text=True, check=False,
    ).stdout.strip()


class FrontendLifecycleTest(unittest.TestCase):
    def exercise(self, exit_code, occupied=False):
        source = HARNESS.read_text()
        cleanup = source.split("cleanup() {", 1)[1].split("trap cleanup EXIT", 1)[0]
        launch = source.split('printf \'Disposable backend started with ddl-auto=validate\\n\'\n', 1)[1]
        launch = launch.split('\n(\n  cd "$ROOT_DIR/frontend"\n  PAYMENT_E2E_API_URL=', 1)[0]
        launch = launch.replace(
            "FRONTEND_PID=$!", 'FRONTEND_PID=$!\nprintf "%s\\n" "$FRONTEND_PID" > "$PID_FILE"',
            1,
        )
        with socket.socket() as reservation, tempfile.TemporaryDirectory() as evidence:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
            if occupied:
                reservation.listen()
            else:
                reservation.close()
            runtime = Path(evidence) / "runtime"
            runtime.mkdir()
            pid_file = Path(evidence) / "frontend.pid"
            fixture = (
                'set -Eeuo pipefail\nBACKEND_PID=""\nFRONTEND_PID=""\n'
                '# The only external cleanup collaborator is stubbed; no Docker call.\n'
                'docker() { :; }\ncleanup() {' + cleanup + 'trap cleanup EXIT\n'
                + launch + '\nprintf "READY\\n"\nread -r _\n'
                + f"exit {exit_code}\n"
            )
            env = {
                key: os.environ[key] for key in ("PATH", "HOME", "NODE_OPTIONS", "TMPDIR")
                if key in os.environ
            }
            env.update(
                ROOT_DIR=str(ROOT), TMP_DIR=str(runtime), PID_FILE=str(pid_file),
                POSTGRES_CONTAINER="lifecycle-fixture-not-a-container",
                FRONTEND_PORT=str(port), FRONTEND_URL=f"http://127.0.0.1:{port}",
                BACKEND_URL="http://127.0.0.1:1",
            )
            started = time.monotonic()
            shell = subprocess.Popen(
                ["bash", "-c", fixture], env=env, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            pid = None
            identity = None
            try:
                deadline = time.monotonic() + 10
                while not pid_file.exists() and shell.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(pid_file.exists(), "Launch must record its owned PID")
                pid = int(pid_file.read_text())
                if not occupied:
                    while time.monotonic() < deadline:
                        command = process(pid, "command")
                        if command.startswith("node --input-type=module"):
                            break
                        time.sleep(0.02)
                    self.assertTrue(command.startswith("node --input-type=module"), command)
                    self.assertEqual(process(pid, "ppid"), str(shell.pid))
                    identity = (command, process(pid, "lstart"))
                    self.assertEqual(shell.stdout.readline().strip(), "READY")
                    listeners = subprocess.run(
                        ["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN"],
                        capture_output=True, text=True, check=False,
                    ).stdout
                    self.assertIn(f"127.0.0.1:{port} (LISTEN)", listeners)
                output, _ = shell.communicate(input="exit\n", timeout=10)
                self.assertEqual(shell.returncode, 1 if occupied else exit_code, output)
                if occupied:
                    self.assertIn("Disposable Vite server exited during startup.", output)
                    self.assertEqual(reservation.getsockname(), ("127.0.0.1", port))
                    self.assertGreaterEqual(reservation.fileno(), 0)
                self.assertEqual(process(pid, "pid"), "", "EXIT trap must reap Vite")
                self.assertFalse(runtime.exists(), "EXIT trap must remove its disposable directory")
                if not occupied:
                    with socket.socket() as probe:
                        self.assertNotEqual(probe.connect_ex(("127.0.0.1", port)), 0)
                print(
                    f"exit={shell.returncode} startup_failure={occupied} shell={shell.pid} "
                    f"vite={pid} port={port} PID_reaped=True listener_closed={not occupied} "
                    f"occupied_listener_preserved={occupied} "
                    f"runtime_removed=True elapsed={time.monotonic() - started:.2f}s",
                    flush=True,
                )
            finally:
                if shell.poll() is None:
                    shell.terminate()
                    shell.communicate(timeout=10)
                # Failure safety is limited to the exact node identity observed by this fixture.
                if pid and identity and (process(pid, "command"), process(pid, "lstart")) == identity:
                    os.kill(pid, signal.SIGTERM)

    def test_normal_exit_reaps_actual_vite(self):
        self.exercise(0)

    def test_failure_exit_reaps_actual_vite(self):
        self.exercise(37)

    def test_startup_failure_reaps_vite_without_touching_occupied_port(self):
        self.exercise(0, occupied=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
