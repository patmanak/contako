[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [switch]$SkipBuild,
    [string]$ArtifactDirectory,
    [string]$MergedManifestPath,
    [string]$LegalInventoryPath,
    [string]$VerificationMetadataPath,
    [string]$OutputDirectory,
    [string]$OsvDatabase
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$appRoot = Join-Path $RepositoryRoot 'app'
$gradle = Join-Path $appRoot 'gradlew.bat'
if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) { $ArtifactDirectory = Join-Path $appRoot 'build\outputs' }
if ([string]::IsNullOrWhiteSpace($LegalInventoryPath)) { $LegalInventoryPath = Join-Path $appRoot 'legal\runtime-artifact-licenses.json' }
if ([string]::IsNullOrWhiteSpace($VerificationMetadataPath)) { $VerificationMetadataPath = Join-Path $appRoot 'gradle\verification-metadata.xml' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $appRoot 'build\reports\release-check' }

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$Failure) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Executable @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw $Failure }
    return @($output)
}

function Find-AndroidTool([string]$Name) {
    $sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    $properties = Join-Path $appRoot 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $line) { $sdkCandidates += (($line -replace '^sdk\.dir=', '') -replace '\\\\', '\') }
    }
    foreach ($sdk in @($sdkCandidates | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
        $root = Join-Path ([IO.Path]::GetFullPath($sdk)) 'build-tools'
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $fileName = if ($Name -eq 'apksigner') { 'apksigner.bat' } else { "$Name.exe" }
        $tool = Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name -Descending |
            ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName $fileName) -ErrorAction SilentlyContinue } |
            Select-Object -First 1
        if ($null -ne $tool) { return $tool.FullName }
    }
    throw "ANDROID_TOOL_NOT_FOUND:$Name"
}

function Get-OnlyArtifact([string]$Extension) {
    $files = @(Get-ChildItem -LiteralPath $ArtifactDirectory -Recurse -File -Filter "*.$Extension")
    if ($Extension -eq 'apk') { $files = @($files | Where-Object { $_.Name -match 'release' -and $_.Name -notmatch 'androidTest' }) }
    if ($files.Count -ne 1) { throw "RELEASE_${Extension}_NOT_UNIQUE:$($files.Count)" }
    return $files[0].FullName
}

function Get-MergedManifest {
    if (-not [string]::IsNullOrWhiteSpace($MergedManifestPath)) {
        if (-not (Test-Path -LiteralPath $MergedManifestPath -PathType Leaf)) { throw 'MERGED_MANIFEST_NOT_FOUND' }
        return [IO.Path]::GetFullPath($MergedManifestPath)
    }
    $root = Join-Path $appRoot 'build\intermediates\merged_manifests\release'
    $files = @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter 'AndroidManifest.xml' | Sort-Object FullName)
    if ($files.Count -eq 0) { throw 'MERGED_MANIFEST_NOT_FOUND' }
    return $files[$files.Count - 1].FullName
}

function Assert-MergedManifest([string]$Path) {
    [xml]$xml = Get-Content -LiteralPath $Path -Raw
    $ns = [Xml.XmlNamespaceManager]::new($xml.NameTable)
    $ns.AddNamespace('android', 'http://schemas.android.com/apk/res/android')
    $application = $xml.SelectSingleNode('/manifest/application')
    foreach ($pair in @(
        @('allowBackup', 'false'), @('usesCleartextTraffic', 'false'),
        @('fullBackupContent', '@xml/backup_rules'),
        @('dataExtractionRules', '@xml/data_extraction_rules')
    )) {
        $value = $application.GetAttribute($pair[0], $ns.LookupNamespace('android'))
        if ($value -ne $pair[1]) { throw "MERGED_MANIFEST_POLICY:$($pair[0])" }
    }
    $exported = @($xml.SelectNodes('/manifest/application/*[@android:exported="true"]', $ns))
    foreach ($component in $exported) {
        $name = $component.GetAttribute('name', $ns.LookupNamespace('android'))
        $permission = $component.GetAttribute('permission', $ns.LookupNamespace('android'))
        $allowed =
            ($component.LocalName -eq 'activity' -and $name -eq 'com.patmanak.contako.MainActivity') -or
            ($component.LocalName -eq 'service' -and $permission -in @('android.permission.BIND_ACCOUNT_AUTHENTICATOR', 'android.permission.BIND_SYNC_ADAPTER')) -or
            ($component.LocalName -eq 'receiver' -and $name -eq 'androidx.profileinstaller.ProfileInstallReceiver' -and $permission -eq 'android.permission.DUMP')
        if (-not $allowed) { throw "EXPORTED_COMPONENT_FORBIDDEN:$name" }
    }
}

