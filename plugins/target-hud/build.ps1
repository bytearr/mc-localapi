$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\env.ps1
$core = "$PSScriptRoot\..\..\build\classes"
if (-not (Test-Path $core)) { throw 'Build the core first (compile-only API dependency).' }
$out = "$PSScriptRoot\build\classes"
Remove-Item "$PSScriptRoot\build" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out -Force | Out-Null
$src = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp "$core;$CP" -d $out $src
if ($LASTEXITCODE) { throw 'javac failed' }
Copy-Item localapi.plugin.json $out
$jar = "$PSScriptRoot\build\localapi-target-hud-1.0.1.jar"
& "$JDK\bin\jar" --create --file $jar -C $out .
if ($LASTEXITCODE) { throw 'jar failed' }
Write-Host "built $jar"
