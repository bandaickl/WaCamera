param([string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (!(Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio C++ Build Tools and Windows SDK are required.' }
$vsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vsPath) { throw 'Visual Studio C++ tools were not found.' }
$devShell = Join-Path $vsPath 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll'
Import-Module $devShell
Enter-VsDevShell -VsInstallPath $vsPath -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$output = Join-Path $taskRoot 'build'
New-Item -ItemType Directory -Force -Path $output | Out-Null
Push-Location $output
try {
    & cl.exe /nologo /std:c++17 /EHsc /W4 /O2 /MT (Join-Path $taskRoot 'timeline_test.cpp') /Fe:timeline-test.exe
    if ($LASTEXITCODE -ne 0) { throw "Audio timeline test build failed: $LASTEXITCODE" }
    & ./timeline-test.exe
    if ($LASTEXITCODE -ne 0) { throw "Audio timeline tests failed: $LASTEXITCODE" }
    & cl.exe /nologo /std:c++17 /EHsc /W4 /O2 /MT /DUNICODE /D_UNICODE (Join-Path $taskRoot 'main.cpp') /Fe:wacamera-audio.exe /link mmdevapi.lib ole32.lib runtimeobject.lib
    if ($LASTEXITCODE -ne 0) { throw "Audio helper build failed: $LASTEXITCODE" }
} finally { Pop-Location }
