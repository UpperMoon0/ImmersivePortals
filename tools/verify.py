#!/usr/bin/env python3
"""Canonical Immersive Portals verification harness.

Modes:
  core  - build + JUnit + NeoForge GameTests in one Gradle invocation
  e2e   - dedicated Sable server + real graphical client + marker validation
  visual - real portal pixels + shader reload + runtime timing budgets
  full  - core followed by e2e and visual
  matrix - visual suite across renderer and optional-Sable configurations
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import ctypes
from datetime import datetime, timezone
import json
import math
import hashlib
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RESULT_DIR = ROOT / "build" / "sable-dimension-stack-e2e"
SERVER_DIR = ROOT / "run-sable-e2e-server"
CLIENT_DIR = ROOT / "run-sable-e2e-client"
SERVER_LOG = RESULT_DIR / "server.log"
CLIENT_LOG = RESULT_DIR / "client.log"
RENDERERS = ("vanilla", "sodium", "iris", "iris-active", "embeddium", "neoculus", "neoculus-active", "veil")
ACTIVE_RENDERERS = ("iris-active", "neoculus-active")
FIXTURE_NAME = "ip-clipping-fixture-v1"
VISUAL_JOB_SECONDS = 120 * 60
VISUAL_RUN_SECONDS = 112 * 60
VISUAL_CLIENT_SECONDS = 105 * 60
DIAGNOSTIC_COMMAND_SECONDS = 8
DIAGNOSTIC_MAX_JVMS = 4
DIAGNOSTIC_MAX_BYTES = 2 * 1024 * 1024
VISUAL_CLEANUP_SECONDS = 120
MATRIX = (("vanilla", True), ("sodium", True), ("iris", True), ("iris-active", True),
          ("embeddium", False), ("neoculus", False), ("neoculus-active", False),
          ("veil", False), ("vanilla", False))
GAMETEST_LOG = ROOT / "runs" / "gameTestServer" / "logs" / "latest.log"


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
    inherited = ("IP_SABLE_E2E", "IP_SABLE_E2E_PORT", "IP_SABLE_E2E_RESULT_DIR", "GRADLE_USER_HOME",
                 "IP_PORTAL_SMOKE", "IP_SHADOW_SMOKE", "IP_SMOKE_SAMPLES", "IP_SMOKE_RENDERER", "IP_SMOKE_SABLE",
                 "IP_SMOKE_NEGATIVE_CONTROL", "IP_SMOKE_FLYWHEEL_BACKEND", "IP_SMOKE_RENDER_MODE",
                 "IP_SMOKE_DISABLE_COPY_IMAGE", "IP_SMOKE_GL_CONTEXT", "MESA_GL_VERSION_OVERRIDE", "MESA_EXTENSION_OVERRIDE", "IP_SMOKE_SHADERPACK_NAME", "IP_SMOKE_DIAGNOSTIC_FIXTURE")
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


def validate_gametest_log(path: Path = GAMETEST_LOG) -> None:
    if not path.is_file():
        raise RuntimeError(f"GameTest log missing: {path}")
    content = path.read_text(encoding="utf-8", errors="replace")
    if not re.search(r"All \d+ required tests passed", content):
        raise RuntimeError("NeoForge GameTest server did not report that all required tests passed")
    if re.search(r"\b[1-9]\d* optional tests failed\b", content):
        raise RuntimeError("NeoForge GameTest server reported optional test failures")


def invalidate_generated_run_classpath(run_name: str) -> None:
    """Force NeoGradle to rebuild property-sensitive run classpaths between matrix entries."""
    neoform = ROOT / ".gradle" / "configuration" / "neoForm"
    if not neoform.is_dir():
        return
    expected_parent = f"writeMinecraftClasspath{run_name}"
    for classpath in neoform.rglob("classpath.txt"):
        if classpath.parent.name == expected_parent:
            classpath.unlink(missing_ok=True)


def find_generated_run_classpath(run_name: str) -> Path:
    neoform = ROOT / ".gradle" / "configuration" / "neoForm"
    expected_parent = f"writeMinecraftClasspath{run_name}"
    matches = sorted(
        path for path in neoform.rglob("classpath.txt")
        if path.parent.name == expected_parent
    ) if neoform.is_dir() else []
    if len(matches) != 1:
        raise RuntimeError(
            f"expected one generated classpath for {run_name}, found {len(matches)}"
        )
    return matches[0]


def renderer_mod_sources(classpath: Path, renderer: str) -> list[Path]:
    """Select direct renderer mod jars from NeoGradle's resolved client classpath."""
    entries = [Path(line.strip()) for line in classpath.read_text(encoding="utf-8").splitlines() if line.strip()]
    patterns = {
        "vanilla": (),
        "sodium": (re.compile(r"^sodium-neoforge-.*\.jar$", re.I),),
        "iris": (
            re.compile(r"^iris-.*-neoforge\.jar$", re.I),
            re.compile(r"^sodium-neoforge-.*\.jar$", re.I),
        ),
        "veil": (
            re.compile(r"^sodium-neoforge-.*\.jar$", re.I),
            re.compile(r"^veil-neoforge-.*\.jar$", re.I),
        ),
    }
    patterns["iris-active"] = patterns["iris"]
    patterns["embeddium"] = (re.compile(r"^embeddium-.*\.jar$", re.I),)
    patterns["neoculus"] = (*patterns["embeddium"], re.compile(r"^(?:neoculus|oculus)-.*\.jar$", re.I))
    patterns["neoculus-active"] = patterns["neoculus"]
    if renderer not in patterns:
        raise RuntimeError(f"unknown renderer staging request: {renderer}")

    selected: list[Path] = []
    for pattern in patterns[renderer]:
        matches = [
            path for path in entries
            if path.is_file() and "-sources.jar" not in path.name and pattern.match(path.name)
        ]
        if len(matches) != 1:
            raise RuntimeError(
                f"expected one {renderer} renderer jar matching {pattern.pattern}, found {len(matches)}"
            )
        if matches[0] not in selected:
            selected.append(matches[0])
    return selected


def stage_renderer_mods(renderer: str, properties: list[str]) -> list[Path]:
    """Stage renderer jars as discoverable NeoForge mods for the smoke client."""
    if renderer == "vanilla":
        return []
    subprocess.run(
        gradle_cmd("writeMinecraftClasspathPortalSmokeClient", *properties),
        cwd=ROOT,
        check=True,
    )
    sources = renderer_mod_sources(find_generated_run_classpath("PortalSmokeClient"), renderer)
    mods = CLIENT_DIR / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    created: list[Path] = []
    try:
        for source in sources:
            target = mods / f"__ip_verify__{source.name}"
            if target.exists():
                raise RuntimeError(f"renderer verification will not overwrite existing mod: {target}")
            shutil.copy2(source, target)
            created.append(target)
        return created
    except Exception:
        for target in created:
            target.unlink(missing_ok=True)
        raise


