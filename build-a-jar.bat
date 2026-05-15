@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat clean buildModJar
echo.
echo Jar output: %CD%\build\jar
endlocal
