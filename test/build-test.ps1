$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. ..\env.ps1
$core = "$PSScriptRoot\..\build\classes"
$w = "$PSScriptRoot\work"; Remove-Item $w -Recurse -Force -ErrorAction SilentlyContinue
$cls = "$w\cls"; New-Item -ItemType Directory $cls,"$w\root\plugins" -Force | Out-Null
$src = Get-ChildItem src -Recurse -Filter *.java | % FullName
& "$JDK\bin\javac" --release 25 -proc:none -encoding UTF-8 -cp "$core;$CP" -d $cls $src
if ($LASTEXITCODE) { throw "javac failed" }

function Make-Jar($name, $manifest, [string[]]$classes, [string[]]$extraFiles) {
    $d = "$w\jar-$name"; New-Item -ItemType Directory $d -Force | Out-Null
    foreach ($c in $classes) { $dest = "$d\$([IO.Path]::GetDirectoryName($c))"; New-Item -ItemType Directory $dest -Force | Out-Null; Copy-Item "$cls\$c" $dest }
    if ($manifest) { Set-Content "$d\localapi.plugin.json" $manifest -Encoding utf8 }
    foreach ($f in $extraFiles) { Set-Content "$d\$f" "{}" }
    & "$JDK\bin\jar" --create --file "$w\root\plugins\$name.jar" -C $d .
}
$all = 'testplugin\Echo.class','testplugin\Echo$1.class' | ? { Test-Path "$cls\$_" }
$echoFiles = Get-ChildItem "$cls\testplugin" -Filter *.class | ? { $_.Name -ne 'Missing.class' } | % { "testplugin\$($_.Name)" }
$m = '{"id":"echo","name":"Echo test","version":"1","apiVersion":1,"entrypoint":"testplugin.Echo"}'
Make-Jar 'echo' $m $echoFiles
Make-Jar 'nomanifest' $null $echoFiles
Make-Jar 'partial' '{"id":"partial","version":"1"}' $echoFiles
Make-Jar 'mixin' '{"id":"mixin","version":"1","apiVersion":1,"entrypoint":"testplugin.Echo"}' $echoFiles @('x.mixins.json')
Make-Jar 'dep' '{"id":"dep","version":"1","apiVersion":1,"entrypoint":"testplugin.Echo","requires":["nope"]}' $echoFiles
# missingclass: needs testplugin.Missing at load time, but the jar lacks it. Entry class refs it in a field type.
$mcDir = "$w\mc"; New-Item -ItemType Directory $mcDir -Force | Out-Null
Set-Content "$mcDir\Gone.java" 'package gone; public class Gone { public String x() { return ""; } }'
Set-Content "$mcDir\Entry.java" 'package gone; import localapi.api.*; public class Entry implements LocalApiPlugin { Gone g = new Gone(); public void register(PluginContext c) {} }'
& "$JDK\bin\javac" --release 25 -proc:none -cp "$core;$CP" -d $cls "$mcDir\Gone.java" "$mcDir\Entry.java"
Make-Jar 'missingclass' '{"id":"missingclass","version":"1","apiVersion":1,"entrypoint":"gone.Entry"}' @('gone\Entry.class')
# Fixtures must be loaded from their jars, not accidentally from the harness classpath.
# In particular Missing/Gone are deliberately absent from the corresponding jars.
Remove-Item "$cls\testplugin","$cls\gone" -Recurse -Force
Get-ChildItem "$w\root\plugins" | % Name