def cleanup_staged_renderer_mods(paths: list[Path]) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def shader_profile_options(properties: str, requested: str | None = None) -> tuple[str, dict[str, str]]:
    """Expand the pack's own named preset, never guess shader macro names or values."""
    properties = re.sub(r"\\\r?\n\s*", " ", properties)
    profiles = dict(re.findall(r"^\s*profile\.([^\s=]+)\s*=\s*(.*?)\s*$", properties, re.M))
    selected = requested or next((name for name in ("no_effects", "POTATO", "VERYLOW", "shadowless_low", "low", "LOW") if name in profiles), "default")
    if selected == "default":
        return selected, {}
    if selected not in profiles:
        raise RuntimeError(f"shaderpack has no profile {selected!r}; available: {', '.join(profiles)}")

    def expand(name: str, parents: tuple[str, ...] = ()) -> dict[str, str]:
        if name in parents or name not in profiles:
            raise RuntimeError(f"invalid shader profile inheritance: {name}")
        options = {}
        for token in profiles[name].split():
            if token.startswith("profile."):
                options.update(expand(token[8:], (*parents, name)))
            elif token.startswith("program.") or token.startswith("!program."):
                raise RuntimeError("this shader profile changes program enablement; select an option-only low profile")
            elif "=" in token or ":" in token:
                key, value = re.split(r"[=:]", token, maxsplit=1)
                options[key] = value
            else:
                options[token.lstrip("!")] = str(not token.startswith("!")).lower()
        if any(not re.fullmatch(r"[A-Za-z0-9_.-]+", key) or not re.fullmatch(r"[A-Za-z0-9_.+-]+", value) for key, value in options.items()):
            raise RuntimeError("shader profile contains an unsupported option value")
        return options
    return selected, expand(selected)


def pinned_shaderpack_profile(sha256: str) -> str | None:
    manifest = json.loads((ROOT / "tools/shaderpacks/real-packs.json").read_text(encoding="utf-8"))
    return next((pack["profile"] for pack in manifest.values() if pack["sha256"] == sha256), None)


def visual_scene_names(renderer: str, render_mode: str = "normal", diagnostic_fixture: bool = True) -> set[str]:
    scenes = {"solid-visible", "solid-clipped", "mirror", "create-visible", "create-clipped",
              "create-crumbling-clean", "create-crumbling-damaged", "create-crumbling-restored", "create-crumbling-clipped"}
    if render_mode == "normal":
        scenes.update(("nested", "create-nested"))
    if renderer in ACTIVE_RENDERERS and not diagnostic_fixture:
        scenes.update(("solid-background", "mirror-background", "mirror-visible", "create-background"))
        if render_mode == "normal":
            scenes.update(("nested-background", "nested-visible", "create-nested-background"))
    if renderer in ACTIVE_RENDERERS and diagnostic_fixture:
        scenes.update(f"{program}-{side}" for program in ("cutout", "translucent", "entity", "block-entity", "particle")
                      for side in ("visible", "clipped"))
    return scenes


def visual_timeout(renderer: str, samples: int, render_mode: str, diagnostic_fixture: bool) -> float:
    # Software-driver budget: up to 90 seconds to build/settle/check each scene,
    # three epochs for active shaders, plus startup/crossing and consecutive frame samples.
    epochs = 3 if renderer in ACTIVE_RENDERERS else 2
    estimated = max(900, len(visual_scene_names(renderer, render_mode, diagnostic_fixture)) * epochs * 90 + samples / 4 + 300)
    return min(VISUAL_CLIENT_SECONDS, estimated)


