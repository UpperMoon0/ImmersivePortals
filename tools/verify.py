#!/usr/bin/env python3
"""Canonical Immersive Portals verification harness.

Modes:
  core  - build + JUnit + NeoForge GameTests in one Gradle invocation
  e2e   - dedicated Sable server + real graphical client + marker validation
  full  - core followed by e2e
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import ctypes
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
RESULT_DIR = ROOT / "build" / "sable-dimension-stack-e2e"
SERVER_DIR = ROOT / "run-sable-e2e-server"
CLIENT_DIR = ROOT / "run-sable-e2e-client"
SERVER_LOG = RESULT_DIR / "server.log"
CLIENT_LOG = RESULT_DIR / "client.log"


@contextmanager
def checkout_lock():
    """Prevent a second runner from erasing a live run's worlds or markers."""
    lock_path = ROOT / "build" / "verification.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as lock:
        lock.write(b"0")
        lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise RuntimeError("another verification run owns this checkout") from exc
        try:
            yield
        finally:
            if os.name == "nt":
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def gradle_cmd(*tasks: str) -> list[str]:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(wrapper), *tasks, "--no-daemon", "--no-configuration-cache", "--build-cache"]


class WindowsInteractiveProcess:
    """Minimal Popen-compatible wrapper for a process launched in another Windows session."""

    def __init__(self, pid: int, handle: int):
        self.pid = pid
        self._handle = handle
        self.returncode: int | None = None

    def poll(self) -> int | None:
        if self.returncode is not None:
            return self.returncode
        from ctypes import wintypes
        code = wintypes.DWORD()
        if not ctypes.windll.kernel32.GetExitCodeProcess(self._handle, ctypes.byref(code)):
            raise ctypes.WinError()
        if code.value == 259:  # STILL_ACTIVE
            return None
        self.returncode = code.value
        ctypes.windll.kernel32.CloseHandle(self._handle)
        self._handle = 0
        return self.returncode


def choose_windows_interactive_session(
    current_session: int,
    active_console_session: int,
    sessions: list[tuple[int, int, str]],
) -> int:
    """Choose an active logged-in Windows session without relying on a fixed session id."""
    WTS_ACTIVE = 0
    candidates = [
        session_id
        for session_id, state, username in sessions
        if state == WTS_ACTIVE and username.strip()
    ]
    if current_session in candidates:
        return current_session
    if active_console_session in candidates:
        return active_console_session
    if candidates:
        return min(candidates)
    raise RuntimeError("graphical E2E requires an active logged-in Windows desktop session")


def windows_interactive_session() -> tuple[int, int]:
    """Return (current process session, selected active graphical user session)."""
    from ctypes import wintypes

    class WTS_SESSION_INFOW(ctypes.Structure):
        _fields_ = [
            ("SessionId", wintypes.DWORD),
            ("pWinStationName", wintypes.LPWSTR),
            ("State", ctypes.c_int),
        ]

    kernel32 = ctypes.windll.kernel32
    wtsapi32 = ctypes.windll.wtsapi32
    current = wintypes.DWORD()
    if not kernel32.ProcessIdToSessionId(os.getpid(), ctypes.byref(current)):
        raise ctypes.WinError()

    buffer = ctypes.POINTER(WTS_SESSION_INFOW)()
    count = wintypes.DWORD()
    if not wtsapi32.WTSEnumerateSessionsW(None, 0, 1, ctypes.byref(buffer), ctypes.byref(count)):
        raise ctypes.WinError()

    sessions: list[tuple[int, int, str]] = []
    try:
        for index in range(count.value):
            info = buffer[index]
            username_buffer = wintypes.LPWSTR()
            username_bytes = wintypes.DWORD()
            username = ""
            if wtsapi32.WTSQuerySessionInformationW(
                None, info.SessionId, 5, ctypes.byref(username_buffer), ctypes.byref(username_bytes)
            ):
                try:
                    username = username_buffer.value or ""
                finally:
                    wtsapi32.WTSFreeMemory(username_buffer)
            sessions.append((info.SessionId, info.State, username))
    finally:
        wtsapi32.WTSFreeMemory(buffer)

    active_console = kernel32.WTSGetActiveConsoleSessionId()
    selected = choose_windows_interactive_session(current.value, active_console, sessions)
    return current.value, selected


