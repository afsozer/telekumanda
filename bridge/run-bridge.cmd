@echo off
REM Supervisor runs from a private copy in %TEMP%. cmd reads a running batch
REM file by byte offset; when git pull rewrote this file under a live supervisor,
REM it resumed mid-line after node exited and never relaunched the bridge
REM (08.11.2026: it ran "bootstrap.log" as a command and hung on Notepad).
REM Chaining without CALL hands control to the copy for good, so this file is
REM never read again and can change freely.
REM The check compares file names only, so an 8.3 or differently spelled %TEMP%
REM path can never make the copy copy itself again.
if /i "%~nx0"=="%TELEKUMANDA_SUPERVISOR_NAME%" goto supervise
set "TELEKUMANDA_BRIDGE_DIR=%~dp0"
set "TELEKUMANDA_SUPERVISOR_NAME=telekumanda-supervisor-%RANDOM%%RANDOM%.cmd"
set "TELEKUMANDA_SUPERVISOR_COPY=%TEMP%\%TELEKUMANDA_SUPERVISOR_NAME%"
copy /y "%~f0" "%TELEKUMANDA_SUPERVISOR_COPY%" >nul || (
  echo ERROR: could not copy supervisor to %TELEKUMANDA_SUPERVISOR_COPY% >> "%~dp0bootstrap.log"
  exit /b 1
)
"%TELEKUMANDA_SUPERVISOR_COPY%"

:supervise
setlocal
REM Auto-restart supervisor for the Telekumanda bridge.
REM If node exits for any reason (crash, OOM, accidental kill), it is relaunched
REM after a short pause.
REM
REM Log mimarisi (madde 14): node'un uygulama loglarını server.mjs içindeki
REM logger.mjs kendisi bridge.log'a yazar (append, sync) ve boyut-tabanlı
REM rotasyon uygular. Buradaki CMD redirect yalnızca bootstrap.log'a gider —
REM bu dosya logger devreye girmeden ÖNCE ölen erken hataları yakalar
REM (node.exe bulunamadı, server.mjs sözdizimi hatası, import çökmesi).
REM Normal koşulda bootstrap.log hep boş kalır; bridge.log uygulama logudur.
cd /d "%TELEKUMANDA_BRIDGE_DIR%"

if defined AGENTBRIDGE_NODE (
  set "NODE_EXE=%AGENTBRIDGE_NODE%"
) else (
  set "NODE_EXE=node"
)

REM Probe the major version via a temp file. A quoted NODE_EXE path (e.g. when
REM AGENTBRIDGE_NODE points to a full path) breaks the `for /f` backtick form's
REM quote parsing, so redirect to a file and read it back instead.
set "NODE_MAJOR="
"%NODE_EXE%" -p "process.versions.node.split('.')[0]" > "%TEMP%\agentbridge_nodemajor.txt" 2>nul
if exist "%TEMP%\agentbridge_nodemajor.txt" set /p NODE_MAJOR=<"%TEMP%\agentbridge_nodemajor.txt"
del "%TEMP%\agentbridge_nodemajor.txt" 2>nul
if not "%NODE_MAJOR%"=="22" (
  echo ERROR: Telekumanda requires Node.js 22 LTS. Set AGENTBRIDGE_NODE to a Node 22 executable or put Node 22 first on PATH.
  echo ERROR: Telekumanda requires Node.js 22 LTS. Set AGENTBRIDGE_NODE to a Node 22 executable or put Node 22 first on PATH. >> bootstrap.log
  goto done
)
:loop
REM Re-check on EVERY launch attempt, not just supervisor startup. Two supervisors
REM can coexist when one loses its child; without this guard the loser wakes later,
REM races the winner for :8787 and remains in a permanent EADDRINUSE loop.
netstat -ano | findstr /r /c:":8787 .*LISTENING" >nul && (
  echo ===== bridge already running on :8787, supervisor exiting %DATE% %TIME% ===== >> bootstrap.log
  goto done
)
echo. >> bootstrap.log
echo ===== bridge start %DATE% %TIME% ===== >> bootstrap.log
REM Optional local settings can be supplied by the environment before launching.
set CHATGPT_PLANNER_AUTO_OPEN=
REM Pinned to Node 22 LTS: the tested runtime for this bridge. (The original
REM reason was node-pty's ConPTY crash on Node v25; node-pty left with the agy
REM backend on 08.11.2026, but the pin stays until Node 25 is verified here.)
"%NODE_EXE%" "%TELEKUMANDA_BRIDGE_DIR%server.mjs" >> bootstrap.log 2>&1
echo ===== bridge exited (code %ERRORLEVEL%) %DATE% %TIME% ===== >> bootstrap.log
REM brief pause so a tight crash-loop doesn't spin the CPU
ping -n 3 127.0.0.1 >nul
goto loop

:done
REM Delete this %TEMP% copy on the way out: "(goto)" ends the batch context first,
REM so cmd does not try to read the next line from a deleted file. A hard kill
REM (taskkill) skips this and leaves a ~3 KB copy behind in %TEMP%.
(goto) 2>nul & del "%~f0"