def stage_shader_fixture(renderer: str, negative_control: str = "none", shaderpack_file: Path | None = None, shaderpack_profile: str | None = None, shadow_fixture: bool = False) -> dict:
    """Own only the disposable verification directories; never download a mutable pack."""
    fixture_name = "ip-shadow-fixture-v1" if shadow_fixture else FIXTURE_NAME
    source = ROOT / "tools" / "shaderpacks" / fixture_name
    target = CLIENT_DIR / "shaderpacks" / fixture_name
    if target.exists():
        if target.is_symlink() or not target.resolve().is_relative_to(CLIENT_DIR.resolve()):
            raise RuntimeError("unsafe shader fixture staging target")
        shutil.rmtree(target)
    shutil.copytree(source, target)
    digest = hashlib.sha256()
    for path in sorted(source.rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(source).as_posix().encode())
            digest.update(path.read_bytes())
    pack_name = fixture_name
    profile, options = "fixture", {}
    pack_hash = digest.hexdigest()
    programs = sorted(p.stem for p in (source / "shaders").glob("*.vsh"))
    if shaderpack_file is not None:
        shaderpack_file = shaderpack_file.resolve()
        if not shaderpack_file.is_file() or shaderpack_file.suffix.lower() != ".zip":
            raise RuntimeError("--shaderpack-file must be an existing .zip shaderpack")
        pack_hash = hashlib.sha256(shaderpack_file.read_bytes()).hexdigest()
        requested_profile = shaderpack_profile if shaderpack_profile is not None else pinned_shaderpack_profile(pack_hash)
        with zipfile.ZipFile(shaderpack_file) as archive:
            programs = sorted(name for name in archive.namelist() if name.endswith(".vsh"))
            properties = archive.read("shaders/shaders.properties").decode("utf-8-sig") if "shaders/shaders.properties" in archive.namelist() else ""
            profile, options = shader_profile_options(properties, requested_profile)
            if not any(name.startswith("shaders/") for name in archive.namelist()):
                raise RuntimeError("shaderpack ZIP must contain a top-level shaders directory")
        pack_name = "__ip_verify__" + shaderpack_file.name
        pack_target = CLIENT_DIR / "shaderpacks" / pack_name
        if pack_target.is_symlink():
            raise RuntimeError("unsafe external shaderpack staging target")
        shutil.copy2(shaderpack_file, pack_target)
        pack_hash = hashlib.sha256(shaderpack_file.read_bytes()).hexdigest()
    (CLIENT_DIR / "shaderpacks" / (pack_name + ".txt")).write_text(
        "".join(f"{key}={value}\n" for key, value in sorted(options.items())), encoding="utf-8")
    enabled = renderer in ACTIVE_RENDERERS and negative_control != "pack-disabled"
    config = CLIENT_DIR / "config"
    config.mkdir(parents=True, exist_ok=True)
    # NeOculus reads oculus.properties, while official Iris reads iris.properties.
    for name in ("iris.properties", "oculus.properties"):
        (config / name).write_text(
            f"shaderPack={pack_name}\nenableShaders={str(enabled).lower()}\n"
            "enableDebugOptions=true\ndisableUpdateMessage=true\n", encoding="utf-8")
    evidence = {"name": pack_name, "sha256": pack_hash, "enabled": enabled,
                "diagnostic_fixture": shaderpack_file is None, "shadow_fixture": shadow_fixture, "profile": profile, "options": options,
                "expected_active": renderer in ACTIVE_RENDERERS,
                "programs": programs}
    (RESULT_DIR / "fixture.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    return evidence


def validate_target_shader_path(evidence: dict, target: str, expect_clipping: bool) -> None:
    """Compilation alone is not proof that the intended drawing path ran in a portal."""
    sources = evidence.get("sourceByDrawName", {})
    selected = evidence.get("selectedProgramCounts", {})
    bypassed = evidence.get("bypassedProgramCounts", {})
    count_key = "portalWithUniform" if expect_clipping else "portalWithoutUniform"
    for name, counts in evidence.get("completedDrawCounts", {}).items():
        matches = name.startswith("entities_") if target == "entity" else name in ("particles", "particles_trans")
        if matches and name in sources and selected.get("VANILLA:" + name, 0) > 0 and counts.get(count_key, 0) > 0:
            if expect_clipping or bypassed.get("VANILLA:" + name, 0) > 0:
                return
    raise RuntimeError(f"no completed portal {target} draw with clipping={expect_clipping}")


def validate_crumbling_evidence(check: dict, clean: dict | None) -> None:
    scene = check["scene"]
    proof = check.get("crumbling_oracle", {})
    stage = 9 if scene in ("create-crumbling-damaged", "create-crumbling-clipped") else -1
    position = [0, 82, 3 if scene.endswith("-clipped") else -1]
    if proof.get("stage") != stage or proof.get("client_stage") != stage or proof.get("position") != position or proof.get("block") != "create:large_cogwheel" or proof.get("packet_sent") is not True:
        raise RuntimeError("crumbling stage was not sent and observed on the intended cog")
    if proof.get("face_samples", 0) < 100 or proof.get("background_samples", 0) < 100:
        raise RuntimeError("crumbling masks did not sample the cog and backdrop")
    if scene not in ("create-crumbling-damaged", "create-crumbling-restored"):
        return
    if not clean or proof.get("fixture_generation") != clean.get("fixture_generation") or not proof.get("fixture_unchanged") or not proof.get("camera_unchanged"):
        raise RuntimeError("crumbling comparison changed its base fixture or camera")
    measurement = proof.get("measurement", {})
    required = ("samples", "darkenedPixels", "brightenedPixels", "unchangedPixels", "meanDarkening", "darkeningEnergy",
                "brighteningEnergy", "meanAbsoluteChange", "changedFraction", "backgroundDrift", "backgroundResidual")
    if any(not isinstance(measurement.get(key), (int, float)) or not math.isfinite(measurement[key]) for key in required):
        raise RuntimeError("missing or invalid localized crumbling measurement")
    samples = measurement["samples"]
    if samples != proof["face_samples"] or any(measurement[key] < 0 for key in ("darkenedPixels", "brightenedPixels", "unchangedPixels", "darkeningEnergy", "brighteningEnergy", "meanAbsoluteChange", "changedFraction", "backgroundResidual")):
        raise RuntimeError("invalid crumbling sample counts or energies")
    if abs(measurement["backgroundDrift"]) > 1 or measurement["backgroundResidual"] > 1:
        raise RuntimeError("crumbling backdrop changed independently of damage")
    if scene == "create-crumbling-damaged":
        if measurement["meanDarkening"] <= 0.5 or measurement["darkenedPixels"] < max(20, samples * 0.02) or measurement["unchangedPixels"] < samples * 0.05 or measurement["darkeningEnergy"] <= 2 * measurement["brighteningEnergy"]:
            raise RuntimeError("cog damage did not show localized directional darkening")
    elif measurement["meanAbsoluteChange"] > 1 or measurement["changedFraction"] > 0.01:
        raise RuntimeError("cog did not return to its clean image after damage cleared")


def validate_crossing_motion(check: dict) -> None:
    motion = check.get("crossing_motion", {})
    source, arrival, capture = (motion.get(key, {}) for key in ("source", "first_destination", "capture"))
    identity = source.get("player_uuid")
    if not identity or source.get("dimension") != "minecraft:overworld" or source.get("player_dimension") != "minecraft:overworld" or source.get("forward_input") is not True:
        raise RuntimeError("crossing did not begin with observed source-world player input")
    for pose in (arrival, capture):
        if pose.get("player_uuid") != identity or pose.get("dimension") != "minecraft:the_nether" or pose.get("player_dimension") != "minecraft:the_nether":
            raise RuntimeError("crossing player ownership was not preserved in the destination")
    def in_aisle(point):
        return isinstance(point, list) and len(point) == 3 and all(isinstance(v, (int, float)) and math.isfinite(v) for v in point) and abs(point[0]) <= 0.25 and abs(point[1] - 82) <= 0.25 and -2 <= point[2] <= 0.25
    if not all(in_aisle(point) for point in (arrival.get("eye"), capture.get("eye"), capture.get("camera"))):
        raise RuntimeError("crossing observer drifted outside the portal-to-wall aisle")
    velocity = capture.get("velocity", [])
    if len(velocity) != 3 or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in velocity) or sum(v*v for v in velocity) >= 1e-12 or capture.get("forward_input") is not False or motion.get("velocity_stops", 0) <= 0:
        raise RuntimeError("crossing observer retained movement at framebuffer capture")
    if capture.get("wall_chunk_loaded") is not True or capture.get("wall_block") != "minecraft:lime_concrete":
        raise RuntimeError("native destination wall chunk was missing after crossing")
    server = capture.get("server", {}).get("current", {})
    if server.get("player_uuid") != identity or server.get("dimension") != "minecraft:the_nether" or not in_aisle(server.get("eye")):
        raise RuntimeError("authoritative server crossing pose disagreed with client")