function Assert-PackagedPolicy([string]$Aapt2, [string]$Apk) {
    $manifest = (Invoke-Checked $Aapt2 @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $Apk) 'PACKAGED_MANIFEST_DUMP_FAILED') -join "`n"
    foreach ($required in @('allowBackup.*false', 'usesCleartextTraffic.*false', 'fullBackupContent.*@', 'dataExtractionRules.*@')) {
        if ($manifest -notmatch $required) { throw "PACKAGED_MANIFEST_POLICY:$required" }
    }
    if ($manifest -match 'debuggable.*true') { throw 'PACKAGED_MANIFEST_POLICY:debuggable' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Apk)
    try { $xmlPaths = @($archive.Entries | Where-Object { $_.FullName -match '^res/[^/]+\.xml$' } | ForEach-Object FullName) } finally { $archive.Dispose() }
    $packagedXml = [ordered]@{ backup_rules = $null; data_extraction_rules = $null }
    foreach ($path in $xmlPaths) {
        try { $dump = (Invoke-Checked $Aapt2 @('dump', 'xmltree', '--file', $path, $Apk) "PACKAGED_XML_DUMP_FAILED:$path") -join "`n" }
        catch { Assert-Archive $Apk; throw }
        if ($dump -match '^E: full-backup-content') { $packagedXml.backup_rules = $dump }
        if ($dump -match '^E: data-extraction-rules') { $packagedXml.data_extraction_rules = $dump }
    }
    foreach ($resource in $packagedXml.Keys) {
        if ([string]::IsNullOrWhiteSpace($packagedXml[$resource])) { throw "PACKAGED_XML_MISSING:$resource" }
    }
    foreach ($domain in @('root', 'file', 'database', 'sharedpref', 'external')) {
        if ($packagedXml.backup_rules -notmatch "domain.*$domain" -or $packagedXml.backup_rules -notmatch 'path.*\.') {
            throw "PACKAGED_LEGACY_BACKUP_EXCLUSION_MISSING:$domain"
        }
    }
    foreach ($section in @('cloud-backup', 'device-transfer')) {
        if ($packagedXml.data_extraction_rules -notmatch $section) { throw "PACKAGED_TRANSFER_SECTION_MISSING:$section" }
    }
    foreach ($domain in @('root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref')) {
        if ($packagedXml.data_extraction_rules -notmatch "domain.*$domain") { throw "PACKAGED_TRANSFER_EXCLUSION_MISSING:$domain" }
    }
    [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'packaged-manifest.txt'), $manifest + "`n", [Text.UTF8Encoding]::new($false))
    foreach ($resource in $packagedXml.Keys) {
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "packaged-$resource.txt"), $packagedXml[$resource] + "`n", [Text.UTF8Encoding]::new($false))
    }
    $permissions = (Invoke-Checked $Aapt2 @('dump', 'permissions', $Apk) 'PACKAGED_PERMISSIONS_DUMP_FAILED') -join "`n"
    $expected = @(
        'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.READ_CONTACTS', 'android.permission.WRITE_CONTACTS',
        'android.permission.READ_SYNC_SETTINGS', 'android.permission.WRITE_SYNC_SETTINGS'
    )
    foreach ($permission in $expected) {
        if ($permissions -notmatch [regex]::Escape($permission)) { throw "PACKAGED_PERMISSION_MISSING:$permission" }
    }
    foreach ($permission in @('android.permission.QUERY_ALL_PACKAGES', 'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.REQUEST_INSTALL_PACKAGES')) {
        if ($permissions -match [regex]::Escape($permission)) { throw "PACKAGED_PERMISSION_FORBIDDEN:$permission" }
    }
}

