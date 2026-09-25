# 断言脚本：校验示例问题预设满足欢迎页与管理端展示要求，且导入脚本保持“默认不写库”。
# 运行方式：pwsh -File scripts/tests/sample-questions.Tests.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$wrapperPath = Join-Path $PSScriptRoot '..\import-sample-questions.ps1'
$modulePath = Join-Path $PSScriptRoot '..\sample-questions.psm1'
$wrapperText = Get-Content -LiteralPath $wrapperPath -Raw -Encoding UTF8
$moduleText = Get-Content -LiteralPath $modulePath -Raw -Encoding UTF8

Import-Module $modulePath -Force
$plan = Get-SampleQuestionPlan

# 欢迎页随机展示 3 条（SampleQuestionServiceImpl.DEFAULT_LIMIT），因此至少准备 10 条以保证轮换空间。
if ($plan.Count -lt 10) {
    throw "Expected at least 10 sample questions, found $($plan.Count)."
}

$titles = @($plan | ForEach-Object { $_.title })
if (@($titles | Select-Object -Unique).Count -ne $titles.Count) {
    throw 'Sample question titles must be unique.'
}

$questions = @($plan | ForEach-Object { $_.question })
$normalizedQuestions = @($questions | ForEach-Object { Get-SampleQuestionKey -Question $_ })
if (@($normalizedQuestions | Select-Object -Unique).Count -ne $normalizedQuestions.Count) {
    throw 'Sample questions must be unique after whitespace normalization.'
}

# 与 t_sample_question 的列长保持一致，避免脚本写库时才失败。
foreach ($item in $plan) {
    if ($item.title.Length -gt 64) { throw "title exceeds VARCHAR(64): $($item.title)" }
    if ($item.description.Length -gt 255) { throw "description exceeds VARCHAR(255): $($item.description)" }
    if ($item.question.Length -gt 255) { throw "question exceeds VARCHAR(255): $($item.question)" }
    if ($item.question -notmatch '？') {
        throw "Sample question should read as a question: $($item.question)"
    }
}

if ($wrapperText -notmatch 'Invoke-SampleQuestionImport' -or $wrapperText -notmatch '-Apply:\$Apply') {
    throw 'The import wrapper must route through Invoke-SampleQuestionImport and forward -Apply.'
}
if ($moduleText -match 'Write-(Output|Host|Verbose)[^\r\n]*\$Password') {
    throw 'The module must never print the password.'
}
if ($wrapperText -notmatch "\`$env:RAGENT_AUTHORIZATION") {
    throw 'The wrapper must default the Authorization header to $env:RAGENT_AUTHORIZATION.'
}
if ($wrapperText -notmatch 'Connect-RagentSession') {
    throw 'The wrapper must support username/password login via Connect-RagentSession.'
}

Write-Output "PASS: $($plan.Count) sample questions cover welcome-page rotation, unique titles, and fit t_sample_question column limits."
