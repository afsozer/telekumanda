@echo off
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
cd /d "%~dp0"

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
  echo ERROR: AgentBridge requires Node.js 22 LTS. Set AGENTBRIDGE_NODE to a Node 22 executable or put Node 22 first on PATH.
  echo ERROR: AgentBridge requires Node.js 22 LTS. Set AGENTBRIDGE_NODE to a Node 22 executable or put Node 22 first on PATH. >> bootstrap.log
  exit /b 1
)
:loop
REM Re-check on EVERY launch attempt, not just supervisor startup. Two supervisors
REM can coexist when one loses its child; without this guard the loser wakes later,
REM races the winner for :8787 and remains in a permanent EADDRINUSE loop.
netstat -ano | findstr /r /c:":8787 .*LISTENING" >nul && (
  echo ===== bridge already running on :8787, supervisor exiting %DATE% %TIME% ===== >> bootstrap.log
  exit /b 0
)
echo. >> bootstrap.log
echo ===== bridge start %DATE% %TIME% ===== >> bootstrap.log
REM Optional local settings can be supplied by the environment before launching.
set CHATGPT_PLANNER_AUTO_OPEN=
REM Pinned to Node 22 LTS: node-pty 1.1.0's ConPTY console-list agent crashes
REM ("AttachConsole failed") on Node v25, taking the whole bridge down. Node 22 is
REM the supported runtime for this node-pty version.
"%NODE_EXE%" "%~dp0server.mjs" >> bootstrap.log 2>&1
echo ===== bridge exited (code %ERRORLEVEL%) %DATE% %TIME% ===== >> bootstrap.log
REM brief pause so a tight crash-loop doesn't spin the CPU
ping -n 3 127.0.0.1 >nul
goto loop
