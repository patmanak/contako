param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{7,40}$')]
    [string]$ExpectedHead,
    [string]$JavaHome = '',
    [string]$GradleUserHome = '',
    [switch]$AuditOnly,
    [switch]$ValidateOnly,
    [switch]$StageOnly
)

$ErrorActionPreference = 'Stop'
$appDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $appDirectory '..'))
$gradle = Join-Path $appDirectory 'gradlew.bat'
$initScript = Join-Path $PSScriptRoot 'gradle\runtime-artifacts.init.gradle'
$publicManifest = Join-Path $appDirectory 'legal\runtime-artifact-licenses.json'
$publicNotices = Join-Path $appDirectory 'legal\THIRD_PARTY_NOTICES.md'
$licenseDirectory = Join-Path $appDirectory 'legal\licenses'

if (-not (Test-Path -LiteralPath $gradle)) { throw 'GRADLE_WRAPPER_NOT_FOUND' }
if (-not (Test-Path -LiteralPath $initScript)) { throw 'ARTIFACT_INIT_SCRIPT_NOT_FOUND' }
if ($AuditOnly -and $ValidateOnly) { throw 'MODE_CONFLICT' }
if ($StageOnly -and ($AuditOnly -or $ValidateOnly)) { throw 'MODE_CONFLICT' }
if ($StageOnly) {
    # Generate reviewable outputs without replacing the public inventory/notices.
    $stageDirectory = Join-Path $appDirectory 'build\runtime-notices-stage'
    $publicManifest = Join-Path $stageDirectory 'runtime-artifact-licenses.json'
    $publicNotices = Join-Path $stageDirectory 'THIRD_PARTY_NOTICES.md'
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $resolvedJavaHome = [IO.Path]::GetFullPath($JavaHome)
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin\java.exe'))) { throw 'JAVA_HOME_INVALID' }
    $env:JAVA_HOME = $resolvedJavaHome
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME_REQUIRED' }

$resolvedGradleHome = if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
    [IO.Path]::GetFullPath($GradleUserHome)
} elseif (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    [IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
} else {
    [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.gradle'))
}
$moduleCache = Join-Path $resolvedGradleHome 'caches\modules-2\files-2.1'
if (-not (Test-Path -LiteralPath $moduleCache)) { throw 'GRADLE_MODULE_CACHE_NOT_FOUND' }

function Invoke-SanitizedProcess([string]$Executable, [string[]]$Arguments, [string]$FailureCategory) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Executable @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw $FailureCategory }
    return @($output)
}

function Get-XmlChildText($Node, [string]$Name) {
    $child = $Node.SelectSingleNode("./*[local-name()='$Name']")
    if ($null -eq $child) { return '' }
    return $child.InnerText.Trim()
}