def needs_windows_interactive_bridge(current_session: int, target_session: int) -> bool:
    return current_session != target_session


def launch_windows_interactive(
    command: list[str], env: dict[str, str], target_session: int
) -> WindowsInteractiveProcess:
    """Launch the graphical client in the selected active Windows desktop session."""
    from ctypes import wintypes


    wrapper = RESULT_DIR / "client-session.cmd"
    inherited = ("IP_SABLE_E2E", "IP_SABLE_E2E_PORT", "IP_SABLE_E2E_RESULT_DIR", "GRADLE_USER_HOME")
    lines = ["@echo off", f'cd /d "{ROOT}"']
    for key in inherited:
        value = env.get(key)
        if value:
            lines.append(f'set "{key}={value.replace("%", "%%")}"')
    lines.append(f'call {subprocess.list2cmdline(command)} > "{CLIENT_LOG}" 2>&1')
    lines.append("exit /b %ERRORLEVEL%")
    wrapper.write_text("\r\n".join(lines) + "\r\n", encoding="utf-8")

    CREATE_NEW_PROCESS_GROUP = 0x00000200
    CREATE_UNICODE_ENVIRONMENT = 0x00000400

    class STARTUPINFOW(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD), ("lpReserved", wintypes.LPWSTR), ("lpDesktop", wintypes.LPWSTR),
            ("lpTitle", wintypes.LPWSTR), ("dwX", wintypes.DWORD), ("dwY", wintypes.DWORD),
            ("dwXSize", wintypes.DWORD), ("dwYSize", wintypes.DWORD), ("dwXCountChars", wintypes.DWORD),
            ("dwYCountChars", wintypes.DWORD), ("dwFillAttribute", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
            ("wShowWindow", wintypes.WORD), ("cbReserved2", wintypes.WORD), ("lpReserved2", ctypes.POINTER(ctypes.c_byte)),
            ("hStdInput", wintypes.HANDLE), ("hStdOutput", wintypes.HANDLE), ("hStdError", wintypes.HANDLE),
        ]

    class PROCESS_INFORMATION(ctypes.Structure):
        _fields_ = [
            ("hProcess", wintypes.HANDLE), ("hThread", wintypes.HANDLE),
            ("dwProcessId", wintypes.DWORD), ("dwThreadId", wintypes.DWORD),
        ]

    token = wintypes.HANDLE()
    env_block = ctypes.c_void_p()
    wtsapi32 = ctypes.windll.wtsapi32
    userenv = ctypes.windll.userenv
    advapi32 = ctypes.windll.advapi32
    kernel32 = ctypes.windll.kernel32

    if not wtsapi32.WTSQueryUserToken(target_session, ctypes.byref(token)):
        raise ctypes.WinError()
    try:
        if not userenv.CreateEnvironmentBlock(ctypes.byref(env_block), token, False):
            raise ctypes.WinError()
        try:
            startup = STARTUPINFOW()
            startup.cb = ctypes.sizeof(startup)
            startup.lpDesktop = "winsta0\\default"
            process = PROCESS_INFORMATION()
            comspec = os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe")
            # cmd.exe requires an extra quoted command string after /c when the
            # batch path itself contains spaces. list2cmdline() alone produces a
            # syntactically valid Win32 command line but not cmd.exe's /c grammar.
            cmdline = ctypes.create_unicode_buffer(
                f'{subprocess.list2cmdline([comspec])} /d /s /c ""{wrapper}""'
            )
            if not advapi32.CreateProcessAsUserW(
                token, comspec, cmdline, None, None, False,
                CREATE_NEW_PROCESS_GROUP | CREATE_UNICODE_ENVIRONMENT,
                env_block, str(ROOT), ctypes.byref(startup), ctypes.byref(process)
            ):
                raise ctypes.WinError()
            kernel32.CloseHandle(process.hThread)
            return WindowsInteractiveProcess(process.dwProcessId, process.hProcess)
        finally:
            userenv.DestroyEnvironmentBlock(env_block)
    finally:
        kernel32.CloseHandle(token)


