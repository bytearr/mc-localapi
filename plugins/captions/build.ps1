$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\env.ps1
$core = "$PSScriptRoot\..\..\build\classes"
if (-not (Test-Path $core)) { throw "build the mod first (..\..\build.ps1)" }

# whisper-jni 1.7.1 and HallucinationFilter come from the voicetranscript 1.5.0 jar (nested jar + one class).
# Extracted once into ..\..\libs\vt (git-ignored), so the old mod can stay disabled afterwards.
$vt = "$PSScriptRoot\..\..\libs\vt"
$whisperJar = "$vt\META-INF\jars\whisper-jni-1.7.1.jar"
$hfClass = "$vt\com\voicetranscript\stt\HallucinationFilter.class"
if (-not ((Test-Path $whisperJar) -and (Test-Path $hfClass))) {
    $inst = Join-Path $env:USERPROFILE 'AppData\Roaming\ATLauncher\instances\Fabulouslypenis263\mods'
    $src = Get-ChildItem $inst -Filter 'voicetranscript-1.5.0*' | Select-Object -First 1
    if (-not $src) { throw "voicetranscript-1.5.0 jar not found in $inst and not extracted in $vt" }
    New-Item -ItemType Directory $vt -Force | Out-Null
    Push-Location $vt
    & "$JDK\bin\jar" xf $src.FullName META-INF/jars com/voicetranscript/stt/HallucinationFilter.class
    Pop-Location
}

$out = "$PSScriptRoot\build\classes"
Remove-Item "$PSScriptRoot\build" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out -Force | Out-Null
$src = Get-ChildItem src -Recurse -Filter *.java | % FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp "$core;$CP;$whisperJar;$vt" -d $out $src
if ($LASTEXITCODE) { throw "javac failed" }

# pack: whisper-jni (classes + Windows DLLs only), the filter class, the manifest
$tmp = "$PSScriptRoot\build\w"
New-Item -ItemType Directory $tmp -Force | Out-Null
Push-Location $tmp
& "$JDK\bin\jar" xf $whisperJar io win-amd64
Pop-Location
Get-ChildItem $tmp -Recurse -Filter .gitkeep | Remove-Item
Copy-Item "$tmp\io" $out -Recurse
Copy-Item "$tmp\win-amd64" $out -Recurse
New-Item -ItemType Directory "$out\com\voicetranscript\stt" -Force | Out-Null
Copy-Item $hfClass "$out\com\voicetranscript\stt\"
Copy-Item localapi.plugin.json $out
$jar = "$PSScriptRoot\build\localapi-captions-1.0.0.jar"
& "$JDK\bin\jar" --create --file $jar -C $out .
if ($LASTEXITCODE) { throw "jar failed" }
Write-Host "built $jar ($([math]::Round((Get-Item $jar).Length/1MB,1)) MB)"
