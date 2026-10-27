# Headless check of the captions pipeline with a real model (about 2 minutes: whisper medium on CPU is slow).
# Needs: ..\build.ps1 done, and a speech file test\work\speech.wav (16 kHz mono; SAPI can make one, see below).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot\..
. ..\..\env.ps1
$model = Join-Path $env:USERPROFILE 'AppData\Roaming\ATLauncher\instances\Fabulouslypenis263\config\voicetranscript\models\ggml-medium-q5_0.bin'
New-Item -ItemType Directory -Force test\work\cls | Out-Null
if (-not (Test-Path test\work\speech.wav)) {
    Add-Type -AssemblyName System.Speech
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $s.SelectVoice('Microsoft David Desktop')
    $fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $s.SetOutputToWaveFile("$PWD\test\work\speech.wav", $fmt)
    $s.Speak('That is the problem. I would like to order a coffee, please.')
    $s.Dispose()
}
& "$JDK\bin\javac.exe" --release 25 -proc:none -encoding UTF-8 -cp "build\classes;..\..\build\classes;$CP" -d test\work\cls test\SelfTest.java
if ($LASTEXITCODE) { throw 'javac failed' }
& "$JDK\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "test\work\cls;build\classes;..\..\build\classes;$CP" captions.SelfTest $model "$PWD\test\work\speech.wav"
