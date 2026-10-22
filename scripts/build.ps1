param([switch]$SkipNative, [switch]$SkipTests, [switch]$Release)
# SkipNative is retained for compatibility. Native helpers are never built or packaged here.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.gradle-home'
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
function Assert-NoNativeBinaries([string]$artifact) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot $artifact))
    try {
        foreach ($entry in $archive.Entries) {
            if (!$entry.Name) { continue }
            if ($entry.FullName -match '(?i)\.(exe|dll|com|scr|so|dylib)$') {
                throw "Native binary found in ${artifact}: $($entry.FullName)"
            }
            # Check contents too, so a renamed executable cannot enter a release.
            $stream = $entry.Open()
            try {
                $header = New-Object byte[] 4
                $count = 0
                while ($count -lt $header.Length) {
                    $read = $stream.Read($header, $count, $header.Length - $count)
                    if ($read -eq 0) { break }
                    $count += $read
                }
                $magic = [BitConverter]::ToString($header)
                if (($count -ge 2 -and $header[0] -eq 0x4D -and $header[1] -eq 0x5A) -or
                    ($count -eq 4 -and $magic -in @('7F-45-4C-46', 'FE-ED-FA-CE', 'CE-FA-ED-FE', 'FE-ED-FA-CF', 'CF-FA-ED-FE'))) {
                    throw "Native executable content found in ${artifact}: $($entry.FullName)"
                }
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    Write-Host "Verified: no native executables in $artifact"
}
Push-Location $taskRoot
try {
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
    foreach ($artifact in $artifacts) { Assert-NoNativeBinaries $artifact }
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
