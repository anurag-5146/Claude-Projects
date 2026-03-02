@echo off
title CIRCUIT Assistant v2.0
color 0B
cd /d E:\assistant

echo.
echo  =========================================
echo    CIRCUIT OS v2.0  ^|  Starting up...
echo  =========================================
echo.

REM Check if already running on port 8000
netstat -an 2>nul | findstr ":8000 " >nul
if %ERRORLEVEL% EQU 0 (
    echo  Already running on port 8000 — opening browser.
    start "" "http://localhost:8000"
    exit /b
)

REM Open browser 3 seconds after server starts (background PS call)
start "" powershell -WindowStyle Hidden -Command "Start-Sleep 3; Start-Process 'http://localhost:8000'"

title CIRCUIT Assistant — http://localhost:8000
echo  Server starting... browser will open automatically.
echo  Keep this window open. Close it to stop CIRCUIT.
echo.

python run.py

echo.
echo  CIRCUIT stopped. Press any key to close.
pause >nul
