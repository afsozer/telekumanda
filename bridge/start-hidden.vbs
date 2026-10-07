' Telekumanda bridge - launches the auto-restart supervisor with no visible console.
' run-bridge.cmd relaunches node if it ever exits; all output goes to bridge.log.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
bridgeDir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = bridgeDir
cmd = "cmd /c " & Chr(34) & bridgeDir & "\run-bridge.cmd" & Chr(34)
sh.Run cmd, 0, False
