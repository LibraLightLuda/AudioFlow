#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Publish,
    [switch]$LiveChecks,
    [string]$KeyStore = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$KeyAlias = 'androiddebugkey',
    [string]$CertificateSha256 = '53d3a031547f6384a3efa67dbfbb0a0d4f640d8e5541c328f0fce76e8e0e37d9'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
$previousLive = $env:AUDIOFLOW_LIVE
try {
    if (git status --porcelain) { throw 'Commit or separately preserve working changes before creating release artifacts.' }
    if (-not $env:AUDIOFLOW_KEYSTORE_PASSWORD) { throw 'Set AUDIOFLOW_KEYSTORE_PASSWORD locally; never save it in this repository.' }
    if (-not (Test-Path -LiteralPath $KeyStore)) { throw 'Existing signing key not found. Do not generate a replacement key.' }
    $gradle = Get-Content -Raw -LiteralPath app/build.gradle
    $version = [regex]::Match($gradle,"versionName '([0-9.]+)'").Groups[1].Value
    if (-not $version) { throw 'Version metadata is missing.' }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $toolDir = Join-Path $sdk 'build-tools\36.0.0'
    $env:ANDROID_HOME = $sdk
    $env:AUDIOFLOW_LIVE = if ($LiveChecks) { '1' } else { '0' }
    & .\gradlew.bat testDebugUnitTest assembleRelease lintRelease assembleDebugAndroidTest *> build/release.log
    if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed. See build/release.log.' }
    New-Item -ItemType Directory -Path dist -Force | Out-Null
    $apk = Join-Path dist "AudioFlow-$version.apk"
    $aligned = Join-Path build "AudioFlow-$version-aligned.apk"
    & "$toolDir\zipalign.exe" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'ZIP alignment failed.' }
    & "$toolDir\apksigner.bat" sign --ks $KeyStore --ks-key-alias $KeyAlias --ks-pass env:AUDIOFLOW_KEYSTORE_PASSWORD --key-pass env:AUDIOFLOW_KEYSTORE_PASSWORD --out $apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $certificate = & "$toolDir\apksigner.bat" verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -notmatch [regex]::Escape($CertificateSha256)) { throw 'Signing certificate differs from the installed release.' }
    & "$toolDir\zipalign.exe" -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'Alignment verification failed.' }
    $head = git rev-parse HEAD
    $source = Join-Path dist "AudioFlow-$version-source.zip"
    git archive --format=zip "--prefix=AudioFlow-$version/" "--output=$source" HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed.' }
    $names = git ls-files
    if ($names -match '\.(keystore|jks)$|local\.properties$') { throw 'Signing or local configuration file is tracked.' }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
    Write-Output "Built AudioFlow $version, commit $head, APK SHA256 $hash"
    if ($Publish) {
        gh release create "v$version" $apk $source --target $head --title "AudioFlow $version" --notes-file app/src/main/assets/whats-new.md --latest
        if ($LASTEXITCODE -ne 0) { throw 'Release publication failed; do not overwrite an existing release blindly.' }
        $release = Invoke-RestMethod "https://api.github.com/repos/LibraLightLuda/AudioFlow/releases/latest"
        if ($release.tag_name -ne "v$version" -or $release.draft -or $release.prerelease) { throw 'Latest stable release mismatch.' }
        foreach ($file in @($apk,$source)) {
            $name = Split-Path -Leaf $file
            $asset = $release.assets | Where-Object name -eq $name
            $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash
            if ($asset.state -ne 'uploaded' -or $asset.digest -ine "sha256:$localHash") { throw 'Published asset digest mismatch.' }
            $download = Join-Path build "verified-$name"
            Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $download
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $download).Hash -ne $localHash) { throw 'Anonymous download mismatch.' }
        }
        $tag = gh api "repos/LibraLightLuda/AudioFlow/git/ref/tags/v$version" --jq .object.sha
        if ($tag -ne $head) { throw 'Published tag does not match source.' }
        Write-Output $release.html_url
    }
} finally { $env:AUDIOFLOW_LIVE = $previousLive; Pop-Location }