def validate_shader_evidence(renderer: str, render_mode: str = "normal", gl_context: str = "default") -> None:
    """A green screenshot alone cannot establish active-shader compatibility."""
    fixture_path = RESULT_DIR / "fixture.json"
    fixture = json.loads(fixture_path.read_text()) if fixture_path.is_file() else {"name": FIXTURE_NAME, "diagnostic_fixture": True}
    if fixture.get("shadow_fixture"):
        from shadow_evidence import validate_shadow_evidence
        validate_shadow_evidence(RESULT_DIR, renderer)
        return
    path = RESULT_DIR / "runtime-evidence.json"
    if not path.is_file():
        raise RuntimeError("missing runtime shader/renderer evidence")
    report = json.loads(path.read_text(encoding="utf-8"))
    phases = report.get("checks", [])
    required = {"before-reload", "after-reload", "after-toggle"} if renderer in ACTIVE_RENDERERS else {"before-reload", "after-reload"}
    if not required.issubset({p.get("phase") for p in phases}):
        raise RuntimeError("runtime evidence is missing reload/toggle phases")
    scenes = visual_scene_names(renderer, render_mode, fixture.get("diagnostic_fixture", True))
    observed = {(check.get("phase"), check.get("scene")) for check in phases}
    if not {(phase, scene) for phase in required for scene in scenes}.issubset(observed):
        raise RuntimeError("runtime evidence is missing per-program positive/clipping scenes")
    if ("after-crossing", "crossing") not in observed or not (RESULT_DIR / "crossing-server-pass.txt").is_file():
        raise RuntimeError("cross-dimension player crossing was not verified")
    for check in phases:
        if gl_context == "no-copy-image":
            if check.get("copy_image_available") is not False or not check.get("gl_version", "").startswith("3.3"):
                raise RuntimeError("requested GL3.3 context still advertised copy-image or had wrong version")
            if check.get("forced_framebuffer_blit"):
                raise RuntimeError("capability-absent lane must not rely on the forced-blit override")
        if renderer in ACTIVE_RENDERERS:
            if not check.get("shaders_active") or check.get("pack") != fixture["name"]:
                raise RuntimeError("shader fixture was not active in runtime evidence")
            if "Iris" not in check.get("pipeline", "") or "Vanilla" in check.get("pipeline", ""):
                raise RuntimeError("expected an actual Iris shader pipeline")
            if check.get("renderer") not in {"IrisPortalRenderer", "IrisCompatibilityPortalRenderer"}:
                raise RuntimeError("expected CE Iris portal renderer, not stencil fallback")
        elif check.get("shaders_active"):
            raise RuntimeError("shaders-off lane unexpectedly had an active shaderpack")
        if renderer in ACTIVE_RENDERERS and check.get("shader_options", {}) != fixture.get("options", {}):
            raise RuntimeError("live shaderpack options do not match the recorded low preset")
    if renderer in ACTIVE_RENDERERS and not report.get("toggle_disabled_verified"):
        raise RuntimeError("shader disable/enable was not verified")
    if renderer in ACTIVE_RENDERERS:
        copy = report.get("framebuffer_copy", {})
        cases = copy.get("cases", [])
        if copy.get("passed") is not True or len(cases) != 4 or {(c.get("width"), c.get("forcedBlit")) for c in cases} != {(8, False), (8, True), (13, False), (13, True)}:
            raise RuntimeError("missing live framebuffer copy cases")
        for case in cases:
            color = case.get("color", [])
            if len(color) != 4 or any(not isinstance(v, (int, float)) or not math.isfinite(v) or abs(v - expected) > 0.006
                                     for v, expected in zip(color, (0.25, 0.75, 0.5, 1.0))):
                raise RuntimeError("framebuffer color evidence failed")
            depth = case.get("depth", -1)
            if case.get("stateRestored") is not True or not math.isfinite(depth) or abs(depth - 0.375) > 0.00001 or case.get("stencil") != 77:
                raise RuntimeError("framebuffer depth/stencil/state evidence failed")
            if case["forcedBlit"] and case.get("path") != "framebuffer-blit":
                raise RuntimeError("forced framebuffer blit was not exercised")
    dimensions = {check["phase"]: (check.get("width"), check.get("height")) for check in phases if check.get("scene") == "solid-clipped"}
    if dimensions.get("before-reload") == dimensions.get("after-reload"):
        raise RuntimeError("framebuffer resize was not observed after reload")
    clean_cogs = {check["phase"]: check.get("crumbling_oracle", {}) for check in phases if check.get("scene") == "create-crumbling-clean"}
    for check in phases:
        scene = check.get("scene", "")
        if scene == "crossing":
            validate_crossing_motion(check)
        if scene.startswith("create-crumbling-"):
            validate_crumbling_evidence(check, clean_cogs.get(check["phase"]))
        if renderer in ACTIVE_RENDERERS and not fixture.get("diagnostic_fixture", True) and scene != "crossing":
            witness = check.get("reference_witness", {})
            reference = witness.get("reference_scene")
            expected_reference = scene if scene.endswith("-background") else (
                "solid-background" if scene.startswith("solid-") else "nested-background" if scene.startswith("nested")
                else "mirror-background" if scene.startswith("mirror") else "create-nested-background" if scene == "create-nested"
                else "create-background")
            if reference != expected_reference:
                raise RuntimeError("real-pack scene used the wrong camera/dimension backdrop")
            if not reference or (check["phase"], reference) not in observed:
                raise RuntimeError("missing same-pack background reference scene")
            if not (RESULT_DIR / witness.get("reference_screenshot", "missing")).is_file():
                raise RuntimeError("missing same-pack background reference screenshot")
            depth_targets = {"solid-background": ("minecraft:the_nether:1", 7), "create-background": ("minecraft:the_nether:1", 7),
                             "nested-background": ("minecraft:the_end:2", 8), "create-nested-background": ("minecraft:the_nether:2", 8),
                             "mirror-background": ("minecraft:overworld:1", 10)}
            target, expected = depth_targets[expected_reference]
            distance, backdrop, closest = (witness.get(key, -1) for key in ("view_distance", "background_view_distance", "closest_view_distance"))
            expectation = "same" if scene.endswith(("-background", "-clipped")) or scene in ("nested", "mirror", "mirror-visible") else "moving-nearer" if scene.startswith("create-") else "nearer"
            valid = witness.get("depth_samples") == 81 and witness.get("depth_observations", 0) > 0 and witness.get("depth_target") == target
            valid = valid and witness.get("depth_expectation") == expectation and all(math.isfinite(v) and v > 0 for v in (distance, backdrop, closest)) and abs(backdrop - expected) <= 0.5
            valid = valid and (abs(distance - backdrop) <= max(0.1, backdrop * 0.02) if expectation == "same" else closest < backdrop - 0.5 if expectation == "moving-nearer" else distance < backdrop - 1)
            if not valid:
                raise RuntimeError("real-pack topology depth did not match the geometric control")
            if witness.get("is_reference"):
                if witness.get("stability_mean_error", float("inf")) > 1.0 or witness.get("mean_brightness", 0) <= 2.0:
                    raise RuntimeError("same-pack background reference was unstable or black")
            else:
                error, fraction = witness.get("mean_absolute_error", -1), witness.get("changed_fraction", -1)
                if not math.isfinite(error) or not math.isfinite(fraction) or error < 0 or not 0 <= fraction <= 1:
                    raise RuntimeError("invalid same-pack pixel comparison evidence")
                expected_match = scene.endswith("-clipped") or scene in ("nested", "mirror")
                if witness.get("expected_match") != expected_match:
                    raise RuntimeError("wrong same-pack comparison expectation")
                if expected_match and (witness.get("actual_brightness", 0) <= 2.0 or error > 4.0 or fraction > 0.01):
                    raise RuntimeError("clipped real-pack view did not match its unobstructed background")
                # Each counted pixel has summed RGB delta >12. Do not dilute that
                # local contrast a second time by averaging over unchanged backdrop.
                if not expected_match and (witness.get("actual_brightness", 0) <= 2.0
                                           or fraction < 0.10 or error + 1e-9 < fraction * 13 / 3):
                    raise RuntimeError("visible real-pack geometry did not differ from its background")
        if renderer in ACTIVE_RENDERERS and not fixture.get("diagnostic_fixture", True) and scene == "crossing":
            witness = check.get("reference_witness", {})
            background, visible, actual = (witness.get(name, []) for name in ("background_color", "visible_color", "actual_color"))
            if not witness.get("crossing_palette") or any(len(color) != 3 for color in (background, visible, actual)):
                raise RuntimeError("missing real-pack destination palette crossing evidence")
            if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0 for color in (background, visible, actual) for value in color):
                raise RuntimeError("invalid real-pack crossing colors")
            distance, expected = witness.get("native_view_distance", -1), witness.get("expected_native_distance", -1)
            camera = witness.get("native_camera", [])
            if witness.get("depth_samples") != 81 or witness.get("depth_observations", 0) <= 0 or len(camera) != 3 or not all(math.isfinite(v) for v in (*camera, distance, expected)) or expected <= 0 or abs(expected - (camera[2] + 3)) > 0.001 or abs(distance - expected) > max(0.1, expected * 0.02):
                raise RuntimeError("native crossing depth did not prove the destination wall")
            def chromatic_distance(left, right):
                return sum((a / sum(left) - b / sum(right)) ** 2 for a, b in zip(left, right)) if sum(left) > 0 and sum(right) > 0 else float("inf")
            if sum(actual) / 3 <= 2 or actual[1] / (actual[2] + 1) < 0.8 * background[1] / (background[2] + 1) or chromatic_distance(actual, background) >= 0.75 * chromatic_distance(actual, visible):
                raise RuntimeError("crossing pixels did not match the destination's reference palette")
        if scene.startswith(("entity-", "particle-")):
            validate_target_shader_path(check.get("shader_path", {}), "entity" if scene.startswith("entity-") else "particle", True)
            witness = check.get("straddling_witness", {})
            excluded, retained = witness.get("excluded", {}), witness.get("retained", {})
            if not witness.get("cpu_origin_retained") or not witness.get("cpu", {}).get("centers"):
                raise RuntimeError("shader geometry witness was not accepted by the actual CPU gate")
            if scene.endswith("-clipped"):
                if excluded.get("green_fraction", 0) <= 0.80 or excluded.get("red_fraction", 1) >= 0.01 or retained.get("red_fraction", 0) <= 0.10:
                    raise RuntimeError("straddling geometry did not prove both clipped and retained fragments")
            elif excluded.get("red_fraction", 0) <= 0.10:
                raise RuntimeError("straddling geometry positive control was not visible")
            if scene.startswith("particle-") and witness.get("active_particles", 0) <= 0:
                raise RuntimeError("particle clipping witness had no live particles")
        if scene.startswith("create-") and render_mode != "debug" and check.get("source_motion_changed_pixels", 0) <= 20:
            raise RuntimeError("visible source Create motion was not observed in its isolated region")
        if scene.startswith("create-"):
            flywheel = check.get("flywheel", {})
            if not flywheel.get("contextAccessorsInstalled") or not flywheel.get("contextClearedAfterFrame"):
                raise RuntimeError("live Flywheel context accessors/restoration were not verified")
        if scene in ("create-visible", "create-nested") and (check.get("create_motion_changed_pixels", 0) <= 20 or not check.get("create_server_motion")):
            raise RuntimeError("destination Create rotor motion was not verified")
        if scene == "create-nested":
            flywheel = check.get("flywheel", {})
            deferred = check.get("renderer") in {"IrisPortalRenderer", "IrisCompatibilityPortalRenderer"}
            mode = "deferred-null-view" if deferred else "overlapping-live-context"
            if check.get("nested_context_verification") != mode:
                raise RuntimeError("Flywheel nested context evidence does not match the actual portal renderer")
            if deferred:
                witness = flywheel.get("viewContextWitness", {})
                view_deltas = check.get("nested_view_contexts_this_scene", {})
                views = view_deltas.get("sameRendererViewsVerified", 0)
                keys = ("sameRendererViewsVerified", "nullContextsVerified", "nonNullContextsVerified", "fallbackScopesVerified")
                if (witness.get("observedViews", 0) <= 0 or witness.get("openViews") != 0 or views <= 0
                        or view_deltas.get("nullContextsVerified") != views or view_deltas.get("nonNullContextsVerified") != 0
                        or view_deltas.get("fallbackScopesVerified") != views
                        or any(not isinstance(view_deltas.get(key), int) or view_deltas[key] < 0
                               or view_deltas[key] > witness.get(key, -1) for key in keys)):
                    raise RuntimeError("deferred same-renderer Flywheel context/scope restoration was not verified")
            elif (flywheel.get("nestedContextsRestored", 0) <= 0
                  or check.get("nested_contexts_restored_this_scene", 0) <= 0):
                raise RuntimeError("same-dimension nested Flywheel context was not restored")
        image = RESULT_DIR / check["screenshot"]
        if not image.is_file() or image.stat().st_size == 0:
            raise RuntimeError(f"missing screenshot: {image.name}")


