$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gate = Join-Path $PSScriptRoot 'check-release.ps1'
$outputs = Join-Path $repositoryRoot 'app\build\outputs'
$merged = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'app\build\intermediates\merged_manifests\release') -Recurse -Filter AndroidManifest.xml -File | Select-Object -Last 1
if ($null -eq $merged) { throw 'RELEASE_FIXTURE_NOT_BUILT' }

function Assert-Failure([string]$Name, [scriptblock]$Mutate, [string]$Expected) {
    $root = Join-Path ([IO.Path]::GetTempPath()) ('contako-release-check-' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $root)
    try {
        $artifactRoot = Join-Path $root 'outputs'
        [void](New-Item -ItemType Directory -Path (Join-Path $artifactRoot 'apk\release') -Force)
        [void](New-Item -ItemType Directory -Path (Join-Path $artifactRoot 'bundle\release') -Force)
        $apk = Get-ChildItem -LiteralPath (Join-Path $outputs 'apk\release') -Filter '*.apk' -File | Select-Object -First 1
        $aab = Get-ChildItem -LiteralPath (Join-Path $outputs 'bundle\release') -Filter '*.aab' -File | Select-Object -First 1
        Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $artifactRoot 'apk\release')
        Copy-Item -LiteralPath $aab.FullName -Destination (Join-Path $artifactRoot 'bundle\release')
        $manifest = Join-Path $root 'AndroidManifest.xml'
        Copy-Item -LiteralPath $merged.FullName -Destination $manifest
        $legal = Join-Path $root 'runtime-artifact-licenses.json'
        Copy-Item -LiteralPath (Join-Path $repositoryRoot 'app\legal\runtime-artifact-licenses.json') -Destination $legal
        & $Mutate $artifactRoot $manifest $legal
        try {
            & $gate -RepositoryRoot $repositoryRoot -SkipBuild -ArtifactDirectory $artifactRoot `
                -MergedManifestPath $manifest -LegalInventoryPath $legal -OutputDirectory (Join-Path $root 'report') | Out-Null
            throw "EXPECTED_FAILURE_NOT_RAISED:$Name"
        } catch {
            if ($_.Exception.Message -notlike "*$Expected*") { throw "WRONG_FAILURE:${Name}:$($_.Exception.Message)" }
        }
    } finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
}

Assert-Failure 'manifest-cleartext' {
    param($artifacts, $manifest, $legal)
    $text = Get-Content -LiteralPath $manifest -Raw
    [IO.File]::WriteAllText($manifest, $text.Replace('android:usesCleartextTraffic="false"', 'android:usesCleartextTraffic="true"'))
} 'MERGED_MANIFEST_POLICY:usesCleartextTraffic'

Assert-Failure 'packaged-keystore' {
    param($artifacts, $manifest, $legal)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apk = Get-ChildItem -LiteralPath $artifacts -Recurse -Filter '*release*.apk' -File | Select-Object -First 1
    $archive = [IO.Compression.ZipFile]::Open($apk.FullName, [IO.Compression.ZipArchiveMode]::Update)
    try { [void]$archive.CreateEntry('assets/release-key.jks') } finally { $archive.Dispose() }
} 'PACKAGED_SECRET_OR_TEST_FILE'

Assert-Failure 'legal-inventory' {
    param($artifacts, $manifest, $legal)
    $json = Get-Content -LiteralPath $legal -Raw | ConvertFrom-Json
    $json.artifacts = @($json.artifacts | Select-Object -Skip 1)
    $json.artifactCount = $json.artifacts.Count
    [IO.File]::WriteAllText($legal, ($json | ConvertTo-Json -Depth 20))
} 'LEGAL_COMPONENT_UNCOVERED:'

[pscustomobject]@{ Result = 'PASS'; NegativeContracts = 3; ExternalNetwork = 'NONE' }
