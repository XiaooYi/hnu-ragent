[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:9090/api/ragent',
    [string]$SourceRoot = 'D:\study\研一资料\湖大新生文件',
    [string]$Authorization = $env:RAGENT_AUTHORIZATION,
    [string]$Proxy,
    [string]$KnowledgeBaseMapPath,
    [int]$TopK = 8,
    [switch]$DryRun,
    [switch]$Apply,
    [switch]$UpdateExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'huda-intent-tree.psm1') -Force

if ($DryRun -and $Apply) {
    throw 'Choose either -DryRun or -Apply, not both.'
}

if ([string]::IsNullOrWhiteSpace($Authorization)) {
    throw 'Authorization is required. Pass -Authorization or set RAGENT_AUTHORIZATION. The value is sent as the frontend Authorization header and is never printed.'
}

$headers = @{ Authorization = $Authorization }
$kbMap = if (-not [string]::IsNullOrWhiteSpace($KnowledgeBaseMapPath)) {
    if (-not (Test-Path -LiteralPath $KnowledgeBaseMapPath -PathType Leaf)) { throw "KB map file not found: $KnowledgeBaseMapPath" }
    $rawMap = Get-Content -LiteralPath $KnowledgeBaseMapPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $result = @{}
    foreach ($property in $rawMap.PSObject.Properties) { $result[$property.Name] = [string]$property.Value }
    $result
} else {
    Get-HudaKnowledgeBaseMap -BaseUrl $BaseUrl -Headers $headers -Proxy $Proxy
}

$plan = Get-HudaIntentPlan -CorpusRoot $SourceRoot -KnowledgeBases $kbMap -TopK $TopK
$inventory = Get-HudaCorpusInventory -CorpusRoot $SourceRoot

Write-Output "Corpus files: $($inventory.TotalFiles); usable: $($inventory.UsableFiles); ignored metadata/media: $($inventory.IgnoredFiles)"
Write-Output "Planned nodes: $($plan.Count); mode: $(if ($Apply) { 'APPLY' } else { 'DRY-RUN' })"
$results = Invoke-HudaIntentTreeImport -BaseUrl $BaseUrl -Headers $headers -Plan $plan -Apply:$Apply -UpdateExisting:$UpdateExisting -Proxy $Proxy
$results | Format-Table -AutoSize

if (-not $Apply) {
    Write-Output 'Dry-run only. Re-run with -Apply after reviewing the node list. No write request was sent.'
}
