"""Builds the Java-to-Bedrock block texture table the renderer uses to find PBR texture sets.

Both editions name blocks the same way (minecraft:red_wool), so each Java block's model textures are
paired, face by face, with the textures Bedrock's blocks.json gives the same block. A pairing only counts
when the two names share a word, since some blocks are modelled differently in the two editions; each Java
texture then takes the Bedrock texture it was paired with most often. Exact name matches win outright, and
entries in texture_map_overrides.json win over everything.

Usage (Windows):
    py tools\\gen_texture_map.py <minecraft-client.jar> <bedrock resource pack folder>

The output is src/main/resources/bedrockvisuals/texture_map.json; commit it. Unmatched Java textures are
listed at the end so they can be added to the overrides file by hand.
"""
import collections
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "src/main/resources/bedrockvisuals/texture_map.json"
OVERRIDES = ROOT / "tools/texture_map_overrides.json"
FACES = ("up", "down", "north", "south", "east", "west")


def words(name):
    return set(re.split(r"[_/]", name)) - {""}


def similarity(a, b):
    a, b = words(a), words(b)
    return len(a & b) / len(a | b)


def load_json(text):
    # Bedrock files allow // and /* */ comments.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    return json.loads(text)


class JavaModels:
    def __init__(self, jar):
        self.zip = zipfile.ZipFile(jar)
        self.cache = {}

    def read(self, path):
        try:
            return json.loads(self.zip.read(path))
        except KeyError:
            return None

    def blockstate_models(self, block):
        state = self.read(f"assets/minecraft/blockstates/{block}.json")
        if state is None:
            return []
        if "variants" in state:
            first = next(iter(state["variants"].values()))
            return [first[0]["model"] if isinstance(first, list) else first["model"]]
        models = []
        for part in state.get("multipart", []):
            apply = part["apply"]
            models.append(apply[0]["model"] if isinstance(apply, list) else apply["model"])
        return models

    def faces(self, model_id):
        """Maps each face direction to the texture name the model draws there."""
        textures, elements = {}, None
        name = model_id
        while name:
            name = name.removeprefix("minecraft:")
            model = self.read(f"assets/minecraft/models/{name}.json")
            if model is None:
                break
            for key, value in model.get("textures", {}).items():
                textures.setdefault(key, value)
            if elements is None and "elements" in model:
                elements = model["elements"]
            name = model.get("parent")

        def resolve(ref):
            for _ in range(10):
                if isinstance(ref, dict):
                    ref = ref.get("sprite", "")
                if not ref.startswith("#"):
                    return ref.removeprefix("minecraft:").removeprefix("block/")
                ref = textures.get(ref[1:], "")
            return None

        result = collections.defaultdict(set)
        for element in elements or []:
            for face, data in element.get("faces", {}).items():
                texture = resolve(data["texture"])
                if texture:
                    result[face].add(texture)
        return result


def bedrock_faces(block_entry, terrain):
    """Maps each face direction to Bedrock texture paths (relative to textures/blocks). An entry can list
    variants (wool colors, door halves); all are kept and the caller picks by name."""
    textures = block_entry.get("textures")
    if textures is None:
        return {}
    if isinstance(textures, str):
        textures = {face: textures for face in FACES}
    else:
        side = textures.get("side")
        textures = {face: textures.get(face, side) for face in FACES}

    result = {}
    for face, short_name in textures.items():
        entry = terrain.get(short_name) if short_name else None
        if entry is None:
            continue
        paths = entry["textures"]
        paths = paths if isinstance(paths, list) else [paths]
        paths = [p.get("path") if isinstance(p, dict) else p for p in paths]
        paths = [p.removeprefix("textures/blocks/") for p in paths if p and p.startswith("textures/blocks/")]
        if paths:
            result[face] = paths
    return result


def main(jar, pack):
    pack = pathlib.Path(pack)
    java = JavaModels(jar)
    blocks = load_json((pack / "blocks.json").read_text(encoding="utf-8"))
    terrain = load_json((pack / "textures/terrain_texture.json").read_text(encoding="utf-8"))["texture_data"]

    java_textures = sorted(
        pathlib.PurePosixPath(n).stem
        for n in java.zip.namelist()
        if n.startswith("assets/minecraft/textures/block/") and n.endswith(".png") and n.count("/") == 4)
    bedrock_textures = {
        p.relative_to(pack / "textures/blocks").with_suffix("").as_posix()
        for p in (pack / "textures/blocks").rglob("*")
        if p.suffix in (".png", ".tga", ".jpg")}
    bedrock_by_basename = collections.defaultdict(list)
    for path in bedrock_textures:
        bedrock_by_basename[path.rsplit("/", 1)[-1]].append(path)

    votes = collections.defaultdict(collections.Counter)
    for block, entry in blocks.items():
        if not isinstance(entry, dict):
            continue
        bedrock = bedrock_faces(entry, terrain)
        if not bedrock:
            continue
        for model in java.blockstate_models(block):
            for face, java_names in java.faces(model).items():
                if face in bedrock:
                    for java_name in java_names:
                        best = max(bedrock[face], key=lambda path: similarity(java_name, path))
                        votes[java_name][best] += 1

    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8")) if OVERRIDES.exists() else {}
    table, unmatched, suggestions, by_source = {}, [], {}, collections.Counter()
    for name in java_textures:
        if name in overrides:
            table[name] = overrides[name]
            by_source["override"] += 1
        elif len(bedrock_by_basename.get(name, [])) == 1:
            table[name] = bedrock_by_basename[name][0]
            by_source["same name"] += 1
        else:
            related = [(count, similarity(name, path), path) for path, count in votes[name].items()]
            if any(sim > 0 for _, sim, _ in related):
                table[name] = max(r for r in related if r[1] > 0)[2]
                by_source["block faces"] += 1
            else:
                unmatched.append(name)
                if related:
                    suggestions[name] = max(related)[2]

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(table, indent="\t", sort_keys=True) + "\n", encoding="utf-8")
    print(f"{len(table)} of {len(java_textures)} Java block textures mapped: "
          + ", ".join(f"{count} by {source}" for source, count in by_source.most_common()))
    print(f"{len(unmatched)} unmatched:")
    for name in unmatched:
        print("  " + name + (f"  (paired with {suggestions[name]}, no shared words)" if name in suggestions else ""))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