function Get-CachedPom([string]$Group, [string]$Module, [string]$Version) {
    $versionDirectory = Join-Path $moduleCache (Join-Path $Group (Join-Path $Module $Version))
    if (-not (Test-Path -LiteralPath $versionDirectory)) { return $null }
    $poms = @(Get-ChildItem -LiteralPath $versionDirectory -Directory | ForEach-Object {
        Get-ChildItem -LiteralPath $_.FullName -Filter '*.pom' -File
    })
    if ($poms.Count -eq 0) { return $null }
    $byHash = @($poms | Group-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
    if ($byHash.Count -ne 1) { throw 'POM_CONTENT_AMBIGUOUS' }
    return $byHash[0].Group | Sort-Object FullName | Select-Object -First 1
}

function ConvertTo-NormalizedLegalText([string]$Text) {
    $lines = @(($Text -replace "`r`n?", "`n") -split "`n", 0, 'SimpleMatch' |
        ForEach-Object { $_.TrimEnd() })
    return (($lines -join "`n").TrimEnd()) + "`n"
}

function Convert-LicenseToSpdx([string]$Name, [string]$Url) {
    $value = "$Name $Url".ToLowerInvariant()
    if ($value -match 'general public license' -and $value -match '(version\s*3|gpl-3)') { return 'GPL-3.0-only' }
    if ($value -match 'apache' -and $value -match '(2\.0|version\s*2)') { return 'Apache-2.0' }
    if ($value -match 'freebsd') { return 'BSD-2-Clause-FreeBSD' }
    if ($value -match 'bsd-3|bsd 3|three-clause') { return 'BSD-3-Clause' }
    if ($value -match '\bmit\b') { return 'MIT' }
    throw 'POM_LICENSE_SPDX_UNMAPPED'
}

function Get-PomAttribution($Project) {
    $organization = $Project.SelectSingleNode('./*[local-name()="organization"]')
    $scm = $Project.SelectSingleNode('./*[local-name()="scm"]')
    $developers = @($Project.SelectNodes('./*[local-name()="developers"]/*[local-name()="developer"]') | ForEach-Object {
        [ordered]@{
            name = Get-XmlChildText $_ 'name'
            organization = Get-XmlChildText $_ 'organization'
            url = Get-XmlChildText $_ 'url'
        }
    })
    return [ordered]@{
        name = Get-XmlChildText $Project 'name'
        url = Get-XmlChildText $Project 'url'
        organization = if ($null -eq $organization) { [ordered]@{ name = ''; url = '' } } else {
            [ordered]@{ name = Get-XmlChildText $organization 'name'; url = Get-XmlChildText $organization 'url' }
        }
        scm = if ($null -eq $scm) { [ordered]@{ url = ''; connection = ''; developerConnection = ''; tag = '' } } else {
            [ordered]@{
                url = Get-XmlChildText $scm 'url'
                connection = Get-XmlChildText $scm 'connection'
                developerConnection = Get-XmlChildText $scm 'developerConnection'
                tag = Get-XmlChildText $scm 'tag'
            }
        }
        developers = $developers
    }
}

function Resolve-PomLicense([string]$Group, [string]$Module, [string]$Version) {
    $visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $chain = [Collections.Generic.List[object]]::new()
    $exactPomAttribution = $null
    $depth = 0
    while ($depth -le 8) {
        $key = "$Group`:$Module`:$Version"
        if (-not $visited.Add($key)) { throw 'POM_PARENT_CYCLE' }
        $pom = Get-CachedPom $Group $Module $Version
        if ($null -eq $pom) { return $null }
        $pomHash = (Get-FileHash -LiteralPath $pom.FullName -Algorithm SHA256).Hash
        $chain.Add([ordered]@{ coordinates = $key; sha256 = $pomHash })
        [xml]$xml = Get-Content -LiteralPath $pom.FullName -Raw
        $project = $xml.SelectSingleNode('/*[local-name()="project"]')
        if ($null -eq $project) { throw 'POM_PROJECT_INVALID' }
        if ($depth -eq 0) { $exactPomAttribution = Get-PomAttribution $project }
        $licenseNodes = @($project.SelectNodes('./*[local-name()="licenses"]/*[local-name()="license"]'))
        if ($licenseNodes.Count -gt 0) {
            $licenses = @($licenseNodes | ForEach-Object {
                $name = Get-XmlChildText $_ 'name'
                $url = Get-XmlChildText $_ 'url'
                if ([string]::IsNullOrWhiteSpace($name) -and [string]::IsNullOrWhiteSpace($url)) {
                    throw 'POM_LICENSE_EMPTY'
                }
                [ordered]@{ spdx = Convert-LicenseToSpdx $name $url; observedName = $name; observedUrl = $url }
            })
            return [pscustomobject]@{
                Licenses = $licenses
                Provenance = [ordered]@{
                    type = if ($depth -eq 0) { 'exact-pom' } else { 'inherited-parent-pom' }
                    licenseSourceCoordinates = $key
                    licenseSourcePomSha256 = $pomHash
                    inheritanceDepth = $depth
                    pomChain = @($chain)
                    exactPomAttribution = $exactPomAttribution
                }
            }
        }
        $parent = $project.SelectSingleNode('./*[local-name()="parent"]')
        if ($null -eq $parent) { return $null }
        $nextGroup = Get-XmlChildText $parent 'groupId'
        $nextModule = Get-XmlChildText $parent 'artifactId'
        $nextVersion = Get-XmlChildText $parent 'version'
        if (@(@($nextGroup, $nextModule, $nextVersion) | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\$\{' }).Count -gt 0) {
            throw 'POM_PARENT_COORDINATES_UNRESOLVED'
        }
        $Group = $nextGroup; $Module = $nextModule; $Version = $nextVersion; $depth++
    }
    throw 'POM_PARENT_DEPTH_EXCEEDED'
}

function Get-EmbeddedLegalEntries([string]$ArtifactPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try { $archive = [IO.Compression.ZipFile]::OpenRead($ArtifactPath) } catch { return @() }
    try {
        $entries = @($archive.Entries | Where-Object {
            $_.Length -gt 0 -and $_.Length -le 2MB -and
            $_.FullName -match '(?i)((^|/)(LICENSES?|LICENCES?|NOTICE|COPYING|COPYRIGHT)(\.(txt|md|html|license))?$|\.(license|licence|notice)$)'
        } | Sort-Object FullName)
        return @($entries | ForEach-Object {
            $entry = $_
            $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8, $true)
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($text.Contains([char]0)) {
                return [pscustomobject]@{ entry = $entry.FullName; invalidText = $true; sha256 = ''; characterCount = 0; text = '' }
            }
            $text = ConvertTo-NormalizedLegalText $text
            $bytes = [Text.Encoding]::UTF8.GetBytes($text)
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') } finally {
                $sha.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length)
            }
            [pscustomobject]@{ entry = $entry.FullName; invalidText = $false; sha256 = $hash; characterCount = $text.Length; text = $text }
        })
    } finally { $archive.Dispose() }
}

