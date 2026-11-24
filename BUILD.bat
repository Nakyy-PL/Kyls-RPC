@echo off
echo Building Kyls Client RPC (needs JDK 25 + internet)...
call gradlew.bat build
if errorlevel 1 (
  echo.
  echo BUILD FAILED. Send the error message above to Claude.
) else (
  echo.
  echo DONE. The jar is in build\libs\  (use the one without -sources)
  start "" build\libs
)
pause
