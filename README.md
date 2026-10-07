# Telekumanda

Telekumanda is a remote control for the coding agents running on your computer: you start and follow Claude Code, Codex, OpenCode and Antigravity sessions from an Android phone or a browser, approve their tool calls, and pick up their results without sitting at the desk. The name is the old Turkish word for a TV remote. The repository contains two main parts:

- A Kotlin / Jetpack Compose Android app in `android/`
- A Node.js bridge server in `bridge/`

The Android app talks to the bridge over HTTP and WebSocket. The bridge runs on the PC, authenticates requests with a bearer token or per-device key, and connects the phone UI to Claude Code, Codex, OpenCode, Antigravity CLI, MCP management, usage tracking, and OTA update delivery.

There is also a browser UI in `web/` that the bridge itself serves at `/ui` —
see "Web UI" below.

## What It Does

The phone app provides a mobile control surface for multiple agent backends:

- Claude Code CLI (Claude App)
- Codex CLI (Codex App)
- OpenCode CLI (OpenCode App)
- Antigravity CLI (`agy.exe`)
- Cowork Mode (multi-agent workspace coordination; a presentation layer that
  runs on top of the Claude App endpoints and can drive any provider)
- Fleet Health Panel (real-time process monitoring & direct kill controls across all active backends)
- Operations Inbox (persistent cross-provider started/waiting/completed/failed event history)
- Project Center (persistent cwd-based project envelopes with sessions, outputs, changes, commands, and plans)

Alongside the agent backends, the app carries a two-sided file manager that
browses both the PC and the phone; it is not an agent session.

Each backend is exposed through the same general chat-style UI: select or start a session, choose a model where supported, send prompts, stream responses, stop running work, inspect thought/details where available, and resume disk sessions when the backend supports it.

> [!IMPORTANT]
> **Rule:** Any commit or PR adding a new backend must update this README's backend list and endpoints before being merged.

## Cowork Mode & Workspace Templates

Cowork mode allows you to run project-centric native provider sessions that coordination-wise target the same folder path.
Workspaces live under Claude App's cowork root. Each workspace maintains its provider metadata under the `.cowork/` folder, allowing Claude App, Codex App, OpenCode App, and OMP (Oh My Pi) to resume their own native sessions without interfering with each other's transcripts.

Only Claude App's bridge session id is stable across restarts. The other three mint a fresh bridge uuid every time a shell is opened, so their records also carry the provider's own persistent id — Codex `threadId`, OpenCode `ses_…`, OMP's own session id (surfaced as `diskId` on the live session list). Resume and de-duplication key on that id, not on the bridge uuid; it lands in the record only after the first turn, via each backend's resolved-session callback.

- A project-level writer lease prevents two providers from modifying the same workspace concurrently. Finished or abandoned leases are released automatically or expire safely.
- The **Devret** action writes a bounded `.cowork/handoff.md` containing recent conversation context and current deliveries, opens the selected provider in the same workspace, and asks it to continue from that handoff.
- Provider model lists and defaults come from the bridge's unified Cowork provider catalog, avoiding Android/bridge drift.
- Drawer file operations target the explicitly selected workspace, while provider/model changes continue to target the active Cowork session workspace.

### Workspace Templates
When creating a workspace, you can initialize it with a predefined structure template:
- **dava-dosyasi**: Pre-structures `belgeler/` and `outputs/`. Instructions enforce writing UDF task drafts to `outputs/`, conducting emsal/mevzuat research via connected MCP engines, and tracking case notes in `notlar.md`.

## File Manager