function Resolve-EmbeddedLicense([string]$ArtifactPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try { $archive = [IO.Compression.ZipFile]::OpenRead($ArtifactPath) } catch { return $null }
    try {
        $entries = @($archive.Entries | Where-Object {
            $_.Length -gt 0 -and $_.Length -le 2MB -and
            $_.FullName -match '(?i)((^|/)(LICENSE|LICENCE|COPYING)(\.(txt|md|html|license))?$|\.(license|licence)$)'
        } | Sort-Object FullName)
        foreach ($entry in $entries) {
            $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8, $true)
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $normalized = $text.ToLowerInvariant()
            $spdx = if ($normalized -match 'gnu general public license' -and $normalized -match 'version 3') {
                'GPL-3.0-only'
            } elseif ($normalized -match 'apache license' -and $normalized -match 'version 2\.0') {
                'Apache-2.0'
            } elseif ($normalized -match 'permission is hereby granted, free of charge') {
                'MIT'
            } elseif ($normalized -match 'redistribution and use in source and binary forms' -and $normalized -match 'neither the name') {
                'BSD-3-Clause'
            } elseif ($normalized -match 'redistribution and use in source and binary forms') {
                'BSD-2-Clause-FreeBSD'
            } else { $null }
            if ($null -ne $spdx) {
                $bytes = [Text.Encoding]::UTF8.GetBytes($text)
                $sha = [Security.Cryptography.SHA256]::Create()
                try {
                    $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
                } finally { $sha.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
                return [pscustomobject]@{
                    Licenses = @([ordered]@{ spdx = $spdx; observedName = $entry.FullName; observedUrl = '' })
                    Provenance = [ordered]@{ type = 'embedded-license'; entry = $entry.FullName; sha256 = $hash; inheritanceDepth = 0 }
                }
            }
        }
    } finally { $archive.Dispose() }
    return $null
}

function Convert-InventoryLine([string]$Line, [string]$Prefix) {
    $encoded = $Line.Substring($Prefix.Length)
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json
}

