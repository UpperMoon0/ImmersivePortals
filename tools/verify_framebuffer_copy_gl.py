#!/usr/bin/env python3
"""Exercise the production IPIrisHelper against a real surfaceless EGL driver.

Requires a built Minecraft/NeoForge jar, cached LWJGL 3.3.3 dependencies (including
Linux natives), and org.lwjgl:lwjgl-egl:3.3.3. This is not Minecraft client QA.
No jars are downloaded and no production helper or OpenGL calls are mocked.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    parser.add_argument("--egl-jar", type=Path, required=True)
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--report", type=Path, help="Save the driver and per-case results")
    parser.add_argument("--clipping-classpath", help="Optional built main classes plus runtime dependencies; also check real FrontClipping state")
    args = parser.parse_args()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java 21 with the jdk.compiler module is required")
    for path in (args.minecraft_jar, args.egl_jar):
        if not path.is_file():
            parser.error(f"Required dependency does not exist: {path}")
    jars = sorted((args.gradle_home / "caches/modules-2/files-2.1/org.lwjgl").glob("*/3.3.3/*/*.jar"))
    if not any(path.name == "lwjgl-opengl-3.3.3.jar" for path in jars):
        parser.error("LWJGL 3.3.3 is not cached; run the project's normal dependency/build tasks first")
    classpath = os.pathsep.join(str(path.resolve()) for path in jars + [args.minecraft_jar, args.egl_jar])
    helper_dir = args.repo / "src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility"
    harness = Path(__file__).resolve().parent / "gl/FramebufferCopyEglCheck.java"
    with tempfile.TemporaryDirectory(prefix="immptl-framebuffer-gl-") as temporary:
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-cp", classpath,
                        "-d", temporary, str(harness), str(helper_dir / "IPIrisHelper.java"),
                        str(helper_dir / "FramebufferCopyPlan.java")], check=True)
        env = dict(os.environ)
        env.setdefault("MESA_SHADER_CACHE_DIR", str(Path(temporary) / "mesa-cache"))
        env.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        runtime_classpath = classpath + os.pathsep + temporary
        probe_arguments = []
        if args.clipping_classpath:
            runtime_classpath += os.pathsep + args.clipping_classpath
            probe_arguments.append("--clipping-state")
        result = subprocess.run([java, "-cp", runtime_classpath,
                                 "FramebufferCopyEglCheck", *probe_arguments], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end="")
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(result.stdout)
        result.check_returncode()


if __name__ == "__main__":
    main()
