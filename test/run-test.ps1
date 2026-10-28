# Run after .\build.ps1. Uses the same JDK and dependencies as the mod.
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-test.ps1"
if ($LASTEXITCODE) { throw 'test build failed' }
. "$PSScriptRoot\..\env.ps1"
$core = "$PSScriptRoot\..\build\classes"
$classes = "$PSScriptRoot\work\cls"
# Registry bootstrap needs runtime dependencies beyond the mod's compile classpath.
$runtime = @(Get-ChildItem "$L\io\netty" -Recurse -Filter '*-4.2.16.Final.jar' | Select-Object -ExpandProperty FullName)
$runtime += "$L\com\google\guava\failureaccess\1.0.3\failureaccess-1.0.3.jar"
# Font class verification in the headless widget harness needs Minecraft's ICU runtime.
$runtime += "$L\com\ibm\icu\icu4j\78.3\icu4j-78.3.jar"
$testCP = "$classes;$core;$CP;" + ($runtime -join ';')
& "$JDK\bin\java" -cp $testCP localapi.LiveDataHarness
if ($LASTEXITCODE) { throw 'live-data checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.ProgressDataHarness
if ($LASTEXITCODE) { throw 'advancement/statistics checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.SessionEventsHarness
if ($LASTEXITCODE) { throw 'session/stream checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.ScreenWidgetsHarness
if ($LASTEXITCODE) { throw 'screen-widget checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.RecipeDataHarness
if ($LASTEXITCODE) { throw 'recipe-book checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.HudWidgetsHarness
if ($LASTEXITCODE) { throw 'HUD-widget checks failed' }
& "$JDK\bin\java" -cp $testCP localapi.RegressionHarness
if ($LASTEXITCODE) { throw 'regression checks failed' }
& "$JDK\bin\java" -cp $testCP harness.Harness "$PSScriptRoot\work\root" 'none' '0'
if ($LASTEXITCODE) { throw 'plugin harness failed' }