A single file browser with two tabs — **PC** (over the bridge) and **Phone**
(the device's own storage). Both sides support long-press multi-select with
copy / move / delete, select-all, sorting by name, date or size, and a
show/hide switch for dotfiles and hidden system entries.

Files open **inside the app** rather than handing off to another app:

- Markdown, DOCX and UDF open in their own editors.
- Images open in a viewer with pinch zoom, double-tap zoom and swipe between
  the images in the same folder. Ultra HDR photos are rendered through their
  gainmap where the panel supports it.
- The list shows real thumbnails for images and PDFs in place of the file icon.
- Coming back from any viewer keeps you in the folder you were in, while
  entering the browser fresh from the hub starts at its own root.

## Notes

Notes live as plain Markdown files on the PC: a project note under `<project>/notlar/`,
a standalone note under the cowork root's general-notes folder. Notes are text only.

New notes are created without a name prompt and open straight in the editor; the title
is edited at the top and defaults to the first free `not001`, `not002`, …

## Multiple Bridges

The app can hold several named bridge profiles (id, name, base URL, token) and
switch between them from Settings → Connection.

Tabs are bound to the bridge that created them: sessions only exist on their own
bridge, so after a switch the other bridge's tabs are hidden and no longer
polled. Hidden tabs are not deleted — switching back restores them, and those
sessions keep running meanwhile.

## Notifications

Operation events (turn started, needs attention, completed, failed), notes, and
reminders reach the phone over the app's own connection to the bridge — no Firebase,
no ADB. The full app runs a foreground monitor service that registers the device
(`POST /notifications/device` with its installation id and model), long-polls
`/notifications/poll`, posts each event as a real Android notification, and
acknowledges it (`/notifications/ack`). Undelivered events are queued per device in
`bridge/data/notification-delivery.json`, so a bridge restart or a dropped connection
does not lose them. Turn events go to the device that started the turn; if that is
unknown, to every registered device. The Lite build has no background notifications.
Details: `docs/notification-routing.md`.

## Android App

The Android app is built with Kotlin, Jetpack Compose, Material 3, OkHttp, and Markwon.

Current capabilities include:

- Landing screen for choosing an agent backend
- Bridge URL and token settings with connection test
- Live chat UI with streamed messages
- Markdown rendering for assistant output
- Model, session, slash-command, and usage sheets
- File attachment upload to the bridge
- Two-sided file manager (PC over the bridge, phone storage locally)
- In-app viewers/editors for Markdown, DOCX, UDF, and images
- Stop controls for running backends
- Disk session listing and adoption for supported CLI backends
- MCP server management screen
- Usage and quota displays for supported services
- Foreground monitor service for bridge status
- OTA update check, APK download, and install handoff

The Android package is:

```text
com.agent.bridge
```

The current app version is defined in `android/app/build.gradle.kts`.

## Bridge Server

The bridge is a Node.js server in `bridge/server.mjs`. It exposes REST endpoints and WebSocket streams for the Android app.

Main bridge responsibilities:

- Authenticate phone requests with the token from `bridge/config.json`
- Start and manage CLI-backed sessions for Claude, Codex, OpenCode, and Agy
- Stream conversation updates over WebSocket
- List and resume disk sessions
- Read selected workspace files for the phone UI
- Upload attached files into backend context
- Manage MCP server entries
- Aggregate usage and quota data
- Serve OTA update metadata and APK files

Important WebSocket channels:

```text
/agy/stream
/claude-app/stream
/codex-app/stream
/opencode2-app/stream
```

Important REST areas:

```text
/health                             (vital signs / watchdog check)
/active-state                       (overall bridge status)
/operations                         (active operations and persistent event history)
/projects                           (persistent project envelopes and summaries)
/projects/detail                    (sessions, outputs, changes, commands, and plans for one project)
/projects/security/apply            (explicit per-project security profile application)
/projects/mcp/*                     (project MCP profile listing and explicit global apply)
/backends                           (versioned backend capability catalog and adapter conformance)
/pairing/*, /devices/*              (device pairing codes, per-device keys, key rotation)
/ui-pins                            (read/write starred selector entries, shared across devices)
/file                               (read workspace file content)
/download                           (download workspace file)
/dirs                               (browse directories and files; ?hidden=true reveals dotfiles)
/upload                             (write a file into a target folder; ?mkdir=true creates it)
/delete                             (delete workspace entries)
/rename                             (rename workspace entries)
/move                               (move workspace entries)
/usage                              (retrieve token & token quotas)
/slash                              (fetch command shortcuts per backend)
/notifications/device, /ack        (register a device, acknowledge delivered notifications)
/notifications/poll                 (long-poll background notifications)
/cowork/*                           (projects, import, matters, session switching)
/cowork/notes, /cowork/note/*       (text notes, rename, attach, AI actions)
/agy/*                              (Antigravity CLI actions and thought retrieval)
/claude-app/*                       (Claude Code app workspaces, MCP, thoughts, quota suggest)
/codex-app/*                        (Codex app fork, compact, efforts, approve, rewind)
/opencode2-app/*                    (OpenCode 2 app: steer, fork-from, rewind, diff, checkpoints, revert, instructions, permissions/saved, commands, compact, pin/rename)
/update/*                           (delivery of OTA Android APK packages)
```

## Requirements

The bridge is developed and run on Windows. It shells out to agent CLIs that must
already be installed and logged in on the host machine:

- **Node.js 22 LTS** — required, not optional (see "Running The Bridge")
- **Claude Code CLI** (`claude`) — for the Claude App and Cowork backends
- **Codex CLI** (`codex`) — for the Codex App backend
- **OpenCode CLI** (`opencode`) — for the OpenCode App backend
- **Antigravity CLI** (`agy.exe`) — for the Agy backend
- **JDK 17+ and the Android SDK** — only to build the app

Backends whose CLI is missing simply fail when you start a session; the bridge
itself still runs. Each CLI keeps its own credentials and config outside this
repository (for example `~/.claude`, `~/.codex`, `~/.gemini`), so a fresh machine
needs those CLIs authenticated separately before the corresponding backend works.

## Configuration

### Device pairing

The legacy shared Bearer token remains supported for migration. In Android,
Settings > Device security can create a single-use code valid for five minutes;
the paired phone receives its own device key. Rotating that key immediately
invalidates the previous one. The bridge stores only SHA-256 key digests and
returns the clear key once, in the pairing or rotation response.

The bridge reads its runtime configuration from `bridge/config.json`.
This file is intentionally ignored by Git because it contains the auth token
and local machine settings. Copy the template to create it:

```powershell
copy bridge\config.example.json bridge\config.json
```

Then edit it and replace `authToken` with a long random string of your own.
Settings:

- `host` / `port`: bridge listen address (default `0.0.0.0:8787`)
- `authToken`: shared bearer token used by the phone
- `workspaceRoots`: extra roots allowed for remote file lookup (optional)
- `fileAliases`: short name → absolute path shortcuts for `/file` (optional)
- `zcodePromptCap5h`: 5-hour prompt cap used by usage display (optional)

### Selector pins (starred entries)

Model and agent pickers are long, so the way to tame them is search plus stars —
not a blacklist. The old `hiddenModels` / `/model-visibility` system was removed
(27.08.2026): hiding a provider hid models the user later wanted, and every new
provider needed a fresh blacklist entry.

Stars live on the *bridge*, not on the device: `GET /ui-pins` returns every
scope, `POST /ui-pins` writes one scope (`{scope, pinned}`). Phone and tablet
share a bridge, so they share their stars; two PCs running two bridges keep
separate sets on purpose. Scope names come from the client
(`backend-model:<backendId>`, `backend-agent:<backendId>`).

Conflicts resolve last-writer-wins — a toggle sends the scope's whole set and
overwrites what the bridge had. Storage is `bridge/ui-pins.json` (gitignored),
kept out of `config.json` because nothing here needs the secrets that file
holds. On first connect a client whose bridge has no file yet uploads its local
stars once; after that the bridge is the source and the local cache is replaced.

Optional environment variables:

- `AGENTBRIDGE_NODE`: full path to the Node.js 22 executable used by
  `bridge/run-bridge.cmd`
- `AGENTBRIDGE_COWORK_ROOT`: Cowork workspace root (default `~/CoworkSpaces`)

The Android app stores the bridge URL, token, and cached usage data in local shared preferences.

## Running The Bridge

From the bridge directory:

```powershell
npm install
node server.mjs
```

The bridge requires Node.js 22 LTS. This is intentional: `node-pty` is pinned to
a runtime known to work reliably with the Windows ConPTY integration used by the
agent backends.

There is also a Windows supervisor script:

```powershell
bridge\run-bridge.cmd
```

That script is intended for the local Windows setup and restarts the bridge if it exits.
It uses `AGENTBRIDGE_NODE` when set; otherwise it expects `node` on `PATH` to be
Node.js 22.

## Logging Architecture

The bridge logging system separates normal runtime logging from early bootstrap errors:
- **`bridge.log`**: The main logging target. Standard output redirection was replaced by a centralized Node-side logging controller (`logger.mjs` / `log-rotation.mjs`). All console logs, warnings, and errors are formatted with an ISO timestamp and severity level, then appended synchronously to this file. It automatically rotates and archives older files (e.g. `bridge.log.1`) based on file size constraints.
- **`bootstrap.log`**: Used as a supervisor redirect target by `run-bridge.cmd`. It captures console outputs and errors before Node.js has initialized the bridge or logger (e.g. syntax errors, missing executables, or crash-on-start failures). It remains empty during normal operations.

## Building The Android App

From the Android project directory:

```powershell
cd android
.\gradlew assembleDebug
```

The debug APK is produced under:

```text
android/app/build/outputs/apk/debug/
```

On first run, open Settings in the app and set the bridge URL and token to match
your own machine. The URL compiled in as a default (`DEFAULT_URL` in
`RemoteUiState.kt`) is a localhost placeholder (`http://127.0.0.1:8787`) and will
not reach your PC from the phone — enter your machine's own address in Settings.

## Web UI

`web/` is a React + TypeScript client that the bridge serves itself at
`http://<bridge>/ui/`. Same chat, sessions and controls on a keyboard-and-mouse
layout, with no install and no update channel: rebuilding `web/dist` is enough,
the bridge reads it from disk on every request.

It re-implements the bridge contract in TypeScript rather than sharing
`android/shared/` (that module is Kotlin). `SessionStreamManager.kt` and
`BridgeClientBackend.kt` stay canonical — see `web/README.md`.

```powershell
cd web
npm install
npm run build     # production: served by the bridge at /ui
npm run dev       # development: http://localhost:5173/ui/ , proxies to the bridge
```

Token flow: open `/ui/?token=<bridge token>` once; the token is stored in
`localStorage` and stripped from the address bar.

> The former Compose Desktop client (`android/desktop/`) was removed on
> 2026-08-10 — it had fallen far behind the phone app because every screen had
> to be written twice. Its jpackage/MSI packaging and the `/update/desktop`
> endpoints are gone with it.

## Repository Layout

```text
android/                 Gradle root for the Android clients
android/app/             Android app (Kotlin + Jetpack Compose)
android/shared/          Android-free Kotlin/JVM logic, shared and unit-tested
bridge/                  Node.js bridge and backend adapters
bridge/server.mjs        Main HTTP and WebSocket server, route definitions, SLASH commands
bridge/router.mjs        Route table and dispatch
bridge/routes/           Shared route groups (backend, general, mcp, update)
bridge/web-ui.mjs        Serves web/dist at /ui (before auth; see the note in that file)
web/                     Browser UI (React + TypeScript, built with Vite)
bridge/claude-app.mjs    Claude Code backend (+ claude-app-adapter.mjs)
bridge/codex-app.mjs     Codex backend (+ codex-app-adapter.mjs)
bridge/opencode2-app.mjs OpenCode backend (OpenCode 2)
bridge/agy.mjs           Antigravity CLI (agy.exe) backend, PTY-driven
bridge/cowork.mjs        Cowork workspaces, leases, handoff
bridge/projects.mjs      Persistent project envelopes
bridge/operations.mjs    Cross-provider operations inbox
bridge/device-auth.mjs   Device pairing codes and per-device keys
bridge/mcp.mjs           MCP server registry management (+ per-backend *-mcp.mjs)
bridge/usage.mjs         Usage and quota aggregation
bridge/release.mjs       APK release script (version bump + gradle build)
bridge/config.example.json  Template for the git-ignored bridge/config.json
bridge/test/             Unit tests (node:test)
docs/                    Design notes, roadmaps, and phase instructions (Turkish)
```

## Agy Backend — PTY Interactive Mode

`bridge/agy.mjs` drives `agy.exe` through a **pseudo-terminal (node-pty)**, not plain pipes. This is required and was arrived at after testing; the reasoning is recorded here so it is not undone by accident:

- **Why PTY, not `spawn`/pipe.** `agy -i` (`--prompt-interactive`) only processes a prompt when stdin reaches EOF. With a persistent open pipe (needed for a live session) agy processes nothing and every prompt hangs forever. Run under a PTY, agy behaves as a real interactive TUI and processes input normally.
- **Why not `-p`/`--print`.** Print mode is single-shot and does not reliably carry multi-step tasks (edit → long build → summarize) through to a final reply.
- **Initial prompt as argument.** The first prompt must be passed as an argument: `pty.spawn(agy, ['-i', firstPrompt, '--dangerously-skip-permissions', ...])`. An empty `-i` TUI hangs.
- **Trust prompt.** On the first open of a directory, agy shows `Do you trust the contents of this project?`. `--dangerously-skip-permissions` does **not** skip it — this was the original "agent says it's working then never replies" hang. The bridge detects this in the PTY output and auto-answers `1` (Yes).
- **Content source is the transcript, not the PTY.** PTY output is a noisy TUI render (spinners, ANSI). All chat content is read from `~/.gemini/antigravity-cli/brain/<conversationId>/.system_generated/logs/transcript.jsonl`. The PTY stream is used only for prompt detection (trust / approval).
- **Turn-end is deterministic.** A turn is complete when the transcript's last meaningful entry (ignoring `CONVERSATION_HISTORY` / `CHECKPOINT`) is a non-empty `PLANNER_RESPONSE`. There is **no absolute turn cap** — only a 60-minute no-activity idle timeout — so long builds (`flutter build apk`, 10+ min) are never killed mid-run.
- **Background-task guard.** agy runs long shell commands (`flutter analyze/build`) as **async background tasks**: the `RUN_COMMAND` entry reads "Tool is running as a background task" and the model emits an intermediate prose ("started it, will act on the result") that looks like a final reply. When the task finishes, agy injects a `SYSTEM_MESSAGE` that re-prompts the model to continue. To avoid the phone showing "finished" while work continues for minutes, a turn is held open while a background task is in flight. The reliable signal is **order, not count** (there can be extra `SYSTEM_MESSAGE` entries): a task is in flight iff the last background-task launch has no `SYSTEM_MESSAGE` after it (`lastBgIdx > lastSysIdx`).
- **Conversation id discovery.** `last_conversations.json` is written late, so the bridge instead scans the brain directory for the newest conversation created after spawn (`discoverBrainConversation`).
- **Multi-turn = kill + respawn.** Writing a second prompt into the live TUI is unreliable, so each follow-up message kills the PTY and respawns with `--conversation=<id>` to resume the same conversation (history is preserved by agy in the brain dir).
- **B2 (in-app approval) is partial.** Only the trust prompt is auto-handled. Generic OAuth/login prompts are matched conservatively (line-ending `[y/n]` / `(yes/no)` only, to avoid false positives on model prose) and surfaced to the phone via `awaitingApproval` / `pendingPrompt`; `POST /agy/respond` writes the answer back to the PTY. The exact OAuth prompt format has not yet been observed live.
- **Process admin is PTY-aware.** Other backends are killed via `process-admin.mjs`, which scans for `powershell.exe` wrappers by `\<name>-tmp\` CommandLine signature. agy has no such wrapper anymore, so `/agy/processes` and `/agy/kill-all` are served by `agy.listLiveProcesses()` / `agy.killAllSessions()` (operating on the PTY handles in the session map). `killAllSessions()` also clears the session map so "active sessions" drops to 0.
- **Known cosmetic issue.** On each PTY `kill()` (every multi-turn message), node-pty's Windows ConPTY helper may log `AttachConsole failed` to stderr. It is harmless — the turn still completes and no process is leaked — but it adds noise to `bridge.log`.

## Notes

Telekumanda grew out of a personal automation project. Several backend adapters assume
Windows, locally installed CLIs, browser/IDE profiles, and credentials already
configured on the PC. The bridge should be treated as a trusted local service:
it can read and write files and run agent CLIs with broad permissions, so do not
expose it to the public internet. Bind it to a private network (the author runs
it over Tailscale) and keep the auth token secret.

`scripts/` ships only a few generic, machine-independent tools (`masaustu.ps1`,
`ocr.ps1`, `cdp.mjs`, `ui-olc.mjs`). The author's personal helper scripts are
git-ignored and are not part of the bridge or the app.

## Names inside the code

Telekumanda was called AgentBridge until October 2026. Identifiers that live on
users' devices keep the old name so that existing installs keep working and keep
receiving OTA updates: the Android package `com.agent.bridge`, the data
directory `~/.agentbridge`, the `AGENTBRIDGE_*` environment variables and the
browser storage keys that start with `agentbridge.`. Only user-facing text uses
the new name.

## Security

Telekumanda gives whoever holds a valid token the ability to run agent CLIs and
read and write files on the host, so please report vulnerabilities privately as
described in [SECURITY.md](SECURITY.md) rather than in a public issue.

## License

Copyright (C) 2026 Alpaslan Fatih Sözer.

Telekumanda is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License, version 3, as published by the
Free Software Foundation. See [LICENSE](LICENSE). The bundled Liberation fonts
keep their own license (`android/LIBERATION-FONTS-LICENSE.txt`).
