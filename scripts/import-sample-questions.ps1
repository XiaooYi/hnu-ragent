[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:9090/api/ragent',
    [string]$Authorization = $env:RAGENT_AUTHORIZATION,
    [string]$Username,
    [string]$Password = $env:RAGENT_PASSWORD,
    [int]$PageSize = 100,
    [string]$Proxy,
    [switch]$DryRun,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'sample-questions.psm1') -Force

if ($DryRun -and $Apply) {
    throw 'Choose either -DryRun or -Apply, not both.'
}

# 未显式给出 Authorization 时用账号密码登录换取 token，脚本不会打印凭证。
if ([string]::IsNullOrWhiteSpace($Authorization)) {
    if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw 'Authorization is required. Pass -Authorization or set RAGENT_AUTHORIZATION (or pass -Username with -Password / RAGENT_PASSWORD). The value is sent as the frontend Authorization header and is never printed.'
    }
    $Authorization = Connect-RagentSession -BaseUrl $BaseUrl -Username $Username -Password $Password -Proxy $Proxy
}

$headers = @{ Authorization = $Authorization }
$plan = Get-SampleQuestionPlan

Write-Output "Planned sample questions: $($plan.Count); mode: $(if ($Apply) { 'APPLY' } else { 'DRY-RUN' })"
$results = Invoke-SampleQuestionImport -BaseUrl $BaseUrl -Headers $headers -Plan $plan -Apply:$Apply -PageSize $PageSize -Proxy $Proxy
$results | Format-Table -AutoSize

$created = @($results | Where-Object { $_.Action -eq 'CREATE' }).Count
$present = @($results | Where-Object { $_.Action -eq 'SKIP' }).Count
Write-Output "Created: $created; already present: $present"

if (-not $Apply) {
    Write-Output 'Dry-run only. Re-run with -Apply after reviewing the list. No write request was sent.'
}