def validate_release_jar() -> None:
    version = re.search(r"^mod_version\s*=\s*(.+)$", (ROOT / "gradle.properties").read_text(), re.M)
    if not version:
        raise RuntimeError("mod_version is missing")
    jar = ROOT / "build" / "libs" / f"immersive_portals-{version[1].strip()}.jar"
    with zipfile.ZipFile(jar) as archive:
        forbidden = [name for name in archive.namelist()
                     if name.startswith("qouteall/imm_ptl/core/gametest/")
                     or name in {"imm_ptl_gametest.mixins.json", "imm_ptl_portal_clipping_test.mixins.json"}]
    if forbidden:
        raise RuntimeError("development test classes leaked into release jar: " + ", ".join(forbidden))


def run_core() -> None:
    print("[verify] core: build + JUnit + GameTests", flush=True)
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"], cwd=ROOT, check=True)
    subprocess.run(gradle_cmd("coreCheck"), cwd=ROOT, check=True)
    validate_gametest_log()
    validate_release_jar()


def run_staff() -> None:
    """Exercise the actual optional Aeronautics staff against native Sable physics."""
    mods = ROOT / "runs" / "gameTestServer" / "mods"
    sources = [ROOT.parent / "Simulated-Project" / component / "neoforge" / "build" / "libs"
               / f"{component}-neoforge-1.21.1-1.3.1.jar"
               for component in ("simulated", "aeronautics", "offroad")]
    for source in sources:
        if not source.is_file():
            raise RuntimeError(f"staff verification requires local Aeronautics artifact: {source}")
    mods.mkdir(parents=True, exist_ok=True)
    created = []
    try:
        for source in sources:
            target = mods / source.name
            if target.exists():
                raise RuntimeError(f"staff verification will not overwrite existing mod: {target}")
            shutil.copy2(source, target)
            created.append(target)
        run_core()
        game_test_log = GAMETEST_LOG.read_text(encoding="utf-8", errors="replace")
        if "Running test batch 'staff:0' (1 tests)" not in game_test_log:
            raise RuntimeError("Creative Physics Staff regression did not run")
    finally:
        for target in created:
            target.unlink(missing_ok=True)


def choose_tcp_udp_port(attempts: int = 64) -> int:
    """Choose one localhost port number that both Minecraft TCP and Sable UDP can bind."""
    for _ in range(attempts):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as tcp:
            tcp.bind(("127.0.0.1", 0))
            port = tcp.getsockname()[1]
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                    udp.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise RuntimeError("could not find a localhost port free for both TCP and UDP")


