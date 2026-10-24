# Text descriptors use UTF-8 without a BOM and LF line endings for hashing.
# Binary dependency and release artifacts retain their exact byte hashes.
function Get-CanonicalTextSha256([string]$Path) {
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    $text = $utf8.GetString([IO.File]::ReadAllBytes($Path))
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $bytes = $utf8.GetBytes($text.Replace("`r`n", "`n").Replace("`r", "`n"))
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace('-', '') }
    finally { $sha256.Dispose() }
}