def launch_graphical_client(command: list[str], env: dict[str, str], creation: dict) -> tuple[object, object | None]:
    if os.name == "nt":
        current_session, target_session = windows_interactive_session()
        if needs_windows_interactive_bridge(current_session, target_session):
            print(
                f"[verify] Windows session {current_session} is non-interactive; launching client in active desktop session {target_session}",
                flush=True,
            )
            return launch_windows_interactive(command, env, target_session), None

    client_log = CLIENT_LOG.open("wb")
    try:
        process = subprocess.Popen(
            command, cwd=ROOT, env=env, stdout=client_log,
            stderr=subprocess.STDOUT, **creation,
        )
        return process, client_log
    except Exception:
        client_log.close()
        raise


def run_core() -> None:
    print("[verify] core: build + JUnit + GameTests", flush=True)
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"], cwd=ROOT, check=True)
    subprocess.run(gradle_cmd("coreCheck"), cwd=ROOT, check=True)


def prepare_e2e() -> dict[str, str]:
    # Resolve every disposable target before deleting; never follow a redirected
    # run directory outside this checkout, and never hide cleanup errors.
    for target in (RESULT_DIR, SERVER_DIR / "world", SERVER_DIR / "logs", CLIENT_DIR / "logs"):
        resolved = target.resolve()
        if resolved == ROOT.resolve() or not resolved.is_relative_to(ROOT.resolve()):
            raise RuntimeError(f"unsafe E2E cleanup target: {resolved}")
        if target.exists():
            shutil.rmtree(target)
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    CLIENT_DIR.mkdir(parents=True, exist_ok=True)

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]

    # Deterministic low-cost graphics; real rendering and Sodium remain enabled.
    (CLIENT_DIR / "options.txt").write_text(
        "onboardAccessibility:false\nrenderDistance:3\nsimulationDistance:3\n"
        "maxFps:60\nenableVsync:false\npauseOnLostFocus:false\n"
        "soundCategory_master:0.0\n", encoding="utf-8")
    (SERVER_DIR / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (SERVER_DIR / "server.properties").write_text(
        "\n".join(
            [
                "online-mode=false",
                "enforce-secure-profile=false",
                "gamemode=creative",
                "difficulty=peaceful",
                "spawn-protection=0",
                "view-distance=3",
                "simulation-distance=3",
                "enable-command-block=true",
                f"server-port={port}",
                "server-ip=127.0.0.1",
                "level-seed=12345",
                "level-type=minecraft:flat",
                'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}',
                "generate-structures=false",
                "",
            ]
        ),
        encoding="utf-8",
    )

    env = os.environ.copy()
    env["IP_SABLE_E2E_PORT"] = str(port)
    env["IP_SABLE_E2E"] = "true"
    env["IP_SABLE_E2E_RESULT_DIR"] = str(RESULT_DIR.resolve())
    return env


def wait_for_server(proc: subprocess.Popen[bytes], timeout: int = 720) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"dedicated server exited before ready (exit={proc.returncode})")
        if SERVER_LOG.exists() and "Done (" in SERVER_LOG.read_text(encoding="utf-8", errors="replace"):
            return
        time.sleep(1)
    raise TimeoutError("timed out waiting for dedicated server startup")


def check_failures() -> None:
    for side in ("server", "client"):
        marker = RESULT_DIR / f"{side}-fail.txt"
        if marker.exists():
            raise RuntimeError(marker.read_text(encoding="utf-8", errors="replace").strip())


def validate_results(client_exit: int) -> None:
    check_failures()
    if client_exit != 0:
        raise RuntimeError(f"graphical client exited with status {client_exit}")
    for name in ("server-pass", "client-pass", "client-source", "client-destination", "client-return"):
        marker = RESULT_DIR / f"{name}.txt"
        if not marker.exists() or not marker.read_text(encoding="utf-8").strip():
            raise RuntimeError(f"missing or empty authoritative result: {name}")


