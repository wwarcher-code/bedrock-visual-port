# pixdump

Exports the constant buffers that Bedrock binds at each draw in a PIX GPU capture (`.wpix`) to a JSON file, so
the runtime values don't have to be read out of the PIX window one by one.

## Running it (Windows, PIX installed)

```powershell
.\tools\pixdump\pixdump.exe "C:\path\to\capture.wpix"
```

It loads `pixapi.dll` from your PIX install (under `C:\Program Files\Microsoft PIX`). PIX replays the capture
on your GPU to read the buffers, which can take a minute or two. It writes `capture.wpix.bvdump.json` next to the
capture. Upload that file; Claude names every value from it.

By default it exports the first 3 draws of each distinct shader combination, which keeps it quick (Bedrock
draws terrain thousands of times with the same shaders). Add `--all` to export every draw, or
`--max-per-pipeline 10` for more of each.

## What's in the export

For each draw or dispatch: its event number and name, the PIX markers around it, the shaders bound (stage and
DXIL hash), and every constant buffer bound (stage, register, raw bytes). `decode.py` matches the hashes to
Bedrock's material shaders and decodes the bytes with their constant buffer layouts.

## Building

`pixdump.exe` is cross-compiled with mingw-w64 against the PIX API headers (from the PIX API documentation)
and the Agility SDK's `d3d12.h`; neither is in this repository. The exe loads `pixapi.dll` at run time, so it
needs no import library.
