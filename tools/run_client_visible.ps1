[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# A runClient started by the node service can leave a generated NeoGradle
# classpath pointing at SYSTEM's profile. It is generated state, so discard
# only contaminated copies before launching in the interactive session.
Get-ChildItem -Path (Join-Path $RepoRoot '.gradle\configuration\neoForm') -Filter classpath.txt -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like '*\writeMinecraftClasspathClient\classpath.txt' } |
    ForEach-Object {
        if (Select-String -LiteralPath $_.FullName -SimpleMatch 'systemprofile\.gradle' -Quiet) {
            Remove-Item -LiteralPath $_.FullName -Force
        }
    }

if (-not ('LilyInteractiveSession' -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class LilyInteractiveSession {
    [DllImport("kernel32.dll")]
    public static extern uint WTSGetActiveConsoleSessionId();
}
"@
}

$consoleSessionId = [int][LilyInteractiveSession]::WTSGetActiveConsoleSessionId()
$desktopSessions = Get-Process explorer -IncludeUserName -ErrorAction SilentlyContinue |
    Where-Object SessionId -gt 0 |
    Group-Object SessionId

$targetDesktop = $desktopSessions | Where-Object { [int]$_.Name -eq $consoleSessionId } | Select-Object -First 1
if (-not $targetDesktop) {
    if (@($desktopSessions).Count -eq 1) {
        $targetDesktop = @($desktopSessions)[0]
    }
    else {
        throw 'No unambiguous interactive desktop session is available; refusing to launch a hidden Minecraft client.'
    }
}

$targetSessionId = [int]$targetDesktop.Name
$explorer = $targetDesktop.Group | Select-Object -First 1
$targetUser = $explorer.UserName
$currentSessionId = (Get-Process -Id $PID).SessionId
$launchCommand = 'gradlew.bat runClient'

function Start-ClientProcess {
    Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', $launchCommand) -WorkingDirectory $RepoRoot | Out-Null
}

if ($currentSessionId -eq $targetSessionId) {
    Start-ClientProcess
    Write-Host "Immersive Portals client launch started directly in session $targetSessionId ($targetUser)."
    exit 0
}

$taskName = "ImmersivePortals-Interactive-$([guid]::NewGuid().ToString('N'))"
$marker = Join-Path $RepoRoot ".gradle\$taskName.dispatched"
$escapedRoot = $RepoRoot.Replace("'", "''")
$escapedMarker = $marker.Replace("'", "''")
$child = "Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d','/c','gradlew.bat runClient') -WorkingDirectory '$escapedRoot'; Set-Content -LiteralPath '$escapedMarker' -Value 'dispatched'"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -Command `"$child`""
$principal = New-ScheduledTaskPrincipal -UserId $targetUser -LogonType Interactive -RunLevel Highest

try {
    Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
    Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName

    for ($i = 0; $i -lt 80 -and -not (Test-Path -LiteralPath $marker); $i++) {
        Start-Sleep -Milliseconds 125
    }
    if (-not (Test-Path -LiteralPath $marker)) {
        throw "Timed out dispatching runClient into interactive session $targetSessionId."
    }
}
finally {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
}

Write-Host "Immersive Portals client launch routed from session $currentSessionId to visible session $targetSessionId ($targetUser)."