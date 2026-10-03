param(
    [Parameter(Mandatory = $true)][string]$GoHome,
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [Parameter(Mandatory = $true)][string]$AndroidNdk,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot
$work = Join-Path $source 'build'
$go = Join-Path $GoHome 'bin/go.exe'
if (-not (Test-Path -LiteralPath $go)) { $go = Join-Path $GoHome 'bin/go' }
if (-not (Test-Path -LiteralPath $go)) { throw 'GO_HOME_INVALID' }
if ((& $go version) -notmatch '^go version go1\.27\.1 ') { throw 'GO_1_27_1_REQUIRED' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe')) -and
    -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac'))) { throw 'JAVA_HOME_INVALID' }
$ndkProperties = Join-Path $AndroidNdk 'source.properties'
if (-not (Test-Path -LiteralPath $ndkProperties) -or
    (Get-Content -LiteralPath $ndkProperties -Raw) -notmatch 'Pkg.Revision\s*=\s*27\.2\.12479018\b') {
    throw 'NDK_R27C_REQUIRED'
}
New-Item -ItemType Directory -Force -Path $work, "$work/bin" | Out-Null
$env:GOTOOLCHAIN = 'local'
$env:GOMAXPROCS = '2'
$env:GOPATH = Join-Path $work 'gopath'
$env:GOMODCACHE = Join-Path $work 'modcache'
$env:GOCACHE = Join-Path $work 'gocache'
$env:GOSUMDB = 'sum.golang.org'
$env:GOPROXY = 'https://proxy.golang.org'
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = $AndroidNdk
$env:PATH = (Join-Path $GoHome 'bin') + [IO.Path]::PathSeparator +
    (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + "$work/bin" +
    [IO.Path]::PathSeparator + $env:PATH
$suffix = if ($IsWindows) { '.exe' } else { '' }
function Invoke-NativeTool([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> (Join-Path $work $Log)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "NATIVE_BUILD_FAILED: $Log" }
}
$descriptorHashes = @('go.mod', 'go.sum', 'dependencies.go') | ForEach-Object {
    (Get-FileHash -LiteralPath (Join-Path $source $_) -Algorithm SHA256).Hash
}
Push-Location $source
try {
    Invoke-NativeTool $go @('mod', 'download') 'download.log'
    Invoke-NativeTool $go @('build', '-mod=readonly', '-trimpath', '-o', "$work/bin/gomobile$suffix", 'golang.org/x/mobile/cmd/gomobile') 'gomobile.log'
    Invoke-NativeTool $go @('build', '-mod=readonly', '-trimpath', '-o', "$work/bin/gobind$suffix", 'golang.org/x/mobile/cmd/gobind') 'gobind.log'
    Invoke-NativeTool "$work/bin/gomobile$suffix" @('init') 'init.log'
    $packages = @('crypto', 'armor', 'constants', 'models', 'subtle', 'helper') |
        ForEach-Object { 'github.com/ProtonMail/gopenpgp/v2/' + $_ }
    $packages += 'github.com/ProtonMail/go-srp'
    Invoke-NativeTool "$work/bin/gomobile$suffix" (@('bind', '-tags', 'mobile', '-target', 'android',
        '-androidapi', '31', '-javapkg', 'com.proton.gopenpgp', '-trimpath', '-ldflags=-s -w',
        '-o', "$work/raw.aar") + $packages) 'bind.log'
    $after = @('go.mod', 'go.sum', 'dependencies.go') | ForEach-Object {
        (Get-FileHash -LiteralPath (Join-Path $source $_) -Algorithm SHA256).Hash
    }
    if (($descriptorHashes -join ',') -ne ($after -join ',')) { throw 'NATIVE_SOURCE_LOCK_CHANGED' }
    Invoke-NativeTool $go @('list', '-m', '-json', 'all') 'modules.json'
    $reference = Join-Path $work 'official.aar'
    $expected = 'f64705f4ae14dde5c619d03a3a7fc2e8d747fb2803c4b5da01b0c38dac7884c9'
    if (-not (Test-Path -LiteralPath $reference)) {
        Invoke-WebRequest 'https://repo.maven.apache.org/maven2/me/proton/crypto/android-golib/2.10.0-2/android-golib-2.10.0-2.aar' -OutFile $reference
    }
    if ((Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash -ne $expected) {
        throw 'OFFICIAL_REFERENCE_HASH_MISMATCH'
    }
    Invoke-NativeTool $Python @((Join-Path $source 'package-aar.py'), '--go', $go,
        '--java-home', $JavaHome) 'package.log'
    Write-Output 'NATIVE_GOLIB_BUILD_COMPLETE; verify the resulting hashes against Gradle verification metadata'
} finally { Pop-Location }
