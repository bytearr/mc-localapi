$script:JDK = Join-Path $env:USERPROFILE '.vscode\extensions\redhat.java-1.57.0-win32-x64\jre\25.0.4.1-win32-x86_64'
$L = Join-Path $env:USERPROFILE 'AppData\Roaming\ATLauncher\libraries'
$script:CLIENT = "$L\net\minecraft\client\26.3\client-26.3.jar"
$script:CPJARS = @(
  $script:CLIENT,
  "$L\com\mojang\datafixerupper\10.0.21\datafixerupper-10.0.21.jar",
  "$L\com\mojang\brigadier\1.3.11\brigadier-1.3.11.jar",
  "$L\com\google\code\gson\gson\2.14.0\gson-2.14.0.jar",
  "$L\com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar",
  "$L\io\netty\netty-buffer\4.2.16.Final\netty-buffer-4.2.16.Final.jar",
  "$L\io\netty\netty-common\4.2.16.Final\netty-common-4.2.16.Final.jar",
  "$L\it\unimi\dsi\fastutil\8.5.18\fastutil-8.5.18.jar",
  "$L\org\joml\joml\1.10.9\joml-1.10.9.jar",
  "$L\org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar",
  "$L\net\fabricmc\sponge-mixin\0.17.4+mixin.0.8.7\sponge-mixin-0.17.4+mixin.0.8.7.jar",
  "$L\net\fabricmc\fabric-loader\0.19.5\fabric-loader-0.19.5.jar",
  "$L\com\mojang\authlib\10.0.77\authlib-10.0.77.jar",
  "$L\com\mojang\logging\1.7.12\logging-1.7.12.jar",
  "$L\com\mojang\jtracy\1.14.38\jtracy-1.14.38.jar",
  "$L\org\apache\commons\commons-lang3\3.20.0\commons-lang3-3.20.0.jar"
) + (Get-ChildItem "$PSScriptRoot\libs\fapi\META-INF\jars" -Filter *.jar | Select -Expand FullName)
$script:CP = ($script:CPJARS -join ';')


# --- compile-only additions for the plugin system / UI (approved by the user, not bundled into any jar) ---
# Voice Chat API: ONLY de/maxhenkel/voicechat/api/** extracted from voicechat-fabric-2.6.26+26.3.jar (the nested api jar is empty).
$script:CPJARS += "$PSScriptRoot\libs\voicechat-api"
# Mod Menu API (ModMenuApi, ConfigScreenFactory) for the settings entry. Only Mod Menu reads that entrypoint.
$script:CPJARS += Join-Path $env:USERPROFILE 'AppData\Roaming\ATLauncher\instances\Fabulouslypenis263\mods\modmenu-21.0.0.jar'
$script:CP = ($script:CPJARS -join ';')
