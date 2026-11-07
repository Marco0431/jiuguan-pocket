param([ValidateSet('protect', 'unprotect')][string]$Mode)
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Security
    $inputText = [Console]::In.ReadToEnd().Trim()
    $entropy = [Text.Encoding]::UTF8.GetBytes('JiuguanPocket.PhoneRelay.v1')
    if ($Mode -eq 'protect') {
        $bytes = [Text.Encoding]::UTF8.GetBytes($inputText)
        $protected = [Security.Cryptography.ProtectedData]::Protect($bytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Console]::Out.Write([Convert]::ToBase64String($protected))
    } else {
        $bytes = [Convert]::FromBase64String($inputText)
        $plain = [Security.Cryptography.ProtectedData]::Unprotect($bytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Console]::Out.Write([Text.Encoding]::UTF8.GetString($plain))
    }
} catch {
    [Console]::Error.Write('Unable to access the locally protected relay setting.')
    exit 1
}
