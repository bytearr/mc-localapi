param(
    [string]$Instance = $(Join-Path $env:USERPROFILE 'AppData\Roaming\ATLauncher\instances\Fabulouslypenis263')
)
# Installs the mod jar and the plugins (pre-approved by SHA-256). Close Minecraft first:
# a loaded jar can be replaced on disk, but only a restart loads it, and Voice Chat only reads plugins at client start.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$mod = "$root\build\localapi-1.0.0.jar"
$plugins = @(
    "$root\plugins\voice-ref\build\localapi-voice-1.0.0.jar",
    "$root\plugins\captions\build\localapi-captions-1.0.0.jar"
)
foreach ($f in @($mod) + $plugins) { if (-not (Test-Path $f)) { throw "missing $f, run the build.ps1 files first" } }

$pdir = "$Instance\config\localapi\plugins"
New-Item -ItemType Directory -Force $pdir | Out-Null
Copy-Item $mod "$Instance\mods\localapi-1.0.0.jar" -Force

$file = "$Instance\config\localapi\plugins.json"
$approved = [ordered]@{}
if (Test-Path $file) {
    $old = Get-Content $file -Raw | ConvertFrom-Json
    if ($old.approved) { $old.approved.PSObject.Properties | ForEach-Object { $approved[$_.Name] = $_.Value } }
}
foreach ($p in $plugins) {
    $name = Split-Path $p -Leaf
    Copy-Item $p "$pdir\$name" -Force
    $hash = (Get-FileHash "$pdir\$name" -Algorithm SHA256).Hash.ToLower()
    $approved[$name] = $hash
    Write-Host "installed + approved $name = $hash"
}
$json = [ordered]@{ approved = $approved } | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($file, $json, (New-Object Text.UTF8Encoding $false))