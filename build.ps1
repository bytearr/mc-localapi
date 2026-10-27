$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. .\env.ps1
$out = "$PSScriptRoot\build\classes"
Remove-Item "$PSScriptRoot\build" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out -Force | Out-Null
$src = Get-ChildItem src\main\java -Recurse -Filter *.java | % FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -Xlint:-options -cp $CP -d $out $src
if ($LASTEXITCODE) { throw "javac failed" }
Copy-Item src\main\resources\* $out -Recurse -Force
$jar = "$PSScriptRoot\build\localapi-1.0.0.jar"
& "$JDK\bin\jar" --create --file $jar -C $out .
if ($LASTEXITCODE) { throw "jar failed" }
Write-Host "built $jar"
