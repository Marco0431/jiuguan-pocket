[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$GradleHome,
    [switch]$SkipChecks
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolsRoot = Join-Path (Split-Path $projectRoot -Parent) '.android-tools'

function Find-ToolDirectory([string]$Explicit, [string]$EnvironmentValue, [string]$Pattern, [string]$RequiredFile) {
    $candidates = @($Explicit, $EnvironmentValue)
    if (Test-Path -LiteralPath $toolsRoot) {
        $candidates += Get-ChildItem -LiteralPath $toolsRoot -Directory |
            Where-Object { $_.Name -like $Pattern } |
            Sort-Object Name -Descending |
            ForEach-Object { $_.FullName }
        if ($Pattern -eq 'jdk*') {
            $nestedJdkRoot = Join-Path $toolsRoot 'jdk'
            if (Test-Path -LiteralPath $nestedJdkRoot) {
                $candidates += Get-ChildItem -LiteralPath $nestedJdkRoot -Directory |
                    Sort-Object Name -Descending |
                    ForEach-Object { $_.FullName }
            }
        }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate $RequiredFile))) { return $candidate }
    }
    return $null
}

$jdk = Find-ToolDirectory $JavaHome $env:JAVA_HOME 'jdk*' 'bin\java.exe'
if (-not $jdk) {
    throw '需要 JDK 17 或 21。请设置 JAVA_HOME，或将 JDK 解压到相邻的 .android-tools 目录，再运行本脚本。'
}
$sdk = Find-ToolDirectory $AndroidSdk $env:ANDROID_HOME 'android-sdk*' 'platforms'
if (-not $sdk) { $sdk = Find-ToolDirectory $AndroidSdk $env:ANDROID_SDK_ROOT '*sdk*' 'platforms' }
if (-not $sdk) {
    throw '需要 Android SDK (platforms;android-35、build-tools;35.0.0)。请传入 -AndroidSdk 或设置 ANDROID_HOME。'
}
$gradle = Find-ToolDirectory $GradleHome $env:GRADLE_HOME 'gradle-*' 'bin\gradle.bat'

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = (Join-Path $jdk 'bin') + [IO.Path]::PathSeparator + $env:PATH
$sdkProperty = $sdk.Replace('\', '/')
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$sdkProperty`n", [Text.UTF8Encoding]::new($false))

if ($gradle) {
    $gradleCommand = Join-Path $gradle 'bin\gradle.bat'
} elseif (Test-Path -LiteralPath (Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar')) {
    $gradleCommand = Join-Path $projectRoot 'gradlew.bat'
} else {
    throw '需要 Gradle 8.9。请将其解压到相邻的 .android-tools 目录，或传入 -GradleHome。'
}

Push-Location $projectRoot
try {
    $tasks = @('assembleDebug')
    if (-not $SkipChecks) { $tasks += @('testDebugUnitTest', 'lintDebug') }
    & $gradleCommand @tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
    $dist = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $apk = Join-Path $dist 'jiuguan-pocket.apk'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
    Write-Host "已生成：$apk"
    $pluginNames = @('安装酒馆插件.cmd', 'install-server-plugin.cmd', 'install-server-plugin.mjs', 'SERVER-PLUGIN-INSTALL.md')
    $pluginFiles = @($pluginNames | ForEach-Object { Join-Path $projectRoot "tools\$_" })
    $pluginFiles += Join-Path $projectRoot 'server-plugin\jiuguan-relay'
    $pluginFiles += Join-Path $projectRoot 'LICENSE'
    $pluginZip = Join-Path $dist 'jiuguan-server-plugin.zip'
    Compress-Archive -LiteralPath $pluginFiles -DestinationPath $pluginZip -Force
    Write-Host "已生成酒馆插件安装包：$pluginZip"
} finally {
    Pop-Location
}
