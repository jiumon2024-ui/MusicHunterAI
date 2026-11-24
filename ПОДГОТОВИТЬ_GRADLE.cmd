@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare_gradle.ps1"
if errorlevel 1 (
  echo.
  echo Ne udalos podgotovit Gradle. Prover internet i povtori.
  pause
  exit /b 1
)
echo.
echo Gotovo. Teper otkroi papku MusicHunterAI v Android Studio.
pause