def prepare_e2e() -> dict[str, str]:
    # Resolve every disposable target before deleting; never follow a redirected
    # run directory outside this checkout, and never hide cleanup errors.
    for target in (RESULT_DIR, SERVER_DIR / "world", SERVER_DIR / "logs", CLIENT_DIR / "logs", CLIENT_DIR / ".mixin.out"):
        resolved = target.resolve()
        if resolved == ROOT.resolve() or not resolved.is_relative_to(ROOT.resolve()):
            raise RuntimeError(f"unsafe E2E cleanup target: {resolved}")
        if target.exists():
            shutil.rmtree(target)
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    CLIENT_DIR.mkdir(parents=True, exist_ok=True)

    port = choose_tcp_udp_port()

    # Deterministic low-cost graphics; real rendering and Sodium remain enabled.
    (CLIENT_DIR / "options.txt").write_text(
        "onboardAccessibility:false\nrenderDistance:3\nsimulationDistance:3\n"
        "overrideWidth:854\noverrideHeight:480\nmaxFps:60\nenableVsync:false\npauseOnLostFocus:false\n"
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


def validate_results(client_exit: int, smoke: bool = False) -> None:
    check_failures()
    if client_exit != 0:
        raise RuntimeError(f"graphical client exited with status {client_exit}")
    required = ("server-pass", "client-pass", "visual-pass") if smoke else (
        "server-pass", "client-pass", "client-source", "client-destination", "client-return", "client-recross", "client-dismount")
    for name in required:
        marker = RESULT_DIR / f"{name}.txt"
        if not marker.exists() or not marker.read_text(encoding="utf-8").strip():
            raise RuntimeError(f"missing or empty authoritative result: {name}")


def client_progress_token() -> tuple:
    """Only client output or authoritative scene/frame evidence counts as progress."""
    paths = {CLIENT_LOG, CLIENT_DIR / "logs/latest.log", RESULT_DIR / "runtime-evidence.json",
             RESULT_DIR / "scene-request.txt", RESULT_DIR / "scene-ready.txt", RESULT_DIR / "visual-pass.txt"}
    paths.update(RESULT_DIR.glob("client-*.txt"))
    paths.update(RESULT_DIR.glob("*.png"))
    result = []
    for path in sorted(paths):
        try:
            stat = path.stat()
            result.append((str(path), stat.st_mtime_ns, stat.st_size))
        except FileNotFoundError:
            continue
    return tuple(result)


def process_snapshot() -> dict[int, dict]:
    """Read process identity/ancestry without collecting arbitrary command-line secrets."""
    if os.name == "nt":
        command = ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                   "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,ExecutablePath | ConvertTo-Json -Compress"]
        output = subprocess.run(command, capture_output=True, text=True, timeout=5, check=True).stdout
        records = json.loads(output or "[]")
        if isinstance(records, dict):
            records = [records]
        return {int(record["ProcessId"]): {"ppid": int(record["ParentProcessId"]),
                "name": record["Name"], "executable": record.get("ExecutablePath")} for record in records}
    output = subprocess.run(["ps", "-eo", "pid=,ppid=,comm="], capture_output=True,
                            text=True, timeout=5, check=True).stdout
    result = {}
    for line in output.splitlines():
        fields = line.split(None, 2)
        if len(fields) == 3:
            result[int(fields[0])] = {"ppid": int(fields[1]), "name": Path(fields[2]).name, "executable": None}
    return result


def process_cwd(pid: int) -> Path | None:
    try:
        return Path(f"/proc/{pid}/cwd").resolve(strict=True)
    except OSError:
        return None


def project_java_processes(roots: set[int], processes: dict[int, dict]) -> list[dict]:
    descendants = set(roots)
    while True:
        added = {pid for pid, info in processes.items() if info["ppid"] in descendants}
        if added.issubset(descendants):
            break
        descendants.update(added)
    selected = []
    for pid, info in processes.items():
        if info["name"].lower() not in {"java", "java.exe", "javaw.exe"}:
            continue
        cwd = process_cwd(pid)
        in_checkout = cwd is not None and cwd.is_relative_to(ROOT.resolve())
        if pid not in descendants and not in_checkout:
            continue
        # Prioritize the actual game JVM over Gradle wrapper/daemon JVMs.
        game = cwd is not None and cwd in {CLIENT_DIR.resolve(), SERVER_DIR.resolve()}
        selected.append({"pid": pid, "ppid": info["ppid"], "name": info["name"],
                         "scope": "descendant" if pid in descendants else "checkout-cwd",
                         "game": game, "executable": info.get("executable")})
    return sorted(selected, key=lambda info: (not info["game"], info["pid"]))[:DIAGNOSTIC_MAX_JVMS]


def jcmd_for_process(process: dict) -> Path | None:
    candidates = []
    executable = process.get("executable")
    if not executable:
        try:
            executable = str(Path(f"/proc/{process['pid']}/exe").resolve(strict=True))
        except OSError:
            pass
    name = "jcmd.exe" if os.name == "nt" else "jcmd"
    if executable:
        candidates.append(Path(executable).with_name(name))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / name)
    found = shutil.which(name)
    if found:
        candidates.append(Path(found))
    return next((path for path in candidates if path.is_file()), None)


