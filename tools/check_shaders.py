"""Compiles the mod's shaders with glslangValidator to catch GLSL errors without launching the game.

Resolves #include <namespace:path> the way Minecraft's shader loader does, from the mod's assets, the
pinned Sodium jar and (optionally) the Minecraft client jar, and adds the defines Sodium's terrain pipelines
use. Each terrain shader is checked with and without ALPHA_CUTOUT; the water and cloud shaders, which include
Minecraft's OIT code and so need the Minecraft jar, are also checked in each OIT stage.

Usage: python tools/check_shaders.py <sodium jar> [<minecraft client jar>]
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


# Minecraft 26.3's OIT pipelines (RenderPipelines.OIT_*_SNIPPET): shared defines, then one set per stage.
OIT_DEFINES = ["OIT", "OIT_WAVELET_RANK 2", "OIT_COEFF_COUNT 8", "OIT_COEFF_ATTACHMENT_COUNT 2",
               "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE"]
OIT_STAGES = [["OIT_ALPHA_ONLY", "OIT_DEPTH_BOUNDS"], ["OIT_ALPHA_ONLY", "OIT_TRANSMITTANCE"], ["OIT_ACCUMULATE"]]


def loader(*jar_paths):
    jars = [zipfile.ZipFile(p) for p in jar_paths]

    def read(namespace, path):
        for local in (ASSETS / namespace / "shaders" / path, ASSETS / namespace / "shaders" / "include" / path):
            if local.exists():
                return local.read_text()
        for jar in jars:
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


def main(sodium_jar, minecraft_jar=None):
    read = loader(*[j for j in (sodium_jar, minecraft_jar) if j])
    failures = 0
    for shader in sorted((ASSETS / "bedrockvisuals/shaders").rglob("*.[vf]sh")):
        stage = "vert" if shader.suffix == ".vsh" else "frag"
        terrain = shader.stem == "terrain"
        variants = [TERRAIN_DEFINES, TERRAIN_DEFINES + ["ALPHA_CUTOUT 0.5"]] if terrain else [[]]
        if shader.parent.name == "entity":
            if not minecraft_jar:
                print(f"skip  {shader.relative_to(ASSETS)} (needs the Minecraft client jar)")
                continue
            # Minecraft's entity pipelines (RenderPipelines) switch the one shader by these defines.
            variants = [[], ["ALPHA_CUTOUT 0.1"], ["EMISSIVE"], ["NO_OVERLAY", "NO_CARDINAL_LIGHTING"],
                        ["PER_FACE_LIGHTING"], ["GLINT", "APPLY_TEXTURE_MATRIX"], ["DISSOLVE", "ALPHA_CUTOUT 0.1"],
                        ["EMISSIVE", "NO_OVERLAY", "ALPHA_CUTOUT 0.1"]] \
                + [OIT_DEFINES + s for s in OIT_STAGES] + [OIT_DEFINES + ["OIT_ADDITIVE"] + OIT_STAGES[2]]
        elif shader.parent.name in ("water", "clouds"):
            if not minecraft_jar:
                print(f"skip  {shader.relative_to(ASSETS)} (needs the Minecraft client jar)")
                continue
            base = TERRAIN_DEFINES if shader.parent.name == "water" else []
            # Java's clouds draw without OIT too, when it's off.
            variants = [base + OIT_DEFINES + s for s in OIT_STAGES] + ([[]] if shader.parent.name == "clouds" else [])
        for defines in variants:
            source = with_defines(expand(shader.read_text(), read, set()), defines)
            if stage == "vert":
                # Minecraft's shader loader provides Vulkan's gl_VertexIndex under OpenGL GLSL (vanilla's
                # clouds.vsh uses it); glslang's GLSL front end calls it gl_VertexID.
                source = source.replace("gl_VertexIndex", "gl_VertexID")
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
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    main(*sys.argv[1:])
