#!/usr/bin/env python3
"""Exercise actual ShaderCodeTransformation YAML on a real EGL crumbling draw.

Requires a normal built project runtime classpath (Minecraft compiled jar, main
classes, Cloth Config and LWJGL with EGL/Linux natives). No stubs or downloads.
This probes generated GLSL; exact-head client runs verify mixin/scoped uploads.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True,
                        help="Text file containing the platform-separated built runtime classpath, including lwjgl-egl")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        parser.error("Java 21 with jdk.compiler is required")
    classpath = args.classpath_file.read_text().strip()
    with tempfile.TemporaryDirectory(prefix="immptl-crumbling-gl-") as temporary:
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-sourcepath", "",
                        "-cp", classpath, "-d", temporary, str(root / "tools/gl/CrumblingClippingEglCheck.java")], check=True)
        env = dict(os.environ)
        env.setdefault("MESA_SHADER_CACHE_DIR", str(Path(temporary) / "mesa-cache"))
        result = subprocess.run([java, "-cp", temporary + os.pathsep + classpath, "CrumblingClippingEglCheck",
                                 str(root / "src/main/resources/assets/immersive_portals/shaders/shader_transformation.yaml")],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end="")
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(result.stdout)
        result.check_returncode()


if __name__ == "__main__":
    main()
