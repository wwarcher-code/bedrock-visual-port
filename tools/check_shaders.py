"""Compiles the mod's shaders with glslangValidator to catch GLSL errors without launching the game.

Resolves #include <namespace:path> the way Minecraft's shader loader does, from the mod's assets and the
pinned Sodium jar, and adds the defines Sodium's terrain pipelines use. Each terrain shader is checked with
and without ALPHA_CUTOUT.

Usage: python tools/check_shaders.py <sodium jar>
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets"
INCLUDE = re.compile(r'^\s*#include\s*<([a-z0-9_]+):([^>]+)>', re.M)
TERRAIN_DEFINES = ["USE_VERTEX_COMPRESSION", "USE_FOG"]


def loader(sodium_jar):
    jar = zipfile.ZipFile(sodium_jar)

    def read(namespace, path):
        local = ASSETS / namespace / "shaders" / path
        if local.exists():
            return local.read_text()
        for candidate in (f"assets/{namespace}/shaders/{path}", f"assets/{namespace}/shaders/include/{path}"):
            try:
                return jar.read(candidate).decode()
            except KeyError:
                pass
        raise FileNotFoundError(f"{namespace}:{path}")
    return read


def expand(source, read, seen):
    def replace(match):
        key = match.group(0)
        if key in seen:
            return ""
        seen.add(key)
        return expand(read(match.group(1), match.group(2)), read, seen)
    return INCLUDE.sub(replace, source)


def with_defines(source, defines):
    lines = source.split("\n")
    # Defines go after #version and any #extension lines.
    insert = max(i for i, line in enumerate(lines) if line.startswith(("#version", "#extension"))) + 1
    return "\n".join(lines[:insert] + [f"#define {d}" for d in defines] + lines[insert:])


def main(sodium_jar):
    read = loader(sodium_jar)
    failures = 0
    for shader in sorted((ASSETS / "bedrockvisuals/shaders").rglob("*.[vf]sh")):
        stage = "vert" if shader.suffix == ".vsh" else "frag"
        terrain = shader.stem == "terrain"
        variants = [TERRAIN_DEFINES, TERRAIN_DEFINES + ["ALPHA_CUTOUT 0.5"]] if terrain else [[]]
        for defines in variants:
            source = with_defines(expand(shader.read_text(), read, set()), defines)
            with tempfile.NamedTemporaryFile("w", suffix="." + stage, delete=False) as tmp:
                tmp.write(source)
            result = subprocess.run(["glslangValidator", "-S", stage, tmp.name], capture_output=True, text=True)
            name = f"{shader.relative_to(ASSETS)} {' '.join(d.split()[0] for d in defines)}".strip()
            if result.returncode == 0:
                print(f"ok    {name}")
            else:
                failures += 1
                print(f"FAIL  {name}\n" + "\n".join("      " + l for l in result.stdout.strip().splitlines()))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