def collect_thread_diagnostics(server, client, reason: str) -> None:
    """Bounded read-only thread dumps, before process-tree cleanup. Never changes JVM flags."""
    roots = {pid for proc in (client, server) if type(pid := getattr(proc, "pid", None)) is int and pid > 1}
    directory = RESULT_DIR / "thread-diagnostics" / reason
    try:
        directory.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        print(f"[verify] Cannot prepare thread diagnostic directory: {exc}", file=sys.stderr)
        return
    report = {"reason": reason, "utc": datetime.now(timezone.utc).isoformat(), "root_pids": sorted(roots), "jvms": []}
    try:
        if not roots:
            report["error"] = "No verified harness process roots; no JVM attach attempted"
            return
        processes = project_java_processes(roots, process_snapshot())
        if not processes:
            report["error"] = "No scoped Java processes remained for thread diagnostics"
        for process in processes:
            entry = {key: process[key] for key in ("pid", "ppid", "name", "scope", "game")}
            report["jvms"].append(entry)
            jcmd = jcmd_for_process(process)
            if jcmd is None:
                entry["error"] = "jcmd is unavailable for this scoped JVM"
                continue
            path = directory / f"jvm-{process['pid']}-threads.txt"
            entry["file"] = path.name
            try:
                with path.open("wb") as output:
                    completed = subprocess.run([str(jcmd), str(process["pid"]), "Thread.print", "-l"],
                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=DIAGNOSTIC_COMMAND_SECONDS, check=False)
                entry["returncode"] = completed.returncode
            except (OSError, subprocess.TimeoutExpired) as exc:
                entry["error"] = str(exc)
            finally:
                if path.is_file() and path.stat().st_size > DIAGNOSTIC_MAX_BYTES:
                    with path.open("rb") as output:
                        head = output.read(DIAGNOSTIC_MAX_BYTES // 2)
                        marker = b"\n... bounded thread dump: middle omitted ...\n"
                        output.seek(-(DIAGNOSTIC_MAX_BYTES // 2 - len(marker)), os.SEEK_END)
                        tail_bytes = output.read()
                    path.write_bytes(head + marker + tail_bytes)
                    entry["truncated"] = True
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        report["error"] = str(exc)
    finally:
        try:
            (directory / "diagnostics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        except OSError as exc:
            print(f"[verify] Cannot save thread diagnostics: {exc}", file=sys.stderr)


def visual_run_deadline() -> float:
    budget = float(VISUAL_RUN_SECONDS)
    job_started = os.environ.get("IP_VERIFY_JOB_START_EPOCH")
    if job_started:
        try:
            elapsed = max(0.0, time.time() - float(job_started))
            # Leave two minutes for artifact upload even when setup consumed most of the job.
            budget = min(budget, max(0.0, VISUAL_JOB_SECONDS - elapsed - 120))
        except ValueError as exc:
            raise RuntimeError("invalid IP_VERIFY_JOB_START_EPOCH") from exc
    return time.monotonic() + budget


def wait_for_client(server: subprocess.Popen, client: subprocess.Popen, timeout: float = 300, smoke: bool = False) -> None:
    started = time.monotonic()
    deadline = started + timeout
    last_progress = started
    progress = client_progress_token()
    diagnosed_stall = False
    while True:
        now = time.monotonic()
        if now >= deadline:
            collect_thread_diagnostics(server, client, "timeout")
            raise TimeoutError(f"graphical client timed out after {timeout:.1f} seconds; thread diagnostics preserved")
        check_failures()
        if server.poll() is not None:
            raise RuntimeError(f"dedicated server exited during E2E (exit={server.returncode})")
        if client.poll() is not None:
            validate_results(client.returncode, smoke=smoke)
            return
        current = client_progress_token()
        if current != progress:
            progress, last_progress = current, now
        if not diagnosed_stall and now - last_progress >= 180:
            print("[verify] No graphical-client progress for 180s; preserving scoped JVM thread dumps", flush=True)
            collect_thread_diagnostics(server, client, "no-progress-180s")
            diagnosed_stall = True
        time.sleep(0.25)


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


def validate_metrics(samples: int) -> None:
    import math
    for side, budget in (("server", 100.0), ("client", 250.0)):
        values = json.loads((RESULT_DIR / f"{side}-metrics.json").read_text(encoding="utf-8"))
        if values["samples"] < samples:
            raise RuntimeError(f"{side}: too few runtime timing samples")
        value = values["p95_ms"]
        if not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= budget:
            raise RuntimeError(f"{side}: p95 {value} ms exceeds {budget} ms budget or is invalid")
        print(f"[verify] {side}: p95={value:.2f} ms, heap={values['heap_used_bytes']} bytes", flush=True)


def run_e2e(smoke: bool = False, renderer: str = "sodium", sable: bool = True, samples: int = 200,
            negative_control: str = "none", flywheel_backend: str = "default", render_mode: str = "normal",
            disable_copy_image: bool = False, shaderpack_file: Path | None = None, shaderpack_profile: str | None = None,
            gl_context: str = "default", shadow_fixture: bool = False) -> None:
    print(f"[verify] {'visual ' + renderer if smoke else 'e2e'}: dedicated server + automated graphical client", flush=True)
    run_deadline = visual_run_deadline() if smoke else None
    env = prepare_e2e()
    if smoke:
        env.update(IP_SABLE_E2E="false", IP_PORTAL_SMOKE="true", IP_SHADOW_SMOKE=str(shadow_fixture).lower(), IP_SMOKE_RENDERER=renderer,
                   IP_SMOKE_SABLE=str(sable).lower(), IP_SMOKE_SAMPLES=str(samples),
                   IP_SMOKE_NEGATIVE_CONTROL=negative_control, IP_SMOKE_FLYWHEEL_BACKEND=flywheel_backend,
                   IP_SMOKE_RENDER_MODE=render_mode, IP_SMOKE_DISABLE_COPY_IMAGE=str(disable_copy_image).lower(), IP_SMOKE_GL_CONTEXT=gl_context)
        if gl_context == "no-copy-image":
            env.update(MESA_GL_VERSION_OVERRIDE="3.3", MESA_EXTENSION_OVERRIDE="-GL_ARB_copy_image")
        fixture = stage_shader_fixture(renderer, negative_control, shaderpack_file, shaderpack_profile, shadow_fixture)
        env.update(IP_SMOKE_SHADERPACK_NAME=fixture["name"],
                   IP_SMOKE_DIAGNOSTIC_FIXTURE=str(fixture["diagnostic_fixture"]).lower())
        (CLIENT_DIR / "config" / "flywheel-client.toml").write_text(
            f'backend="{"DEFAULT" if flywheel_backend == "default" else "flywheel:" + flywheel_backend}"\n', encoding="utf-8")
    else:
        env["IP_PORTAL_SMOKE"] = "false"
    properties = [f"-PverificationRenderer={renderer}", f"-PverificationSable={str(sable).lower()}"]
    server_run = "PortalSmokeServer" if smoke else "SableE2EServer"
    client_run = "PortalSmokeClient" if smoke else "SableE2EClient"
    invalidate_generated_run_classpath(server_run)
    invalidate_generated_run_classpath(client_run)
    staged_renderer_mods = stage_renderer_mods(renderer, properties) if smoke else []
    if smoke:
        (RESULT_DIR / "staged-mods.json").write_text(json.dumps([
            {"file": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in staged_renderer_mods], indent=2), encoding="utf-8")
    client_properties = properties
    if staged_renderer_mods:
        client_properties = [*properties, "-PverificationRendererStaged=true"]
        # The resolution pass above deliberately included renderer jars so they
        # could be located. Rebuild the actual launch classpath without them.
        invalidate_generated_run_classpath(client_run)

    server_log = SERVER_LOG.open("wb")
    creation = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    server = None
    try:
        server = subprocess.Popen(
            gradle_cmd("run" + server_run, *properties),
            cwd=ROOT,
            env=env,
            stdin=subprocess.PIPE,
            stdout=server_log,
            stderr=subprocess.STDOUT,
            **creation,
        )
        server_timeout = min(720, max(0, run_deadline - time.monotonic() - VISUAL_CLEANUP_SECONDS)) if smoke else 720
        wait_for_server(server, timeout=server_timeout)
        print("[verify] e2e server ready; launching automated client", flush=True)

        client_command = gradle_cmd("run" + client_run, *client_properties)
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            if shutil.which("xvfb-run") is None:
                raise RuntimeError("xvfb-run is required for headless Linux graphical E2E")
            client_command = ["xvfb-run", "-a", *client_command]

        client, client_log = launch_graphical_client(client_command, env, creation)
        try:
            timeout = (1800 if shadow_fixture else visual_timeout(renderer, samples, render_mode, fixture["diagnostic_fixture"])) if smoke else 300
            if smoke:
                timeout = min(timeout, max(0, run_deadline - time.monotonic() - VISUAL_CLEANUP_SECONDS))
                print(f"[verify] Graphical client budget: {timeout:.0f}s; bounded by 120-minute job with diagnostics/cleanup reserve", flush=True)
            wait_for_client(server, client, timeout=timeout, smoke=smoke)
        finally:
            stop_process_tree(client)
            if client_log is not None:
                client_log.close()

        validate_log_health()
        if smoke:
            validate_shader_evidence(renderer, render_mode, gl_context)
            validate_metrics(samples)
        print("[verify] " + (RESULT_DIR / "server-pass.txt").read_text(encoding="utf-8").strip(), flush=True)
        print("[verify] " + (RESULT_DIR / "client-pass.txt").read_text(encoding="utf-8").strip(), flush=True)
    except Exception:
        print("\n[verify] --- server log tail ---", file=sys.stderr)
        print(tail(SERVER_LOG), file=sys.stderr)
        print("\n[verify] --- client log tail ---", file=sys.stderr)
        print(tail(CLIENT_LOG), file=sys.stderr)
        raise
    finally:
        if server is not None:
            if server.stdin:
                try:
                    server.stdin.close()
                except OSError:
                    pass
            stop_process_tree(server)
        server_log.close()
        cleanup_staged_renderer_mods(staged_renderer_mods)


def run_visual(renderer: str, sable: bool, samples: int, negative_control: str = "none",
               flywheel_backend: str = "default", render_mode: str = "normal", disable_copy_image: bool = False,
               shaderpack_file: Path | None = None, shaderpack_profile: str | None = None, gl_context: str = "default", shadow_fixture: bool = False) -> None:
    # Keep each matrix member's evidence, including failed runs.
    global RESULT_DIR, SERVER_LOG, CLIENT_LOG
    previous = RESULT_DIR, SERVER_LOG, CLIENT_LOG
    RESULT_DIR = ROOT / "build" / f"portal-visual-{renderer}-{'sable' if sable else 'no-sable'}"
    suffix = f"-{render_mode}-{flywheel_backend}"
    if shadow_fixture:
        suffix += "-shadow"
    if gl_context != "default":
        suffix += "-gl33-no-copy-image"
    if disable_copy_image:
        suffix += "-no-copy-image"
    if negative_control != "none":
        suffix += "-negative-" + negative_control
    if shaderpack_file is not None:
        suffix += "-pack-" + re.sub(r"[^a-zA-Z0-9_-]", "_", shaderpack_file.stem)
    RESULT_DIR = RESULT_DIR.with_name(RESULT_DIR.name + suffix)
    SERVER_LOG, CLIENT_LOG = RESULT_DIR / "server.log", RESULT_DIR / "client.log"
    try:
        try:
            run_e2e(smoke=True, renderer=renderer, sable=sable, samples=samples,
                    negative_control=negative_control, flywheel_backend=flywheel_backend,
                    render_mode=render_mode, disable_copy_image=disable_copy_image, shaderpack_file=shaderpack_file, shaderpack_profile=shaderpack_profile, gl_context=gl_context, shadow_fixture=shadow_fixture)
        except RuntimeError:
            failure = RESULT_DIR / "client-fail.txt"
            expected = {"pack-disabled": "SHADER_FIXTURE_NOT_ACTIVE", "clipping-disabled": "PORTAL_PIXELS_MISMATCH: before-reload/solid-clipped",
                        "entity-clipping-disabled": "PORTAL_PIXELS_MISMATCH: before-reload/entity-clipped",
                        "particle-clipping-disabled": "PORTAL_PIXELS_MISMATCH: before-reload/particle-clipped",
                        "shadow-clipping-enabled": "SHADOW_PIXELS_MISMATCH: before-reload/caster"}
            if negative_control == "none" or not failure.is_file() or expected[negative_control] not in failure.read_text():
                raise
            if negative_control == "shadow-clipping-enabled":
                from shadow_evidence import validate_shadow_negative
                validate_shadow_negative(RESULT_DIR)
            if negative_control in ("entity-clipping-disabled", "particle-clipping-disabled"):
                target = negative_control.split("-", 1)[0]
                report = json.loads((RESULT_DIR / "runtime-evidence.json").read_text())
                control = report.get("shader_control", {})
                if control.get("observation") != f"before-reload:{target}-clipped":
                    raise RuntimeError("targeted negative control did not observe its exact clipping scene")
                validate_target_shader_path(control, target, False)
            (RESULT_DIR / "negative-control-pass.txt").write_text(
                f"Expected {negative_control} regression was detected: {failure.read_text()}\n", encoding="utf-8")
        else:
            if negative_control != "none":
                raise RuntimeError("negative control unexpectedly passed the shader lane")
    finally:
        RESULT_DIR, SERVER_LOG, CLIENT_LOG = previous


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the canonical Immersive Portals verification suite")
    parser.add_argument("mode", choices=("core", "e2e", "visual", "full", "matrix", "staff"), nargs="?", default="full")
    parser.add_argument("--renderer", choices=RENDERERS, default="sodium")
    parser.add_argument("--negative-control", choices=("none", "pack-disabled", "clipping-disabled", "entity-clipping-disabled", "particle-clipping-disabled", "shadow-clipping-enabled"), default="none")
    parser.add_argument("--flywheel-backend", choices=("default", "off", "instancing", "indirect"), default="default")
    parser.add_argument("--render-mode", choices=("normal", "compatibility", "debug"), default="normal")
    parser.add_argument("--gl-context", choices=("default", "no-copy-image"), default="default")
    parser.add_argument("--disable-copy-image", action="store_true")
    parser.add_argument("--shaderpack-profile", help="Exact pack profile; otherwise auto-select the lowest known pack-provided preset")
    parser.add_argument("--shaderpack-file", type=Path, help="Additionally run a user-supplied real-pack ZIP through active shader smoke checks")
    parser.add_argument("--no-sable", action="store_true")
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--shadow-fixture", action="store_true", help="Run owned actual shadow-map/receiver acceptance instead of ordinary smoke scenes")
    args = parser.parse_args()
    if args.shadow_fixture and (args.mode != "visual" or args.renderer not in ACTIVE_RENDERERS or args.shaderpack_file is not None or args.render_mode != "normal" or args.negative_control not in ("none", "shadow-clipping-enabled")):
        parser.error("--shadow-fixture requires normal active-renderer visual mode and its own optional shadow negative")
    if args.negative_control == "shadow-clipping-enabled" and not args.shadow_fixture:
        parser.error("shadow negative requires --shadow-fixture")
    if args.negative_control != "none" and (args.mode != "visual" or args.renderer not in ACTIVE_RENDERERS):
        parser.error("negative controls require visual --renderer iris-active or neoculus-active")
    if args.shaderpack_file is not None and (args.mode != "visual" or args.renderer not in ACTIVE_RENDERERS or args.negative_control != "none"):
        parser.error("--shaderpack-file requires visual with an active renderer and no negative control")
    if args.shaderpack_profile is not None and args.shaderpack_file is None:
        parser.error("--shaderpack-profile requires --shaderpack-file")
    if args.gl_context == "no-copy-image" and (args.renderer not in ACTIVE_RENDERERS or args.disable_copy_image):
        parser.error("GL3.3 no-copy-image context requires an active shader renderer without --disable-copy-image")
    if args.disable_copy_image and args.renderer not in ACTIVE_RENDERERS:
        parser.error("--disable-copy-image requires an active shader renderer")
    if not 200 <= args.samples <= 12000:
        parser.error("--samples must be between 200 and 12000")

    started = time.monotonic()
    try:
        with checkout_lock():
            stages = []
            try:
                actions = [("core", run_core), ("e2e", run_e2e),
                           ("visual", lambda: run_visual(args.renderer, not args.no_sable, args.samples, args.negative_control,
                                                         args.flywheel_backend, args.render_mode, args.disable_copy_image, args.shaderpack_file, args.shaderpack_profile, args.gl_context, args.shadow_fixture))]
                if args.mode == "matrix":
                    actions = [(f"visual-{renderer}-{'sable' if sable else 'no-sable'}",
                                lambda r=renderer, s=sable: run_visual(r, s, args.samples))
                               for renderer, sable in MATRIX]
                else:
                    actions = [(name, action) for name, action in actions if args.mode in (name, "full")]
                    if args.mode == "staff":
                        actions = [("staff", run_staff)]
                for name, action in actions:
                    stage_started = time.monotonic()
                    stage = {"name": name, "status": "failed"}
                    stages.append(stage)
                    try:
                        action()
                        stage["status"] = "passed"
                    except Exception as exc:
                        stage["error"] = str(exc)
                        if args.mode != "matrix":
                            raise
                    finally:
                        stage["seconds"] = round(time.monotonic() - stage_started, 2)
                failed = [stage["name"] for stage in stages if stage["status"] != "passed"]
                if failed:
                    raise RuntimeError("failed matrix configurations: " + ", ".join(failed))
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
