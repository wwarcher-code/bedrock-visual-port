# Notes for Claude

- The developer is on Windows. Give every command and path for Windows: PowerShell syntax, `gradlew.bat`,
  backslash paths such as `run\logs\latest.log`.
- Claude's cloud container has no GPU, but it can run the game on Mesa's software Vulkan driver (llvmpipe) at
  about 1 fps: enough to look at the picture and catch shader errors, not to measure performance.
  - `apt-get install -y mesa-vulkan-drivers xdotool imagemagick`, then start the client under Xvfb (e.g.
    `Xvfb :99 -screen 0 1280x720x24 &` and `DISPLAY=:99 ./gradlew runClient "-PquickPlay=<world>" -PbvScript=<file>`).
  - `-PbvScript` makes the mod run lines appended to that file (devtools/ScriptRunner.java): `/commands`,
    `view <DEBUG_VIEW>`, `hud off`, `fly on`, `lighting off`, `clouds off`, `reload` (reloads shaders) and more.
  - Screenshot the window with `import -window root shot.png` (DISPLAY=:99).
  - The Bedrock data the mod reads is found under `/XboxGames/Minecraft for Windows/Content` and
    `~/bedrock-samples` (link the copies kept in the scratchpad there).
  - Behaviour on the developer's GPU and real frame rates still need the developer's own runs.
