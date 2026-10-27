# Run after .\build.ps1. Uses the same JDK and dependencies as the mod.
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-test.ps1"
if ($LASTEXITCODE) { throw 'test build failed' }
. "$PSScriptRoot\..\env.ps1"
$core = "$PSScriptRoot\..\build\classes"
$classes = "$PSScriptRoot\work\cls"
& "$JDK\bin\java" -cp "$classes;$core;$CP" localapi.RegressionHarness
if ($LASTEXITCODE) { throw 'regression checks failed' }
& "$JDK\bin\java" -cp "$classes;$core;$CP" harness.Harness "$PSScriptRoot\work\root" 'none' '0'
if ($LASTEXITCODE) { throw 'plugin harness failed' }
