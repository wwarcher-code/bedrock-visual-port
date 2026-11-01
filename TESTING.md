# Testing

Claude's cloud container can build the mod but can't run it, so every in-game check is a round trip: Claude
pushes a change, you run it and send back pictures and a report. These tools make one round take a couple
of minutes.

## One-time setup

1. Download the Bedrock material maps if you haven't (see "Bedrock inputs" in the README).
2. Run `.\test.cmd` from the project folder in PowerShell. With no test world yet, it opens the title screen.
3. Create a singleplayer world named exactly **BVTest**: Creative, Allow Commands on, World Type Superflat.
   From then on, `.\test.cmd` loads straight into it.

## Each round

1. In PowerShell, from the project folder:

   ```powershell
   .\test.cmd
   ```

   It pulls Claude's latest push and starts the game in BVTest. If the pull fails, it stops and says so.
2. The first time in a world (or after Claude changes the scene), type `/bv scene`. It builds the test scene
   three blocks in front of you: a row of material samples (metals, gems, glowing blocks, glass,
   leaves, stone, wood), a pool of water, a pillar with a torch, a roofed overhang that casts shade, and a
   tree. Stand back a few blocks
   and look at it.
3. Press **F9** and stand still for about ten seconds. The mod hides the HUD, clears the weather,
   and takes screenshots at noon, afternoon, low sun and midnight, each with deferred lighting, vanilla,
   the shadow test and the material view, plus albedo, normal and light at noon. Then it restores the time,
   view and HUD, and opens the folder.
4. Send Claude **`contact-sheet.png`** from the folder that opened, and paste the clipboard (Ctrl+V): it
   holds the diagnostics report (build commit, mod versions, GPU, pass status, Bedrock data, biome,
   lighting per time and recent errors). The individual screenshots are in the same folder if Claude asks
   for one at full size.

Captures are kept under `run\bedrock-visuals-captures\<date and time>\`.

## Keys and commands

| Key or command | Does |
| --- | --- |
| F7 | Cycle debug views: albedo, normal, material, light, shadow test, off |
| F8 | Deferred lighting on and off, for comparison with vanilla |
| F9 or `/bv capture` | The full test capture above; F9 again cancels it |
| `/bv scene` | Build the test scene in front of you |
| `/bv status` | Copy the diagnostics report to the clipboard without capturing |
| `/bv` | List these |

Keys can be rebound under Options, Controls, Key Binds, Miscellaneous.

## If something breaks

- **The game crashes:** send `run\logs\latest.log`, or the newest file in `run\crash-reports\`.
- **The capture stops partway:** press F9 again to cancel it (that restores the time, view and HUD), then
  run `/bv status` and paste the report.
- **Gradle errors before the game opens:** paste the PowerShell output.
