$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\..\env.ps1

$webSrc = Resolve-Path "$PSScriptRoot\..\..\..\localapi-console"
$vendor = "$PSScriptRoot\vendor"
New-Item -ItemType Directory $vendor -Force | Out-Null

$js = "$vendor\skinview3d.bundle.js"
$font = "$vendor\monocraft.ttf"
if (-not (Test-Path $js)) {
    Invoke-WebRequest -Uri "https://cdn.jsdelivr.net/npm/skinview3d@3.4.1/bundles/skinview3d.bundle.js" -OutFile $js
}
if (-not (Test-Path $font)) {
    Invoke-WebRequest -Uri "https://mc.sebay.cc/fonts/monocraft.ttf" -OutFile $font
}
$jsBytes = [System.IO.File]::ReadAllBytes($js)
$fontBytes = [System.IO.File]::ReadAllBytes($font)
if ($jsBytes.Length -lt 1000) { throw "skinview3d bundle is too small" }
$jsText = [System.Text.Encoding]::UTF8.GetString($jsBytes)
if ($jsText -notmatch "SkinViewer") { throw "skinview3d bundle does not look like the viewer" }
if ($fontBytes.Length -lt 1000 -or $fontBytes[0] -ne 0 -or $fontBytes[1] -ne 1) { throw "monocraft.ttf is not a ttf" }

$apiOut = "$PSScriptRoot\build\api"
$out = "$PSScriptRoot\build\classes"
Remove-Item "$PSScriptRoot\build" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $apiOut, "$out\web" -Force | Out-Null

$apiSrc = Get-ChildItem "$PSScriptRoot\..\..\src\main\java\localapi\api" -Filter *.java | ForEach-Object FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp $CP -d $apiOut $apiSrc
if ($LASTEXITCODE) { throw "api javac failed" }

$src = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp "$apiOut;$CP" -d $out $src
if ($LASTEXITCODE) { throw "javac failed" }

$utf8 = [System.Text.UTF8Encoding]::new($false)
$html = [System.IO.File]::ReadAllText("$webSrc\index.html", $utf8)
$html = $html.Replace("https://cdn.jsdelivr.net/npm/skinview3d@3.4.1/bundles/skinview3d.bundle.js", "skinview3d.bundle.js")
if ($html.Contains("cdn.jsdelivr.net")) { throw "index.html still points at the cdn" }
$css = [System.IO.File]::ReadAllText("$webSrc\styles.css", $utf8)
$css = $css.Replace("https://mc.sebay.cc/fonts/monocraft.ttf", "monocraft.ttf")
if ($css.Contains("mc.sebay.cc")) { throw "styles.css still points at the remote font" }
[System.IO.File]::WriteAllText("$out\web\index.html", $html, $utf8)
[System.IO.File]::WriteAllText("$out\web\styles.css", $css, $utf8)
Copy-Item "$webSrc\app.js" "$out\web\app.js"
Copy-Item $js "$out\web\skinview3d.bundle.js"
Copy-Item $font "$out\web\monocraft.ttf"
Copy-Item localapi.plugin.json $out

$jar = "$PSScriptRoot\build\localapi-console-1.0.0.jar"
& "$JDK\bin\jar" --create --file $jar -C $out .
if ($LASTEXITCODE) { throw "jar failed" }
Write-Host "built $jar"
