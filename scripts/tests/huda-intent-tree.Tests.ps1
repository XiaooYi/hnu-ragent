Set-StrictMode -Version Latest
$modulePath = Join-Path $PSScriptRoot '..\huda-intent-tree.psm1'

Import-Module $modulePath -Force

$corpusRoot = 'D:\study\研一资料\湖大新生文件'
$plan = Get-HudaIntentPlan -CorpusRoot $corpusRoot -KnowledgeBases @{
    '湖大通用概况与校园生活' = 'kb-general'
    '湖大本科教学与学业制度' = 'kb-undergraduate-rules'
    '湖大本科专业培养方案'   = 'kb-undergraduate-programs'
    '湖大奖助学金资助'       = 'kb-funding'
    '湖大研究生新生与研究生管理' = 'kb-graduate'
}

if ($plan.Count -lt 20) {
    throw "Expected a non-trivial KB intent plan, got $($plan.Count) nodes."
}

$codes = @($plan | ForEach-Object { $_.intentCode })
if ($codes.Count -ne ($codes | Select-Object -Unique).Count) {
    throw 'Intent codes must be unique.'
}

$roots = @($plan | Where-Object { $_.parentCode -eq $null })
if ($roots.Count -ne 5) {
    throw "Expected five KB roots, got $($roots.Count)."
}

$topics = @($plan | Where-Object { $_.level -eq 2 })
if (@($topics | Where-Object { [string]::IsNullOrWhiteSpace($_.kbId) }).Count -gt 0) {
    throw 'Every KB TOPIC node must have a kbId.'
}

$inventory = Get-HudaCorpusInventory -CorpusRoot $corpusRoot
if ($inventory.TotalFiles -le 0) {
    throw 'Corpus inventory must find files.'
}
if ($inventory.IgnoredFiles -lt 1) {
    throw 'Inventory must identify ignored metadata/media files.'
}
if ($inventory.ReviewFiles -lt 1) {
    throw 'Inventory must identify legacy/archive files for review.'
}

Write-Output "PASS: $($plan.Count) nodes, $($inventory.TotalFiles) corpus files, $($inventory.IgnoredFiles) ignored files"
