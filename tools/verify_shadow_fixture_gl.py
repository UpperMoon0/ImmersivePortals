#!/usr/bin/env python3
"""Verify owned shadow projection/live-bit sentinel on real surfaceless EGL.

Only compatibility attribute/output bindings are adapted for standalone drawing.
This does not replace real Minecraft shadow-caster/receiver acceptance.
Dependencies are existing cached LWJGL3.3.3+Linux natives and lwjgl-egl3.3.3; none are downloaded.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--egl-jar', type=Path, required=True)
    parser.add_argument('--gradle-home', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    parser.add_argument('--gl-version', choices=('3.3', '4.5'), default='4.5')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java')
    if not java or not args.egl_jar.is_file():
        parser.error('Java21 with compiler module and an existing lwjgl-egl3.3.3 jar are required')
    jars = sorted((args.gradle_home / 'caches/modules-2/files-2.1/org.lwjgl').glob('*/3.3.3/*/*.jar'))
    if not any(path.name == 'lwjgl-opengl-3.3.3.jar' for path in jars):
        parser.error('LWJGL3.3.3 is not cached; use the normal build/dependency tasks first')
    repo = Path(__file__).resolve().parents[1]
    cp = os.pathsep.join(str(path.resolve()) for path in jars + [args.egl_jar])
    with tempfile.TemporaryDirectory(prefix='immptl-shadow-gl-') as temporary:
        subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', cp, '-d', temporary,
                        str(repo / 'tools/gl/ShadowFixtureEglCheck.java'),
                        str(repo / 'src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/PortalShadowOracle.java')], check=True)
        env = dict(os.environ)
        env.setdefault('MESA_SHADER_CACHE_DIR', str(Path(temporary) / 'mesa-cache'))
        env.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')
        result = subprocess.run([java, f'-Dip.test.glVersion={args.gl_version}', '-cp', cp + os.pathsep + temporary,
                                 'ShadowFixtureEglCheck', str(repo / 'tools/shaderpacks/ip-shadow-fixture-v1/shaders')],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end='')
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(result.stdout)
        result.check_returncode()


if __name__ == '__main__':
    main()
