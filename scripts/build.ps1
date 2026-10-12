param([switch]$SkipNative, [switch]$SkipTests, [switch]$Release)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.gradle-home'
if ($SkipTests -and !$SkipNative) {
    throw 'Use -SkipTests -SkipNative together to reuse the existing audio helper without running its native tests.'
}
$versionMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $taskRoot 'build.gradle') -Raw), "(?m)^\s*version\s*=\s*'([A-Za-z0-9][A-Za-z0-9._-]*)'")
if (!$versionMatch.Success) { throw 'Could not read the release version from build.gradle.' }
$taskVersion = $versionMatch.Groups[1].Value
$outputDirectory = if ($Release) { Join-Path 'dist' $taskVersion } else { 'dist' }
function Find-Jdk([int]$major) {
    $candidates = @()
    $configured = [Environment]::GetEnvironmentVariable("JAVA${major}_HOME")
    if ($configured) { $candidates += $configured }
    $candidates += Join-Path $env:ProgramFiles "Java\jdk-$major"
    $local = Join-Path $taskRoot ".tools/jdk$major"
    if (Test-Path -LiteralPath $local) {
        $candidates += Get-ChildItem -LiteralPath $local -Directory | ForEach-Object { $_.FullName }
    }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin/java.exe'
        if (!(Test-Path -LiteralPath $java)) { continue }
        $version = & $java -version 2>&1 | Out-String
        if ($version -match "version `"$major\.") { return $candidate }
    }
    throw "JDK $major not found. Set JAVA${major}_HOME to your JDK directory."
}
$jdk17 = Find-Jdk 17
$jdk21 = Find-Jdk 21
Push-Location $taskRoot
try {
    if (!$SkipNative) { & ./native-audio/windows/build.ps1 }
    $env:JAVA_HOME = $jdk17
    if (!$SkipTests) {
        & ./gradlew.bat test --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Shared tests failed.' }
    }
    # assemble omits check/test dependencies. Forge's jar task finalizes with reobfJar;
    # NeoForge's ordinary jar is already the production artifact.
    $loaderTask = if ($SkipTests) { 'assemble' } else { 'build' }
    & ./forge-1.20.1/gradlew.bat -p forge-1.20.1 $loaderTask --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Forge build failed.' }
    $env:JAVA_HOME = $jdk21
    & ./neoforge-1.21.1/gradlew.bat -p neoforge-1.21.1 $loaderTask --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'NeoForge build failed.' }
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    # Exact names prevent older builds or development jars from entering the release.
    $artifacts = @("forge-1.20.1/build/libs/wacamera-forge-1.20.1-$taskVersion.jar",
                   "neoforge-1.21.1/build/libs/wacamera-neoforge-1.21.1-$taskVersion.jar")
    $copiedArtifacts = foreach ($artifact in $artifacts) {
        $destination = Join-Path $outputDirectory (Split-Path -Leaf $artifact)
        Copy-Item -LiteralPath $artifact -Destination $destination
        $destination
    }
    Copy-Item -LiteralPath 'docs/usage-ja.md' -Destination (Join-Path $outputDirectory 'README-ja.md')
    Copy-Item -LiteralPath 'docs/testing-ja.md' -Destination (Join-Path $outputDirectory 'TESTING-ja.md')
    if (Test-Path -LiteralPath 'docs/screenshots') {
        $screenshotsDirectory = Join-Path $outputDirectory 'screenshots'
        New-Item -ItemType Directory -Force -Path $screenshotsDirectory | Out-Null
        Copy-Item -Path 'docs/screenshots/*.png' -Destination $screenshotsDirectory
    }
    Copy-Item -LiteralPath 'THIRD_PARTY.md' -Destination (Join-Path $outputDirectory 'THIRD_PARTY.md')
    Copy-Item -LiteralPath 'LICENSE' -Destination (Join-Path $outputDirectory 'LICENSE')
    if ($Release) {
        $releaseFiles = @(
            'docs/curseforge-description-en.md',
            'docs/curseforge-description-ja.md',
            'docs/curseforge-description-bilingual.md',
            "docs/release-$taskVersion-changelog.md",
            'docs/curseforge-upload-ja.md',
            'assets/branding/wacamera-logo.png',
            'assets/branding/logo-prompt.txt'
        )
        foreach ($releaseFile in $releaseFiles) {
            Copy-Item -LiteralPath $releaseFile -Destination (Join-Path $outputDirectory (Split-Path -Leaf $releaseFile))
        }
    }
    $hashes = Get-FileHash -LiteralPath $copiedArtifacts -Algorithm SHA256
    $hashes | ForEach-Object { "$($_.Hash.ToLowerInvariant())  $(Split-Path -Leaf $_.Path)" } | Set-Content -LiteralPath (Join-Path $outputDirectory 'SHA256SUMS.txt') -Encoding ascii
    $hashes
} finally { Pop-Location }
