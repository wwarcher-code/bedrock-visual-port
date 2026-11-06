"""Names the values in a pixdump export (<capture>.wpix.bvdump.json).

usage: python -I decode.py <dump.json> <extracted materials dir> <dxc dumpbin script> [filter...]

The extracted materials directory holds Bedrock's material shaders as <Material>/<Pass>/<n>_<Stage>.<Platform>.bin
DXIL containers (extracted from the install's renderer/materials/*.material.bin). Each draw's shaders are matched
by hash (the container digest or its HASH part) to a material and pass; that shader's reflection (dxc -dumpbin)
gives each constant buffer's register and field offsets, and the dumped bytes are decoded with it. Filters keep
draws whose material, pass or marker contains any of the given words.
"""
import glob
import json
import os
import re
import struct
import subprocess
import sys


def container_hashes(path):
    data = open(path, 'rb').read()
    if data[:4] != b'DXBC':
        return []
    hashes = [data[4:20].hex()]
    count = struct.unpack_from('<I', data, 28)[0]
    for i in range(count):
        offset = struct.unpack_from('<I', data, 32 + 4 * i)[0]
        if data[offset:offset + 4] == b'HASH':
            hashes.append(data[offset + 12:offset + 28].hex())
    return hashes


def index_shaders(root):
    index = {}
    for path in glob.glob(os.path.join(root, '*', '*', '*Direct3D_SM65.bin')):
        material, shader_pass = path.split(os.sep)[-3:-1]
        for h in container_hashes(path):
            index.setdefault(h, []).append((material, shader_pass, path))
    return index


TYPE = re.compile(r';\s+(column_major\s+)?(float|int|uint|bool)(\d)?(x\d)?\s+(\w+)(\[(\d+)\])?;\s*;\s+Offset:\s+(\d+)')


def reflection(path, dumpbin, cache={}):
    """Constant buffers of a shader: {register: (name, [(field, type, components, rows, count, offset)])}."""
    if path in cache:
        return cache[path]
    text = subprocess.run(['sh', dumpbin, path], capture_output=True, text=True).stdout
    buffers = {}
    current = None
    for line in text.splitlines():
        m = re.match(r';\s+cbuffer (\w+)', line)
        if m:
            current = m.group(1)
            buffers[current] = []
            continue
        m = TYPE.match(line)
        if m and current:
            _, base, comps, rows, name, _, count, offset = m.groups()
            buffers[current].append((name, base, int(comps or 1), int(rows[1:]) if rows else 1, int(count or 1),
                                     int(offset)))
        m = re.match(r';\s+(\w+)\s+cbuffer\s+\S+\s+\S+\s+\S+\s+cb(\d+)(,space(\d+))?', line)
        if m:
            buffers.setdefault('@' + m.group(2), m.group(1))
    by_register = {}
    for key, value in buffers.items():
        if key.startswith('@'):
            by_register[int(key[1:])] = (value, buffers.get(value, []))
    cache[path] = by_register
    return by_register


def decode(fields, data):
    out = []
    for name, base, comps, rows, count, offset in fields:
        values = []
        for element in range(count):
            for row in range(rows):
                start = offset + element * 16 * rows + row * 16
                for c in range(comps):
                    at = start + 4 * c
                    if at + 4 > len(data):
                        continue
                    fmt = {'float': '<f', 'int': '<i', 'uint': '<I', 'bool': '<I'}[base]
                    values.append(struct.unpack_from(fmt, data, at)[0])
        shown = ', '.join(f'{v:.6g}' if isinstance(v, float) else str(v) for v in values)
        out.append(f'      {name} = {shown}')
    return out


def main():
    dump_path, root, dumpbin = sys.argv[1:4]
    filters = [f.lower() for f in sys.argv[4:]]
    index = index_shaders(root)
    dump = json.load(open(dump_path))
    unknown = set()
    for draw in dump['draws']:
        matched = {}
        for shader in draw['shaders']:
            hits = index.get(shader['hash'])
            if hits:
                matched[shader['stage']] = hits[0]
            else:
                unknown.add(shader['hash'])
        label = '; '.join(f'{stage}: {m}/{p}' for stage, (m, p, _) in matched.items()) or 'unmatched shaders'
        text = (label + ' ' + ' '.join(draw['markers'])).lower()
        if filters and not any(f in text for f in filters):
            continue
        print(f"== event {draw['event']} {draw['name']}" + (f" ({draw['pipeline']})" if draw.get('pipeline') else ''))
        if draw['markers']:
            print('   markers: ' + ' > '.join(draw['markers']))
        print('   ' + label)
        for buffer in draw['buffers']:
            if 'bytes' not in buffer:
                print(f"   buffer {buffer['bindings']}: read failed {buffer.get('error')}")
                continue
            data = bytes.fromhex(buffer['bytes'])
            for binding in buffer['bindings'] or [{'stage': '?', 'register': -1}]:
                register = binding.get('register', -1)
                named = False
                for stage, (_, _, path) in matched.items():
                    layout = reflection(path, dumpbin).get(register)
                    if layout:
                        print(f'   {layout[0]} (b{register}, {stage}, {len(data)} bytes)')
                        print('\n'.join(decode(layout[1], data)))
                        named = True
                        break
                if not named:
                    floats = struct.unpack_from(f'<{len(data) // 4}f', data)
                    print(f'   b{register} ({len(data)} bytes, no layout): '
                          + ', '.join(f'{v:.6g}' for v in floats[:64]))
                break
    if unknown:
        print(f'\n{len(unknown)} shader hashes not found among the extracted materials')


if __name__ == '__main__':
    main()
