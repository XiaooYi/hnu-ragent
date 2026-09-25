# 欢迎页示例问题（t_sample_question）批量导入模块。
#
# 数据与逻辑分离：示例问题只定义在 $script:SampleQuestionPresets，
# 导入过程只调用管理端既有 REST 接口（/auth/login、/sample-questions），
# 不直接写库，因此重复执行是安全的（已存在的问题按 question 文本去重跳过）。
#
# 字段长度受 t_sample_question 约束：title <= 64、description <= 255、question <= 255。
Set-StrictMode -Version Latest

$script:SampleQuestionPresets = @(
    [pscustomobject][ordered]@{
        Title       = '校园卡与校园网'
        Description = '办理校园卡、开通校园网'
        Question    = '湖南大学校园卡怎么使用和充值？校园网如何开通，宿舍有线网和校园 Wi-Fi 分别怎么接入？'
    }
    [pscustomobject][ordered]@{
        Title       = '宿舍与生活报修'
        Description = '住宿规定与设施报修方式'
        Question    = '湖南大学学生宿舍的住宿管理规定有哪些？宿舍水电等生活设施需要报修时应该走什么流程？'
    }
    [pscustomobject][ordered]@{
        Title       = '选课与退改选'
        Description = '查询选课资格、安排与补退选'
        Question    = '湖南大学本科生如何完成选课、退课和补退选？请区分普通选课与特殊情况，说明办理渠道和注意事项。'
    }
    [pscustomobject][ordered]@{
        Title       = '绩点与成绩管理'
        Description = '了解成绩记载与绩点计算'
        Question    = '湖南大学本科生的课程成绩如何记载？绩点是怎么计算的，补考或重修后的成绩怎么处理？'
    }
    [pscustomobject][ordered]@{
        Title       = '考试与违规处理'
        Description = '考核方式与考试违规后果'
        Question    = '湖南大学本科生课程考核不合格怎么办？考试违规会受到什么处理和处分？'
    }
    [pscustomobject][ordered]@{
        Title       = '转专业与辅修'
        Description = '转专业、专业分流与辅修条件'
        Question    = '湖南大学本科生申请转专业、专业分流和辅修分别需要满足哪些条件，走什么流程？'
    }
    [pscustomobject][ordered]@{
        Title       = '专业培养方案'
        Description = '按专业查课程体系与学分'
        Question    = '计算机科学与技术专业的培养目标、毕业要求是什么？需要修读哪些课程、达到多少学分？'
    }
    [pscustomobject][ordered]@{
        Title       = '奖学金申请'
        Description = '国家奖学金与综合奖学金条件'
        Question    = '湖南大学国家奖学金、国家励志奖学金和综合奖学金的申请条件与评审流程分别是什么？'
    }
    [pscustomobject][ordered]@{
        Title       = '助学贷款办理'
        Description = '校园地贷款与生源地贷款'
        Question    = '湖南大学本科生国家助学贷款怎么申请？校园地贷款和生源地贷款在办理流程上有什么区别？'
    }
    [pscustomobject][ordered]@{
        Title       = '困难认定与助学金'
        Description = '认定流程与资助申请'
        Question    = '家庭经济困难学生如何认定？湖南大学国家助学金和临时困难补助分别怎么申请？'
    }
    [pscustomobject][ordered]@{
        Title       = '研究生报到与住宿'
        Description = '新生报到流程与住宿申请'
        Question    = '湖南大学研究生新生报到的流程和需要准备的材料有哪些？研究生住宿如何申请、宿舍怎么分配？'
    }
    [pscustomobject][ordered]@{
        Title       = '研究生课程与考核'
        Description = '英语免修与中期考核要求'
        Question    = '湖南大学研究生可以跨学院选课吗？公共英语免修需要什么条件，中期考核有哪些要求？'
    }
)

$script:SampleQuestionTitleLimit = 64
$script:SampleQuestionDescriptionLimit = 255
$script:SampleQuestionQuestionLimit = 255

function Get-RagentApiData {
    param([Parameter(Mandatory)] $Response)
    if ($null -ne $Response -and $null -ne $Response.PSObject.Properties['data']) {
        return $Response.data
    }
    return $Response
}

function Invoke-RagentApiJson {
    param(
        [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Headers,
        [object]$Body,
        [string]$Proxy
    )
    $params = @{ Method = $Method; Uri = $Uri; Headers = $Headers; ErrorAction = 'Stop' }
    if (-not [string]::IsNullOrWhiteSpace($Proxy)) {
        $params.Proxy = $Proxy
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    return Invoke-RestMethod @params
}

function Connect-RagentSession {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password,
        [string]$Proxy
    )
    $uri = "$($BaseUrl.TrimEnd('/'))/auth/login"
    $payload = [ordered]@{ username = $Username; password = $Password }
    $login = Get-RagentApiData -Response (Invoke-RagentApiJson -Method POST -Uri $uri -Headers @{} -Body $payload -Proxy $Proxy)
    $token = [string]$login.token
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw 'Login succeeded but no Authorization token was returned.'
    }
    return $token
}

