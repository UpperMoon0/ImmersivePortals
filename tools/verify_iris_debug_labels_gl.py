#!/usr/bin/env python3
"""Reproduce the Iris depthless debug-label error and exercise its production guard.

Requires cached LWJGL 3.3.3 Linux natives, lwjgl-egl 3.3.3, Java 21 and Mesa EGL.
This is an isolated actual-driver test, not a Minecraft mixin startup test.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--egl-jar", required=True, type=Path)
    parser.add_argument("--gradle-home", type=Path,
                        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java or not args.egl_jar.is_file():
        parser.error("Java 21 and an existing lwjgl-egl jar are required")
    jars = sorted((args.gradle_home / "caches/modules-2/files-2.1/org.lwjgl").glob("*/3.3.3/*/*.jar"))
    classpath = os.pathsep.join(str(p.resolve()) for p in jars + [args.egl_jar])
    with tempfile.TemporaryDirectory(prefix="immptl-labels-gl-") as temporary:
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-cp", classpath,
                        "-d", temporary, str(root / "tools/gl/IrisDebugLabelsEglCheck.java"),
                        str(root / "src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisDebugLabelPolicy.java")],
                       check=True)
        env = dict(os.environ)
        env.setdefault("MESA_SHADER_CACHE_DIR", str(Path(temporary) / "mesa-cache"))
        result = subprocess.run([java, "-cp", classpath + os.pathsep + temporary, "IrisDebugLabelsEglCheck"],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end="")
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(result.stdout)
        result.check_returncode()


if __name__ == "__main__":
    main()