function Assert-Archive([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entries = @($archive.Entries | Sort-Object FullName)
        if (@($entries | Where-Object { $_.FullName -match '(?i)(^|/)(test|androidTest|fixtures?)(/|$)|\.(jks|keystore|p12|pem|key)$|secrets?\.(properties|json)$' }).Count -ne 0) {
            throw 'PACKAGED_SECRET_OR_TEST_FILE'
        }
        if (@($entries | Where-Object { $_.FullName -match '^(?:base/)?assets/legal/NOTICE\.txt$' }).Count -ne 1) {
            throw 'PACKAGED_LEGAL_NOTICE_MISSING'
        }
        foreach ($entry in $entries) {
            if ($entry.Length -gt 128MB) { throw 'PACKAGED_ENTRY_OVERSIZED' }
            if ($entry.Length -eq 0 -or $entry.Length -gt 16MB) { continue }
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $text = [Text.Encoding]::GetEncoding(28591).GetString($memory.ToArray())
                foreach ($pattern in @(
                    '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                    '(?i)Authorization\s*:\s*(?:Bearer|Basic)\s+[A-Za-z0-9+/_.=-]{12,}',
                    '(?i)(?:storePassword|keyPassword)\s*[:=]\s*[^\s]+',
                    'synthetic-(?:access|refresh)-token'
                )) { if ($text -match $pattern) { throw 'PACKAGED_SECRET_STRING' } }
            } finally { $memory.Dispose(); $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function Assert-SourceSurfaces {
    $releaseConfigRoot = Join-Path $appRoot 'build/generated/source/buildConfig/release'
    $releaseConfigs = @(Get-ChildItem -LiteralPath $releaseConfigRoot -Recurse -File -Filter BuildConfig.java -ErrorAction SilentlyContinue)
    if ($releaseConfigs.Count -ne 1) { throw 'RELEASE_BUILD_CONFIG_NOT_UNIQUE' }
    $releaseConfig = Get-Content -LiteralPath $releaseConfigs[0].FullName -Raw
    foreach ($flag in @('DEBUG', 'SANITIZED_DIAGNOSTICS', 'SYNC_DIAGNOSTICS')) {
        $declarations = [regex]::Matches($releaseConfig, ('public\s+static\s+final\s+boolean\s+' + $flag + '\s*=\s*(true|false)\s*;'))
        if ($declarations.Count -ne 1 -or $declarations[0].Groups[1].Value -ne 'false') {
            throw "RELEASE_DIAGNOSTIC_FLAG:$flag"
        }
    }
    $tracked = @(Invoke-Checked 'git' @('-C', $RepositoryRoot, 'ls-files') 'GIT_FILE_INVENTORY_FAILED')
    foreach ($file in $tracked) {
        if ($file -match '(?i)\.(jks|keystore|p12|pem)$|(^|/)secrets?\.(properties|json)$') { throw "TRACKED_SECRET_FILE:$file" }
    }
    $production = Join-Path $appRoot 'src\main'
    $approvedSinkPath = [IO.Path]::GetFullPath((Join-Path $production 'java/com/patmanak/contako/diagnostics/SanitizedDiagnosticLog.kt'))
    foreach ($file in @(Get-ChildItem -LiteralPath $production -Recurse -File)) {
        if ($file.Extension -notin @('.kt', '.java', '.xml', '.txt', '.json')) { continue }
        $text = Get-Content -LiteralPath $file.FullName -Raw
        $scanText = $text
        if ($file.FullName -eq $approvedSinkPath) {
            $call = 'android.util.Log.i("ContakoDiagnostic", renderDiagnostic(event))'
            $guard = 'if (BuildConfig.SANITIZED_DIAGNOSTICS || (BuildConfig.SYNC_DIAGNOSTICS && isSyncDiagnostic(event)))'
            $pattern = [regex]::Escape($guard) + '\s*\{\s*runCatching\s*\{\s*' + [regex]::Escape($call) + '\s*\}\s*\}'
            if ([regex]::Matches($text, [regex]::Escape($call)).Count -ne 1 -or
                -not $text.Contains('fun write(event: SanitizedDiagnosticEvent)') -or
                -not [regex]::IsMatch($text, $pattern)) { throw 'DIAGNOSTIC_LOG_GUARD_INVALID' }
            $scanText = $text.Replace($call, '')
        }
        if ($scanText -match 'android\.util\.Log|Timber\.|println\(|printStackTrace\(|HttpLoggingInterceptor.*BODY') {
            throw "PRODUCTION_LOG_SURFACE:$($file.Name)"
        }
        if ($text -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?i)(?:storePassword|keyPassword)\s*[:=]') {
            throw "PRODUCTION_SECRET_SURFACE:$($file.Name)"
        }
    }
}

function Get-VerificationSummary {
    [xml]$metadata = Get-Content -LiteralPath $VerificationMetadataPath -Raw
    $components = @($metadata.SelectNodes('//*[local-name()="component"]')).Count
    $sha256 = @($metadata.SelectNodes('//*[local-name()="sha256"]')).Count
    $weak = @($metadata.SelectNodes('//*[local-name()="sha1" or local-name()="md5"]')).Count
    if ($components -eq 0 -or $sha256 -lt $components -or $weak -ne 0) { throw 'VERIFICATION_METADATA_INVALID' }
    return [pscustomobject]@{ Components = $components; Sha256 = $sha256 }
}

function Get-ResolvedCoordinates([string[]]$Lines) {
    $coordinates = foreach ($line in $Lines) {
        $match = [regex]::Match($line, '([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\-]+)(?:\s+->\s+(?:([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):)?([A-Za-z0-9_.+\-]+))?')
        if (-not $match.Success) { continue }
        if ($match.Groups[4].Success) {
            "$($match.Groups[4].Value):$($match.Groups[5].Value):$($match.Groups[6].Value)"
            continue
        }
        $version = if ($match.Groups[6].Success) { $match.Groups[6].Value } else { $match.Groups[3].Value }
        "$($match.Groups[1].Value):$($match.Groups[2].Value):$version"
    }
    return @($coordinates | Sort-Object -Unique)
}

function Assert-LegalAndWriteSbom([string[]]$Resolved) {
    $legal = Get-Content -LiteralPath $LegalInventoryPath -Raw | ConvertFrom-Json
    $descriptorPaths = @('app/build.gradle.kts', 'app/settings.gradle.kts', 'app/gradle.properties',
        'app/gradle/libs.versions.toml', 'app/gradle/wrapper/gradle-wrapper.properties', 'app/gradle/verification-metadata.xml',
        'app/native/golib/go.mod', 'app/native/golib/go.sum', 'app/native/golib/dependencies.go',
        'app/native/golib/build.ps1', 'app/native/golib/package-aar.py', 'app/native/golib/golib.pom')
    if (@($legal.dependencyDescriptors).Count -ne $descriptorPaths.Count) { throw 'LEGAL_DESCRIPTOR_SET_INVALID' }
    foreach ($relative in $descriptorPaths) {
        $entries = @($legal.dependencyDescriptors | Where-Object { $_.file -ceq $relative })
        if ($entries.Count -ne 1) { throw 'LEGAL_DESCRIPTOR_SET_INVALID' }
        $actualHash = (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $relative) -Algorithm SHA256).Hash
        if ($entries[0].sha256 -ne $actualHash) { throw "LEGAL_DESCRIPTOR_STALE:$relative" }
    }
    $artifacts = @($legal.artifacts)
    $metadataOnly = @($legal.metadataOnlyComponents)
    if ($artifacts.Count -eq 0 -or $legal.artifactCount -ne $artifacts.Count) { throw 'LEGAL_INVENTORY_INVALID' }
    $covered = @($artifacts | ForEach-Object { $_.coordinates }) + @($metadataOnly | ForEach-Object { $_.coordinates })
    $missing = @($Resolved | Where-Object { $_ -notin $covered -and $_ -notmatch '^com\.android\.tools\.build:gradle:' })
    if ($missing.Count -ne 0) { throw "LEGAL_COMPONENT_UNCOVERED:$($missing[0])" }
    foreach ($artifact in $artifacts) {
        if ([string]::IsNullOrWhiteSpace($artifact.sha256) -or @($artifact.licenses).Count -eq 0 -or $null -eq $artifact.provenance) {
            throw "LEGAL_ARTIFACT_INCOMPLETE:$($artifact.coordinates)"
        }
    }
    $components = @($artifacts | Sort-Object coordinates, sha256 | ForEach-Object {
        $parts = $_.coordinates.Split(':')
        $purl = "pkg:maven/$($parts[0])/$($parts[1])@$($parts[2])"
        [ordered]@{
            type = 'library'; 'bom-ref' = $purl; group = $parts[0]; name = $parts[1]; version = $parts[2]
            hashes = @([ordered]@{ alg = 'SHA-256'; content = $_.sha256.ToLowerInvariant() })
            licenses = @($_.licenses | Sort-Object spdx | ForEach-Object { [ordered]@{ license = [ordered]@{ id = $_.spdx } } })
            purl = $purl
        }
    })
    $buildFile = Get-Content -LiteralPath (Join-Path $appRoot 'build.gradle.kts') -Raw
    $versionMatch = [regex]::Match($buildFile, 'val\s+contakoReleaseVersion\s*=\s*"([^"]+)"')
    if (-not $versionMatch.Success) { throw 'APPLICATION_VERSION_NOT_FOUND' }
    $sbom = [ordered]@{
        bomFormat = 'CycloneDX'; specVersion = '1.5'; version = 1
        metadata = [ordered]@{ component = [ordered]@{ type = 'application'; name = 'Contako'; version = $versionMatch.Groups[1].Value; licenses = @([ordered]@{ license = [ordered]@{ id = 'GPL-3.0-or-later' } }) } }
        components = $components
    }
    [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
    $sbomPath = Join-Path $OutputDirectory 'contako-release.cdx.json'
    [IO.File]::WriteAllText($sbomPath, (($sbom | ConvertTo-Json -Depth 12) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{ Path = $sbomPath; Components = $components.Count; Sha256 = (Get-FileHash -LiteralPath $sbomPath -Algorithm SHA256).Hash }
}

if (-not $SkipBuild) {
    $arguments = @('--offline', '--dependency-verification', 'strict', '--console=plain', '--no-parallel', '-p', $appRoot)
    Invoke-Checked $gradle @($arguments + @('assembleRelease')) 'ASSEMBLE_RELEASE_FAILED' | Out-Null
    Invoke-Checked $gradle @($arguments + @('bundleRelease')) 'BUNDLE_RELEASE_FAILED' | Out-Null
}
$aapt2 = Find-AndroidTool 'aapt2'
$apk = Get-OnlyArtifact 'apk'
$aab = Get-OnlyArtifact 'aab'
$manifestPath = Get-MergedManifest
Assert-MergedManifest $manifestPath
Assert-PackagedPolicy $aapt2 $apk
Assert-Archive $apk
Assert-Archive $aab
Assert-SourceSurfaces
$mapping = Join-Path $appRoot 'build\outputs\mapping\release\mapping.txt'
$usage = Join-Path $appRoot 'build\outputs\mapping\release\usage.txt'
if (-not (Test-Path -LiteralPath $mapping) -or (Get-Item -LiteralPath $mapping).Length -eq 0 -or
    -not (Test-Path -LiteralPath $usage)) { throw 'MINIFY_EVIDENCE_MISSING' }
$verification = Get-VerificationSummary
$verificationHashBefore = (Get-FileHash -LiteralPath $VerificationMetadataPath -Algorithm SHA256).Hash
$dependencyLines = Invoke-Checked $gradle @('--offline', '--dependency-verification', 'strict', '--console=plain', '-p', $appRoot, 'dependencies', '--configuration', 'releaseRuntimeClasspath') 'DEPENDENCY_RESOLUTION_FAILED'
if ((Get-FileHash -LiteralPath $VerificationMetadataPath -Algorithm SHA256).Hash -ne $verificationHashBefore) { throw 'VERIFICATION_METADATA_CHANGED' }
$resolved = Get-ResolvedCoordinates $dependencyLines
if ($resolved.Count -eq 0) { throw 'RESOLVED_DEPENDENCIES_EMPTY' }
$sbom = Assert-LegalAndWriteSbom $resolved

$osvStatus = 'BLOCKED_OFFLINE_DATABASE_OR_TOOL_UNAVAILABLE'
$osv = Get-Command 'osv-scanner' -ErrorAction SilentlyContinue
if ($null -ne $osv -and -not [string]::IsNullOrWhiteSpace($OsvDatabase) -and (Test-Path -LiteralPath $OsvDatabase)) {
    Invoke-Checked $osv.Source @('scan', '--offline', '--vulnerability-database', $OsvDatabase, '--sbom', $sbom.Path) 'OSV_SCAN_FINDING_OR_FAILURE' | Out-Null
    $osvStatus = 'PASS_OFFLINE'
}
$apksigner = Find-AndroidTool 'apksigner'
$old = $ErrorActionPreference
try { $ErrorActionPreference = 'Continue'; & $apksigner verify $apk 2>&1 | Out-Null; $signed = $LASTEXITCODE -eq 0 } finally { $ErrorActionPreference = $old }
if ($signed) { throw 'UNAPPROVED_SIGNED_RELEASE_ARTIFACT' }

$result = [ordered]@{
    Result = 'BLOCKED'; AutomatedArtifactChecks = 'PASS'; Signing = 'BLOCKED_EXTERNAL_UNSIGNED'
    Osv = $osvStatus; ApkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    AabSha256 = (Get-FileHash -LiteralPath $aab -Algorithm SHA256).Hash
    SbomSha256 = $sbom.Sha256; SbomComponents = $sbom.Components
    DependencyComponents = $resolved.Count; VerificationComponents = $verification.Components
    VerificationSha256Entries = $verification.Sha256; ExternalNetwork = 'NONE'
}
$reportPath = Join-Path $OutputDirectory 'gate-result.json'
[IO.File]::WriteAllText($reportPath, (($result | ConvertTo-Json -Depth 4) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
[pscustomobject]$result
