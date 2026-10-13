# Windows PowerShell 5.1 compatible. Does not stop processes or modify MySQL.
[CmdletBinding()]
param(
    [string]$InstallRoot = 'D:\Happy4J',
    [string]$TargetJar = ''
)

$ErrorActionPreference = 'Stop'
$jarName = 'happy4j-0.0.1-SNAPSHOT.jar'
$source = Join-Path $PSScriptRoot $jarName

function Stop-Upgrade([string]$reason) {
    throw $reason
}

if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    Stop-Upgrade "Package JAR missing: $source"
}
if (-not (Test-Path -LiteralPath $InstallRoot -PathType Container)) {
    Stop-Upgrade "Installation folder missing: $InstallRoot"
}

$root = (Resolve-Path -LiteralPath $InstallRoot).Path
if ([string]::IsNullOrWhiteSpace($TargetJar)) {
    $paths = @(
        (Join-Path $root $jarName),
        (Join-Path $root (Join-Path 'build\libs' $jarName)),
        (Join-Path $root (Join-Path 'backend\build\libs' $jarName))
    )
    $matches = @($paths | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($matches.Count -ne 1) {
        Write-Host "Found $($matches.Count) matching JARs. The updater will not guess the active JAR."
        $matches | ForEach-Object { Write-Host "  $_" }
        Stop-Upgrade 'Specify the exact existing file with -TargetJar after checking your original startup command.'
    }
    $TargetJar = $matches[0]
}

$target = [System.IO.Path]::GetFullPath($TargetJar)
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    Stop-Upgrade "Existing JAR missing: $target"
}
$rootPrefix = $root.TrimEnd('\') + '\'
if (-not $target.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    Stop-Upgrade 'The target JAR must be located inside the chosen installation folder.'
}

$running = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
    Where-Object {
        $command = [string]$_.CommandLine
        $command.IndexOf('happy4j', [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $command.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    })
if ($running.Count -gt 0) {
    $running | ForEach-Object { Write-Host "Active Happy4J Java PID: $($_.ProcessId)" }
    Stop-Upgrade 'Stop the existing Happy4J Java process normally and run the updater again. Other Java processes are unaffected.'
}

$newHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$oldHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
if ($newHash -eq $oldHash) {
    Write-Host 'The installed JAR already matches the update. No change was made.'
    exit 0
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $root (Join-Path '_upgrade_backup' $stamp)
New-Item -Path $backupDir -ItemType Directory -Force | Out-Null
$backup = Join-Path $backupDir (Split-Path $target -Leaf)
Copy-Item -LiteralPath $target -Destination $backup -ErrorAction Stop
if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne $oldHash) {
    Stop-Upgrade 'Backup checksum does not match. The existing JAR was not replaced.'
}

$pending = "$target.pending-$stamp"
$previous = "$target.previous-$stamp"
Copy-Item -LiteralPath $source -Destination $pending
if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash -ne $newHash) {
    Remove-Item -LiteralPath $pending -Force
    Stop-Upgrade 'Package checksum verification failed. The existing JAR was not replaced.'
}

$movedOld = $false
try {
    Move-Item -LiteralPath $target -Destination $previous
    $movedOld = $true
    Move-Item -LiteralPath $pending -Destination $target
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $newHash) {
        Stop-Upgrade 'Installed JAR checksum mismatch.'
    }
    Remove-Item -LiteralPath $previous -Force
} catch {
    if ($movedOld) {
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            Remove-Item -LiteralPath $target -Force
        }
        if (Test-Path -LiteralPath $previous -PathType Leaf) {
            Move-Item -LiteralPath $previous -Destination $target
        } else {
            Copy-Item -LiteralPath $backup -Destination $target -Force
        }
    }
    if (Test-Path -LiteralPath $pending -PathType Leaf) {
        Remove-Item -LiteralPath $pending -Force
    }
    throw
}

Write-Host ''
Write-Host 'Update JAR installed successfully.'
Write-Host "Target: $target"
Write-Host "Old JAR backup: $backup"
Write-Host "SHA256: $newHash"
Write-Host 'MySQL, .env, account state, and startup configuration were not modified.'
Write-Host 'Start Happy4J with your ORIGINAL startup command to preserve its environment variables.'
Write-Host 'Before auto-play, verify lobby connectivity, Luna provider, and settlement statistics.'
