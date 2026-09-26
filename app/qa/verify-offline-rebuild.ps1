param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{7,40}$')]
    [string]$ExpectedHead,
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$appDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $appDirectory '..'))
$gradle = Join-Path $appDirectory 'gradlew.bat'
$verificationMetadata = Join-Path $appDirectory 'gradle\verification-metadata.xml'

if (-not (Test-Path -LiteralPath $gradle)) { throw 'GRADLE_WRAPPER_NOT_FOUND' }
if (-not (Test-Path -LiteralPath $verificationMetadata)) {
    throw 'DEPENDENCY_VERIFICATION_METADATA_NOT_FOUND'
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $resolvedJavaHome = [IO.Path]::GetFullPath($JavaHome)
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin\java.exe'))) {
        throw 'JAVA_HOME_INVALID'
    }
    $env:JAVA_HOME = $resolvedJavaHome
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME_REQUIRED' }

function Invoke-SanitizedProcess(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$FailureCategory
) {
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

Push-Location $repositoryDirectory
try {
    $gitScope = "safe.directory=$($repositoryDirectory.Replace('\', '/'))"
    $head = ((Invoke-SanitizedProcess 'git' @('-c', $gitScope, 'rev-parse', 'HEAD') 'GIT_HEAD_FAILED') -join '').Trim()
    if (-not $head.StartsWith($ExpectedHead, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'UNEXPECTED_SOURCE_HEAD'
    }
    $statusOutput = Invoke-SanitizedProcess 'git' @('-c', $gitScope, 'status', '--porcelain') 'GIT_STATUS_FAILED'
    $workingTreeClean = @($statusOutput).Count -eq 0

    $metadataHashBefore = (Get-FileHash -LiteralPath $verificationMetadata -Algorithm SHA256).Hash
    [xml]$metadata = Get-Content -LiteralPath $verificationMetadata -Raw
    $componentCount = @($metadata.SelectNodes('//*[local-name()="component"]')).Count
    $sha256Count = @($metadata.SelectNodes('//*[local-name()="sha256"]')).Count
    $weakChecksumCount = @($metadata.SelectNodes('//*[local-name()="sha1" or local-name()="md5"]')).Count
    if ($componentCount -eq 0 -or $sha256Count -eq 0 -or $weakChecksumCount -ne 0) {
        throw 'DEPENDENCY_VERIFICATION_METADATA_CONTENT_INVALID'
    }
    $strictArguments = @(
        '--offline',
        '--dependency-verification',
        'strict',
        '--console=plain',
        '--no-parallel',
        '-p',
        $appDirectory
    )
    # Keep KSP variant generation and lint analysis in separate Gradle processes. AGP lint reads
    # release-generated sources even for lintDebug, and a combined task graph can race those files.
    @('assembleRelease', 'assembleDebug', 'testDebugUnitTest', 'lintDebug') | ForEach-Object {
        Invoke-SanitizedProcess $gradle @($strictArguments + @($_)) 'STRICT_OFFLINE_REBUILD_FAILED' |
            Out-Null
    }
    $metadataHashAfter = (Get-FileHash -LiteralPath $verificationMetadata -Algorithm SHA256).Hash
    if ($metadataHashBefore -ne $metadataHashAfter) {
        throw 'DEPENDENCY_VERIFICATION_METADATA_CHANGED'
    }

    [pscustomobject]@{
        Result = 'PASS'
        NetworkMode = 'OFFLINE'
        DependencyVerificationMode = 'STRICT'
        HeadMatchesExpected = $true
        WorkingTreeClean = $workingTreeClean
        GradleQualification = 'PASS'
        DependencyVerificationMetadataSha256 = $metadataHashAfter
        DependencyVerificationMetadataUnchanged = $true
        VerificationMetadataGeneration = 'NOT_RUN'
        DependencyVerificationComponentCount = $componentCount
        DependencyVerificationSha256Count = $sha256Count
        DependencyVerificationWeakChecksumCount = $weakChecksumCount
    }
} finally {
    Pop-Location
}
