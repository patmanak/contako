$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gate = Join-Path $PSScriptRoot 'check-release.ps1'
# Load only the trusted parser definition; invoking the whole gate would inspect artifacts.
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($gate, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'RELEASE_GATE_PARSE_ERROR' }
$definition = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-ResolvedCoordinates'
}, $true)
. ([scriptblock]::Create($definition.Extent.Text))
$coordinates = @(Get-ResolvedCoordinates @(
    '+--- example.group:plain:1.0',
    '+--- example.group:version:1.0 -> 2.0 (*)',
    '+--- me.proton.crypto:android-golib:2.9.0-2 -> com.patmanak.contako.crypto:android-golib:2.10.0-2-go1.27.1',
    '+--- example.group:plain:1.0 (*)'
))
$expectedCoordinates = @('com.patmanak.contako.crypto:android-golib:2.10.0-2-go1.27.1',
    'example.group:plain:1.0', 'example.group:version:2.0')
if (($coordinates -join '|') -cne ($expectedCoordinates -join '|')) { throw 'RESOLVED_COORDINATES_REGRESSION' }
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
    } finally {
        $resolvedRoot = [IO.Path]::GetFullPath($root)
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedRoot) -notmatch '^contako-release-check-[a-f0-9]{32}$') {
            throw 'UNSAFE_FIXTURE_CLEANUP_PATH'
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
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

[pscustomobject]@{ Result = 'PASS'; NegativeContracts = 3; CoordinateParser = 'PASS'; ExternalNetwork = 'NONE' }