Push-Location $repositoryDirectory
try {
    $gitScope = "safe.directory=$($repositoryDirectory.Replace('\', '/'))"
    $head = ((Invoke-SanitizedProcess 'git' @('-c', $gitScope, 'rev-parse', 'HEAD') 'GIT_HEAD_FAILED') -join '').Trim()
    if (-not $head.StartsWith($ExpectedHead, [StringComparison]::OrdinalIgnoreCase)) { throw 'UNEXPECTED_SOURCE_HEAD' }
    $inventoryOutput = Invoke-SanitizedProcess $gradle @(
        '--offline', '--dependency-verification', 'strict', '--console=plain',
        '-p', $appDirectory, '-I', $initScript, 'contakoRuntimeArtifactInventory'
    ) 'RUNTIME_ARTIFACT_INVENTORY_FAILED'

    $artifactRows = @($inventoryOutput | Where-Object { $_ -like 'CONTAKO_ARTIFACT|*' } |
        ForEach-Object { Convert-InventoryLine $_ 'CONTAKO_ARTIFACT|' })
    $componentRows = @($inventoryOutput | Where-Object { $_ -like 'CONTAKO_COMPONENT|*' } |
        ForEach-Object { Convert-InventoryLine $_ 'CONTAKO_COMPONENT|' })
    if ($artifactRows.Count -eq 0 -or $componentRows.Count -eq 0) { throw 'RUNTIME_INVENTORY_EMPTY' }

    $unresolved = [Collections.Generic.List[string]]::new()
    $artifactList = [Collections.Generic.List[object]]::new()
    foreach ($row in $artifactRows) {
        if (-not (Test-Path -LiteralPath $row.filePath)) { throw 'RUNTIME_ARTIFACT_FILE_MISSING' }
        $actualHash = (Get-FileHash -LiteralPath $row.filePath -Algorithm SHA256).Hash
        if ($actualHash -ne $row.sha256) { throw 'RUNTIME_ARTIFACT_HASH_MISMATCH' }
        $license = Resolve-PomLicense $row.group $row.module $row.version
        if ($null -eq $license) { $license = Resolve-EmbeddedLicense $row.filePath }
        if ($null -eq $license) {
            $unresolved.Add("$($row.group):$($row.module):$($row.version):$($row.fileName)")
            continue
        }
        $embeddedLegalEntries = @(Get-EmbeddedLegalEntries $row.filePath)
        $invalidLegalEntries = @($embeddedLegalEntries | Where-Object { $_.invalidText })
        if ($invalidLegalEntries.Count -gt 0) {
            foreach ($invalidEntry in $invalidLegalEntries) {
                $unresolved.Add("$($row.group):$($row.module):$($row.version):$($row.fileName):$($invalidEntry.entry):NON_TEXT_LEGAL_ENTRY")
            }
            continue
        }
        $licenseIds = @($license.Licenses | ForEach-Object { $_.spdx } | Sort-Object -Unique)
        if (@($licenseIds | Where-Object { $_ -in @('MIT', 'BSD-2-Clause-FreeBSD', 'BSD-3-Clause') }).Count -gt 0) {
            $attributionText = ($embeddedLegalEntries | ForEach-Object { $_.text }) -join "`n"
            if ($attributionText -notmatch '(?i)copyright' -or
                $attributionText -notmatch '(?i)(permission is hereby granted|redistribution and use in source and binary forms)') {
                $unresolved.Add("$($row.group):$($row.module):$($row.version):$($row.fileName):COPYRIGHT_NOTICE_MISSING")
                continue
            }
        }
        $publicEmbeddedEntries = @($embeddedLegalEntries | ForEach-Object {
            [ordered]@{ entry = $_.entry; sha256 = $_.sha256; characterCount = $_.characterCount }
        })
        $artifactList.Add([pscustomobject][ordered]@{
            coordinates = "$($row.group):$($row.module):$($row.version)"
            fileName = $row.fileName
            extension = $row.extension
            classifier = $row.classifier
            size = [Int64]$row.size
            sha256 = $actualHash
            packaged = $true
            licenses = @($license.Licenses)
            provenance = $license.Provenance
            embeddedLegalEntries = $publicEmbeddedEntries
            internalLegalEntries = $embeddedLegalEntries
        })
    }
    $artifactList.Sort([Comparison[object]]{
        param($left, $right)
        $coordinateOrder = [StringComparer]::Ordinal.Compare([string]$left.coordinates, [string]$right.coordinates)
        if ($coordinateOrder -ne 0) { return $coordinateOrder }
        return [StringComparer]::Ordinal.Compare([string]$left.fileName, [string]$right.fileName)
    })
    $artifacts = @($artifactList)

    $artifactKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($artifact in $artifacts) { [void]$artifactKeys.Add($artifact.coordinates) }
    $metadataOnlyList = [Collections.Generic.List[object]]::new()
    foreach ($component in $componentRows) {
        $coordinates = "$($component.group):$($component.module):$($component.version)"
        if (-not $artifactKeys.Contains($coordinates)) {
            $metadataOnlyList.Add([pscustomobject][ordered]@{
                coordinates = $coordinates; packaged = $false; classification = 'metadata-only-or-platform'
            })
        }
    }
    $metadataOnlyList.Sort([Comparison[object]]{
        param($left, $right)
        return [StringComparer]::Ordinal.Compare([string]$left.coordinates, [string]$right.coordinates)
    })
    $metadataOnly = @($metadataOnlyList)

    if ($unresolved.Count -gt 0) {
        [pscustomobject]@{ Result = 'BLOCKED'; UnresolvedArtifactCount = $unresolved.Count; UnresolvedArtifacts = @($unresolved | Sort-Object) }
        throw 'RUNTIME_ARTIFACT_LICENSE_UNRESOLVED'
    }

    $spdxIds = @($artifacts | ForEach-Object { @($_.licenses) | ForEach-Object { $_.spdx } } | Sort-Object -Unique)
    $licenseTextProvenance = @{
        'GPL-3.0-only' = [ordered]@{ type = 'frozen-canonical-license-text'; reference = 'https://www.gnu.org/licenses/gpl-3.0.txt' }
        'Apache-2.0' = [ordered]@{ type = 'frozen-canonical-license-text'; reference = 'https://www.apache.org/licenses/LICENSE-2.0.txt' }
        'MIT' = [ordered]@{ type = 'exact-runtime-artifact-entry'; reference = 'org.jsoup:jsoup:1.14.2/META-INF/LICENSE' }
        'BSD-2-Clause-FreeBSD' = [ordered]@{ type = 'exact-runtime-artifact-entry'; reference = 'com.googlecode.ez-vcard:ez-vcard:0.11.3/ezvcard/ez-vcard.license' }
    }
    $licenseFiles = @($spdxIds | ForEach-Object {
        $path = Join-Path $licenseDirectory "$_.txt"
        if (-not $AuditOnly -and -not (Test-Path -LiteralPath $path)) { throw 'LEGAL_LICENSE_TEXT_MISSING' }
        if ($null -eq $licenseTextProvenance[$_]) { throw 'LEGAL_LICENSE_TEXT_PROVENANCE_MISSING' }
        [ordered]@{
            spdx = $_
            file = "licenses/$_.txt"
            sha256 = if (Test-Path -LiteralPath $path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash } else { '' }
            provenance = $licenseTextProvenance[$_]
        }
    })
    $descriptorPaths = @(
        'app/build.gradle.kts',
        'app/settings.gradle.kts',
        'app/gradle.properties',
        'app/gradle/libs.versions.toml',
        'app/gradle/wrapper/gradle-wrapper.properties',
        'app/gradle/verification-metadata.xml'
    )
    $descriptorHashes = @($descriptorPaths | ForEach-Object {
        $absolute = Join-Path $repositoryDirectory $_
        if (-not (Test-Path -LiteralPath $absolute)) { throw 'DEPENDENCY_DESCRIPTOR_MISSING' }
        [ordered]@{ file = $_; sha256 = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash }
    })
    $publicArtifacts = @($artifacts | ForEach-Object {
        [ordered]@{
            coordinates = $_.coordinates
            fileName = $_.fileName
            extension = $_.extension
            classifier = $_.classifier
            size = $_.size
            sha256 = $_.sha256
            packaged = $_.packaged
            licenses = $_.licenses
            provenance = $_.provenance
            embeddedLegalEntries = $_.embeddedLegalEntries
        }
    })
    $embeddedLegalEntryCount = @($artifacts | ForEach-Object { @($_.internalLegalEntries) }).Count
    $manifest = [ordered]@{
        schemaVersion = 1
        configuration = 'releaseRuntimeClasspath'
        generationMode = 'strict-offline-cache-only'
        dependencyDescriptors = $descriptorHashes
        artifactCount = $artifacts.Count
        metadataOnlyComponentCount = $metadataOnly.Count
        embeddedLegalEntryCount = $embeddedLegalEntryCount
        spdxIdentifiers = $spdxIds
        licenseTexts = $licenseFiles
        artifacts = $publicArtifacts
        metadataOnlyComponents = $metadataOnly
        completeness = if ($AuditOnly) { 'AUDIT_ONLY' } else { 'COMPLETE' }
    }
    $json = (($manifest | ConvertTo-Json -Depth 12) -replace "`r`n?", "`n") + "`n"

    if ($AuditOnly) {
        [pscustomobject]@{ Result = 'AUDIT_PASS'; ArtifactCount = $artifacts.Count; MetadataOnlyComponentCount = $metadataOnly.Count; EmbeddedLegalEntryCount = $embeddedLegalEntryCount; SpdxIdentifiers = $spdxIds; UnresolvedArtifactCount = 0 }
        exit 0
    }

    $noticeLines = @(
        '# Third-Party Runtime Notices', '',
        'This file is generated from the exact `releaseRuntimeClasspath` artifact manifest.',
        'It MUST be linked from the in-app Credits page. It does not imply endorsement by any upstream project.',
        'This is the resolved runtime inventory before shrinking, not an APK reachability report.', '',
        'Manifest: `runtime-artifact-licenses.json`', '',
        '## Included license texts', ''
    )
    foreach ($licenseFile in $licenseFiles) { $noticeLines += ('- `{0}`: [{1}]({1})' -f $licenseFile.spdx, $licenseFile.file) }
    $noticeLines += @('', '## Resolved runtime artifacts before shrinking', '')
    foreach ($artifact in $artifacts) {
        $noticeLines += ('- `{0}` / `{1}` / {2}' -f $artifact.coordinates, $artifact.fileName, (@($artifact.licenses.spdx) -join ', '))
    }
    $noticeLines += @('', '## Preserved embedded licenses, notices, and copyrights', '')
    foreach ($artifact in $artifacts) {
        foreach ($legalEntry in @($artifact.internalLegalEntries)) {
            $noticeLines += @(
                ('### `{0}` / `{1}`' -f $artifact.coordinates, $artifact.fileName), '',
                ('Embedded entry: `{0}`' -f $legalEntry.entry),
                ('SHA-256: `{0}`' -f $legalEntry.sha256), ''
            )
            foreach ($line in @($legalEntry.text -split "`n", 0, 'SimpleMatch')) {
                $noticeLines += if ($line.Length -eq 0) { '' } else { "    $line" }
            }
            $noticeLines += ''
        }
    }
    $noticesText = (($noticeLines -join "`n").TrimEnd()) + "`n"

    if ($ValidateOnly) {
        if (-not (Test-Path -LiteralPath $publicManifest) -or -not (Test-Path -LiteralPath $publicNotices)) { throw 'PUBLIC_LEGAL_OUTPUT_MISSING' }
        # PowerShell versions differ in JSON indentation. Re-serialize with this
        # runtime, preserving every descriptor/artifact value and array order.
        $storedJson = (([IO.File]::ReadAllText($publicManifest) | ConvertFrom-Json | ConvertTo-Json -Depth 12) -replace "`r`n?", "`n") + "`n"
        if ($storedJson -ne $json) { throw 'PUBLIC_LICENSE_MANIFEST_DRIFT' }
        if (([IO.File]::ReadAllText($publicNotices) -replace "`r`n?", "`n") -ne $noticesText) { throw 'PUBLIC_NOTICES_DRIFT' }
    } else {
        [void](New-Item -ItemType Directory -Path (Split-Path $publicManifest) -Force)
        $utf8NoBom = [Text.UTF8Encoding]::new($false)
        [IO.File]::WriteAllText($publicManifest, $json, $utf8NoBom)
        [IO.File]::WriteAllText($publicNotices, $noticesText, $utf8NoBom)
    }

    [pscustomobject]@{
        Result = 'PASS'
        NetworkMode = 'OFFLINE'
        DependencyVerificationMode = 'STRICT'
        ArtifactCount = $artifacts.Count
        MetadataOnlyComponentCount = $metadataOnly.Count
        EmbeddedLegalEntryCount = $embeddedLegalEntryCount
        SpdxIdentifierCount = $spdxIds.Count
        UnresolvedArtifactCount = 0
        ArtifactLicenseEvidence = 'COMPLETE'
        PublicManifestSha256 = (Get-FileHash -LiteralPath $publicManifest -Algorithm SHA256).Hash
        PublicNoticesSha256 = (Get-FileHash -LiteralPath $publicNotices -Algorithm SHA256).Hash
        ValidationMode = if ($ValidateOnly) { 'VALIDATE_ONLY' } elseif ($StageOnly) { 'STAGE_ONLY' } else { 'GENERATE' }
    }
} finally { Pop-Location }
