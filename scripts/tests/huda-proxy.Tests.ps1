Set-StrictMode -Version Latest

$wrapperPath = Join-Path $PSScriptRoot '..\import-huda-intent-tree.ps1'
$modulePath = Join-Path $PSScriptRoot '..\huda-intent-tree.psm1'
$wrapperText = Get-Content -LiteralPath $wrapperPath -Raw -Encoding UTF8
$moduleText = Get-Content -LiteralPath $modulePath -Raw -Encoding UTF8

if ($wrapperText -notmatch '\[string\]\$Proxy') {
    throw 'The import wrapper must expose a string -Proxy parameter.'
}
if ($moduleText -notmatch 'Proxy\s*=\s*\$Proxy') {
    throw 'The API request helper must forward the configured proxy.'
}

Write-Output 'PASS: import wrapper exposes and forwards -Proxy'
