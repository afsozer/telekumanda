@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  Telekumanda koprusunu yeniden baslat.
REM  :8787 dinleyen node (server.mjs) surecini agac olarak oldurur.
REM  run-bridge.cmd supervisor'u node cikinca ~3 sn icinde tekrar
REM  baslattigi icin sonuc "temiz restart" olur.
REM ============================================================
chcp 65001 >nul

set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8787 .*LISTENING"') do set "PID=%%p"

if not defined PID (
  echo Bridge :8787 uzerinde dinlemede degil.
  echo Supervisor ^(run-bridge.cmd^) hic calismiyor olabilir - onu manuel baslatin.
  timeout /t 4 >nul
  exit /b 0
)

echo Bridge sureci bulundu: PID !PID!
echo Oldruluyor ^(agac dahil^)...
taskkill /PID !PID! /T /F

if errorlevel 1 (
  echo UYARI: taskkill basarisiz oldu. Yonetici olarak calistirmayi deneyin.
  timeout /t 5 >nul
  exit /b 1
)

echo.
echo Bridge oldruldu. Supervisor birkac saniye icinde yeniden baslatacak.
echo Kontrol icin: netstat -ano ^| findstr :8787
timeout /t 4 >nul
