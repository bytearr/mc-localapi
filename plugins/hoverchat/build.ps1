$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\env.ps1
$core = "$PSScriptRoot\..\..\build\classes"
if (-not (Test-Path $core)) { throw "build the mod first (..\..\build.ps1)" }
$out = "$PSScriptRoot\build\classes"
Remove-Item "$PSScriptRoot\build" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out -Force | Out-Null
$src = Get-ChildItem src -Recurse -Filter *.java | % FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp "$core;$CP" -d $out $src
if ($LASTEXITCODE) { throw "javac failed" }
Copy-Item localapi.plugin.json $out
$jar = "$PSScriptRoot\build\localapi-hoverchat-1.0.0.jar"
& "$JDK\bin\jar" --create --file $jar -C $out .
if ($LASTEXITCODE) { throw "jar failed" }
Write-Host "built $jar"