function Get-SampleQuestionKey {
    param([Parameter(Mandatory)][string]$Question)
    return ($Question -replace '\s+', '').Trim().ToLowerInvariant()
}

function Get-SampleQuestionPlan {
    [CmdletBinding()]
    param()

    $plan = [System.Collections.Generic.List[object]]::new()
    foreach ($preset in $script:SampleQuestionPresets) {
        $title = [string]$preset.Title
        $description = [string]$preset.Description
        $question = [string]$preset.Question
        if ([string]::IsNullOrWhiteSpace($title)) { throw 'Preset title cannot be blank.' }
        if ([string]::IsNullOrWhiteSpace($question)) { throw 'Preset question cannot be blank.' }
        if ($title.Length -gt $script:SampleQuestionTitleLimit) { throw "Preset title exceeds $($script:SampleQuestionTitleLimit) characters: $title" }
        if ($description.Length -gt $script:SampleQuestionDescriptionLimit) { throw "Preset description exceeds $($script:SampleQuestionDescriptionLimit) characters: $description" }
        if ($question.Length -gt $script:SampleQuestionQuestionLimit) { throw "Preset question exceeds $($script:SampleQuestionQuestionLimit) characters: $question" }
        $null = $plan.Add([pscustomobject][ordered]@{
            title       = $title
            description = $description
            question    = $question
        })
    }
    return @($plan)
}

function Get-SampleQuestionInventory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [int]$PageSize = 100,
        [string]$Proxy
    )
    if ($PageSize -le 0) { throw 'PageSize must be greater than zero.' }

    $records = [System.Collections.Generic.List[object]]::new()
    $current = 1
    while ($true) {
        $uri = "$($BaseUrl.TrimEnd('/'))/sample-questions?current=$current&size=$PageSize"
        $page = Get-RagentApiData -Response (Invoke-RagentApiJson -Method GET -Uri $uri -Headers $Headers -Proxy $Proxy)
        if ($null -eq $page) { break }
        $pageRecords = @()
        if ($null -ne $page.PSObject.Properties['records']) { $pageRecords = @($page.records) }
        else { $pageRecords = @($page) }
        foreach ($record in $pageRecords) {
            if ($null -ne $record) { $null = $records.Add($record) }
        }
        $totalPages = 0
        if ($null -ne $page.PSObject.Properties['pages']) { $totalPages = [int]$page.pages }
        if ($pageRecords.Count -eq 0 -or $totalPages -le $current) { break }
        $current++
    }
    return @($records)
}

function ConvertTo-SampleQuestionPayload {
    param([Parameter(Mandatory)] $Item)
    return [ordered]@{
        title       = $Item.title
        description = $Item.description
        question    = $Item.question
    }
}

function Invoke-SampleQuestionImport {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][object[]]$Plan,
        [switch]$Apply,
        [int]$PageSize = 100,
        [string]$Proxy
    )

    $existingKeys = @{}
    foreach ($record in (Get-SampleQuestionInventory -BaseUrl $BaseUrl -Headers $Headers -PageSize $PageSize -Proxy $Proxy)) {
        $question = [string]$record.question
        if ([string]::IsNullOrWhiteSpace($question)) { continue }
        $existingKeys[(Get-SampleQuestionKey -Question $question)] = $record
    }

    $results = [System.Collections.Generic.List[object]]::new()
    foreach ($item in $Plan) {
        $key = Get-SampleQuestionKey -Question ([string]$item.question)
        if ($existingKeys.ContainsKey($key)) {
            $null = $results.Add([pscustomobject]@{
                Action   = 'SKIP'
                Title    = $item.title
                Question = $item.question
                Message  = 'already exists'
            })
            continue
        }
        $uri = "$($BaseUrl.TrimEnd('/'))/sample-questions"
        $createdId = $null
        if ($Apply) {
            $createdId = [string](Get-RagentApiData -Response (Invoke-RagentApiJson -Method POST -Uri $uri -Headers $Headers -Body (ConvertTo-SampleQuestionPayload -Item $item) -Proxy $Proxy))
            $existingKeys[$key] = $item
        }
        $null = $results.Add([pscustomobject]@{
            Action   = if ($Apply) { 'CREATE' } else { 'DRY-CREATE' }
            Title    = $item.title
            Question = $item.question
            Message  = if ($Apply) { $createdId } else { $uri }
        })
    }
    return @($results)
}

Export-ModuleMember -Function Get-SampleQuestionPlan, Get-SampleQuestionInventory, Get-SampleQuestionKey, Connect-RagentSession, Invoke-SampleQuestionImport
