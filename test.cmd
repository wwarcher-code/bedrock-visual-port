@echo off
rem Pulls the latest code and starts the dev client. If a world named BVTest exists, it loads straight into it.
cd /d "%~dp0"
git pull --ff-only
if errorlevel 1 (
  echo.
  echo git pull failed, so the game was not started. Send Claude the message above.
  pause
  exit /b 1
)
if exist "run\saves\BVTest\" (
  call gradlew.bat runClient -PquickPlay=BVTest
) else (
  echo No BVTest world yet: create one in game ^(see TESTING.md^) to load straight into it next time.
  call gradlew.bat runClient
)