def wait_for_client(server: subprocess.Popen, client: subprocess.Popen, timeout: float = 300) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        check_failures()
        if server.poll() is not None:
            raise RuntimeError(f"dedicated server exited during E2E (exit={server.returncode})")
        if client.poll() is not None:
            validate_results(client.returncode)
            return
        time.sleep(0.25)
    raise TimeoutError(f"graphical client timed out after {timeout} seconds")


def stop_process_tree(proc: subprocess.Popen[bytes]) -> None:
    if os.name == "nt":
        if proc.poll() is not None:
            return
        subprocess.run(
            ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=10)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


def tail(path: Path, lines: int = 120) -> str:
    if not path.exists():
        return f"<missing {path}>"
    content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(content[-lines:])


def validate_log_health() -> None:
    critical = (
        "InjectionError",
        "InvalidInjectionException",
        "MixinApplyError",
        "MixinTransformerError",
        "Critical injection failure",
        "Exception in thread",
        "Failed to encode packet",
        "Failed to decode packet",
    )
    bad: list[str] = []
    for path in (SERVER_LOG, CLIENT_LOG):
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for token in critical:
            if token in text:
                bad.append(f"{path.name}: {token}")
    if bad:
        raise RuntimeError("critical runtime errors found after E2E pass: " + ", ".join(bad))


def run_e2e() -> None:
    print("[verify] e2e: dedicated Sable server + automated graphical client", flush=True)
    env = prepare_e2e()

    server_log = SERVER_LOG.open("wb")
    creation = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    server = subprocess.Popen(
        gradle_cmd("runSableE2EServer"),
        cwd=ROOT,
        env=env,
        stdin=subprocess.PIPE,
        stdout=server_log,
        stderr=subprocess.STDOUT,
        **creation,
    )

    try:
        wait_for_server(server)
        print("[verify] e2e server ready; launching automated client", flush=True)

        client_command = gradle_cmd("runSableE2EClient")
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            if shutil.which("xvfb-run") is None:
                raise RuntimeError("xvfb-run is required for headless Linux graphical E2E")
            client_command = ["xvfb-run", "-a", *client_command]

        client, client_log = launch_graphical_client(client_command, env, creation)
        try:
            wait_for_client(server, client)
        finally:
            stop_process_tree(client)
            if client_log is not None:
                client_log.close()

        validate_log_health()
        print("[verify] " + (RESULT_DIR / "server-pass.txt").read_text(encoding="utf-8").strip(), flush=True)
        print("[verify] " + (RESULT_DIR / "client-pass.txt").read_text(encoding="utf-8").strip(), flush=True)
    except Exception:
        print("\n[verify] --- server log tail ---", file=sys.stderr)
        print(tail(SERVER_LOG), file=sys.stderr)
        print("\n[verify] --- client log tail ---", file=sys.stderr)
        print(tail(CLIENT_LOG), file=sys.stderr)
        raise
    finally:
        if server.stdin:
            try:
                server.stdin.close()
            except OSError:
                pass
        stop_process_tree(server)
        server_log.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the canonical Immersive Portals verification suite")
    parser.add_argument("mode", choices=("core", "e2e", "full"), nargs="?", default="full")
    args = parser.parse_args()

    started = time.monotonic()
    try:
        with checkout_lock():
            stages = []
            try:
                for name, action in (("core", run_core), ("e2e", run_e2e)):
                    if args.mode not in (name, "full"):
                        continue
                    stage_started = time.monotonic()
                    stage = {"name": name, "status": "failed"}
                    stages.append(stage)
                    try:
                        action()
                        stage["status"] = "passed"
                    except Exception as exc:
                        stage["error"] = str(exc)
                        raise
                    finally:
                        stage["seconds"] = round(time.monotonic() - stage_started, 2)
            finally:
                (ROOT / "build" / f"verification-{args.mode}.json").write_text(
                    json.dumps({"stages": stages, "seconds": round(time.monotonic() - started, 2)}, indent=2),
                    encoding="utf-8")
    except (subprocess.CalledProcessError, OSError, RuntimeError, TimeoutError) as exc:
        print(f"[verify] FAILED: {exc}", file=sys.stderr)
        return 1

    print(f"[verify] PASS ({args.mode}) in {time.monotonic() - started:.1f}s", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
