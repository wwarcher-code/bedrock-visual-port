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
2. Type `/bv scene`. It builds the test scene north of you (a row of material samples, a pool of water, a
   pillar with a torch, a roofed overhang that casts shade, and a tree) and turns you to face it, so every
   capture uses the same camera. Run it before each capture, after a pull, or after you've moved.
3. Press **F9** and stand still for about ten seconds. The mod hides the HUD, clears the weather,
   and takes screenshots at noon, afternoon, low sun and midnight, each with deferred lighting, vanilla,
   the shadow test and the material view, plus albedo, normal and light at noon. Then it restores the time,
   view and HUD, and opens the folder.
4. Send Claude **`contact-sheet.png`** from the folder that opened, and paste the clipboard (Ctrl+V): it
   holds the diagnostics report (build commit, mod versions, GPU, pass status, Bedrock data, biome,
   lighting per time and recent errors). The individual screenshots are in the same folder if Claude asks
   for one at full size.

Captures are kept under `run\bedrock-visuals-captures\<date and time>\`.

## Bedrock reference shots

Tuning needs the real Vibrant Visuals to compare against. The reference pack builds the identical scene in
Bedrock (generated from the same code as `/bv scene`) and stands you in the same spot, so a Bedrock
screenshot lines up with the Java capture.

### Once

1. In File Explorer, open the project folder, then `bedrock`, and double-click `BedrockVisualsTest.mcpack`.
   Bedrock opens and shows "Import started", then "Successfully imported". (If Windows asks which app to
   open it with, choose Minecraft.)
2. Turn on Vibrant Visuals: from the title screen, **Settings**, then **Video**, then set **Graphics Mode** to
   **Vibrant Visuals**. Set **Field of View** to the number on the `window` line of the Java report.
3. Make the world: **Play**, then **Create New**, then **Create New World**.
   - **General:** World Name `BVTest`, Game Mode **Creative**, Difficulty **Peaceful**.
   - **Advanced:** turn **Flat World** on.
   - **Cheats:** turn **Cheats** on (Bedrock warns that achievements are disabled; that's fine).
   - **Behavior Packs:** under **Available**, click **Bedrock Visuals test scene**, then **Activate**.
   - Click **Create**.

### Each time

1. Open the BVTest world (**Play**, then **Worlds**). Press **T** to open chat and type each command, then
   Enter:

   ```
   /function bv/scene
   ```

   This builds the scene, faces you north at it, stops the clock and weather, and sets noon.
2. Type `/function bv/noon`. It hides the HUD; press **F1** as well to hide your hand. Wait a few seconds for
   the chat to fade, then press
   **Win+Alt+PrtScn**. Windows saves the shot to `Videos\Captures` in your user folder. Win+Alt+PrtScn captures only the game; Win+PrtScn
   captures the whole desktop, which works but is harder to compare.
3. Repeat step 2 with `/function bv/afternoon`, `/function bv/lowsun` and `/function bv/midnight`.
4. Press **F1** again and type `/function bv/hud` to bring the HUD back. Send Claude the four screenshots.

Don't move between the commands: the shots must be taken from where `bv/scene` puts you.

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
