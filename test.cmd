@echo off
rem Pulls the latest code and starts the dev client, loading straight into a world:
rem   test.cmd                    the world played most recently
rem   test.cmd "New World (1)"    that world (its folder name under run\saves)
cd /d "%~dp0"
git pull --ff-only
if errorlevel 1 (
  echo.
  echo git pull failed, so the game was not started. Send Claude the message above.
  pause
  exit /b 1
)
set "WORLD=%~1"
if "%WORLD%"=="" if exist "run\saves\" (
  for /f "usebackq delims=" %%W in (`powershell -NoProfile -Command "Get-ChildItem -LiteralPath 'run\saves' -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat') } | Sort-Object { (Get-Item -LiteralPath (Join-Path $_.FullName 'level.dat')).LastWriteTime } -Descending | Select-Object -First 1 -ExpandProperty Name"`) do set "WORLD=%%W"
)
if "%WORLD%"=="" (
  echo No world yet: create one in game, and test.cmd will load straight into it next time.
  call gradlew.bat runClient
) else (
  echo Loading "%WORLD%"
  call gradlew.bat runClient "-PquickPlay=%WORLD%"
)
