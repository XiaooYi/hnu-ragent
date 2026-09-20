Set-StrictMode -Version Latest

$script:HudaIntentDefinitions = @(
    [ordered]@{
        Name = '湖大通用概况与校园生活'
        Code = 'hnu-general-life'
        Description = '湖南大学学校概况、校园地图、住宿、校园卡、校园网、教室、社团和校园生活服务。'
        SourceDirs = @('1.湖大概况介绍', '2.湖大新生关注汇总-生活篇')
        Topics = @(
            [ordered]@{ Suffix = 'overview'; Name = '学校概况与校园文化'; Description = '学校简介、校训、校风、校徽、校歌和学校基本概况。'; Examples = @('湖南大学是一所什么样的学校？', '湖南大学校训是什么？') }
            [ordered]@{ Suffix = 'maps-facilities'; Name = '校园地图与设施'; Description = '校园地图、生活地图、办公地图、教学楼、食宿设施和公共空间。'; Examples = @('湖南大学校园地图怎么看？', '湖大有哪些食宿设施？') }
            [ordered]@{ Suffix = 'accommodation'; Name = '住宿与宿舍管理'; Description = '住宿环境、宿舍园区、学生宿舍管理和住宿相关规定。'; Examples = @('湖南大学宿舍条件怎么样？', '宿舍管理有哪些规定？') }
            [ordered]@{ Suffix = 'campus-card-network'; Name = '校园卡与校园网'; Description = '校园卡使用管理、校园网接入和网络管理办法。'; Examples = @('湖南大学校园卡怎么使用？', '湖大校园网如何办理？') }
            [ordered]@{ Suffix = 'campus-services'; Name = '校园事务与公共服务'; Description = '教室借用、请销假、学生申诉、学杂费和其他校园事务流程。'; Examples = @('湖南大学教室怎么借用？', '请销假流程是什么？') }
            [ordered]@{ Suffix = 'organizations'; Name = '学生组织与社团'; Description = '学生会、研究生会、艺术团和学生社团信息。'; Examples = @('湖南大学有哪些社团？', '怎么加入湖大学生会？') }
            [ordered]@{ Suffix = 'student-rules-workstudy'; Name = '学生管理与勤工助学'; Description = '学生违纪处理、学生勤工助学和相关学生管理规定。'; Examples = @('湖南大学勤工助学怎么申请？', '学生违纪如何处理？') }
        )
    }
    [ordered]@{
        Name = '湖大本科教学与学业制度'
        Code = 'hnu-undergraduate-rules'
        Description = '湖南大学本科生教学运行、选课考试、学籍、成绩、转专业、辅修、培养项目和学位制度。'
        SourceDirs = @('3.湖大新生重点文件汇总-学业篇')
        Topics = @(
            [ordered]@{ Suffix = 'course-selection'; Name = '选课与课堂教学'; Description = '本科生选课、课堂教学、自主选择课堂和教学楼教室简称。'; Examples = @('湖南大学本科生怎么选课？', '自主选择课堂怎么操作？') }
            [ordered]@{ Suffix = 'exams-grades'; Name = '考试与成绩管理'; Description = '课程考核、考试规则、考试违规、成绩管理和绩点计算。'; Examples = @('湖南大学考试违规怎么处理？', '本科生绩点怎么计算？') }
            [ordered]@{ Suffix = 'student-status-degree'; Name = '学籍与学位'; Description = '本科学生学籍管理、学士学位授予、港澳台和留学生培养管理。'; Examples = @('湖南大学本科生学籍有哪些规定？', '本科毕业如何申请学士学位？') }
            [ordered]@{ Suffix = 'major-change-minor'; Name = '转专业、专业分流与辅修'; Description = '转专业、专业分流、辅修专业和双学士学位的条件、流程与名额。'; Examples = @('湖南大学本科生如何转专业？', '专业分流怎么申请？', '辅修专业有哪些要求？') }
            [ordered]@{ Suffix = 'advanced-programs'; Name = '拔尖、强基与试验班'; Description = '拔尖班、强基计划、校级试验班、本硕博贯通和相关选拔培养政策。'; Examples = @('湖南大学拔尖班如何选拔？', '强基计划培养方案是什么？') }
            [ordered]@{ Suffix = 'evaluation-honors'; Name = '素质测评与优秀评选'; Description = '本科生素质测评、优秀毕业生和通识教育等评选与培养事项。'; Examples = @('湖南大学本科生素质测评怎么评？', '优秀毕业生如何评选？') }
            [ordered]@{ Suffix = 'admission-special'; Name = '招生与特殊学生培养'; Description = '本科招生管理、招生专业目录、预科班和特殊类别学生培养管理。'; Examples = @('湖南大学本科招生专业有哪些？', '预科班学生如何管理？') }
            [ordered]@{ Suffix = 'military-service'; Name = '征兵与服役资助'; Description = '大学生征兵、服义务兵役及退役士兵教育资助相关制度。'; Examples = @('湖南大学大学生征兵有什么政策？', '服义务兵役可以获得哪些资助？') }
        )
    }
    [ordered]@{
        Name = '湖大本科专业培养方案'
        Code = 'hnu-undergraduate-programs'
        Description = '湖南大学本科各专业培养方案、培养目标、毕业要求、课程体系、学分和学期安排。'
        SourceDirs = @('4.湖大所有专业培养方案汇总')
        Topics = @(
            [ordered]@{ Suffix = 'program-query'; Name = '本科专业培养方案查询'; Description = '按专业名称查询本科专业培养目标、毕业要求、学制学位、学分要求、课程体系、实践环节和学期安排。该知识库包含约86个专业培养方案。'; Examples = @('计算机科学与技术专业培养方案是什么？', '人工智能专业需要修哪些课程？', '湖南大学某专业毕业需要多少学分？') }
        )
    }
    [ordered]@{
        Name = '湖大奖助学金资助'
        Code = 'hnu-undergraduate-funding'
        Description = '湖南大学本科生奖学金、助学金、困难认定、国家助学贷款和专项资助。'
        SourceDirs = @('5.湖大奖、助学金篇')
        Topics = @(
            [ordered]@{ Suffix = 'scholarships'; Name = '奖学金与荣誉奖励'; Description = '国家奖学金、国家励志奖学金、综合奖学金、港澳台奖学金和学生奖励表彰。'; Examples = @('湖南大学国家奖学金怎么评？', '综合奖学金申请条件是什么？') }
            [ordered]@{ Suffix = 'grants-hardship'; Name = '助学金与困难认定'; Description = '国家助学金、家庭经济困难学生认定和临时困难补助。'; Examples = @('湖南大学国家助学金如何申请？', '家庭经济困难学生如何认定？') }
            [ordered]@{ Suffix = 'student-loans'; Name = '国家助学贷款'; Description = '国家助学贷款、校园地贷款和生源地贷款的申请、入学后办理及还款规则。'; Examples = @('湖南大学国家助学贷款怎么申请？', '校园地贷款入学后如何办理？') }
            [ordered]@{ Suffix = 'overseas-aid'; Name = '出国境学习专项奖学金'; Description = '本科生出国或出境学习专项奖学金及相关资助条件和流程。'; Examples = @('出国境学习专项奖学金怎么申请？') }
            [ordered]@{ Suffix = 'funding-reference'; Name = '资助政策参考与版本说明'; Description = '标注为供参考的资助细则、操作指南和历史版本，只在用户明确询问相关政策时使用。'; Examples = @('2024版校园地贷款细则还能参考吗？') }
        )
    }
    [ordered]@{
        Name = '湖大研究生新生与研究生管理'
        Code = 'hnu-graduate-management'
        Description = '湖南大学研究生新生报到、住宿、课程、研究生系统、学籍、学位、中期考核、奖学金和专业实践。'
        SourceDirs = @('6.湖大研究生新生专属-核心篇')
        Topics = @(
            [ordered]@{ Suffix = 'registration'; Name = '研究生新生报到'; Description = '研究生新生入学报到、材料准备、报到流程和新生注意事项。'; Examples = @('湖南大学研究生新生如何报到？', '研究生报到需要准备什么材料？') }
            [ordered]@{ Suffix = 'housing'; Name = '研究生住宿申请'; Description = '研究生新生网上住宿申请、寝室分配和研究生住宿管理。'; Examples = @('研究生如何申请住宿？', '湖南大学研究生宿舍怎么分配？') }
            [ordered]@{ Suffix = 'courses-english'; Name = '研究生课程与英语免修'; Description = '研究生课程选修、跨学科跨学院选课、公共英语免修和人文素养课程。'; Examples = @('研究生可以跨学院选课吗？', '研究生英语免修条件是什么？') }
            [ordered]@{ Suffix = 'systems'; Name = '研究生管理系统与学堂云'; Description = '研究生管理信息系统和学堂云平台的学生端操作。'; Examples = @('研究生管理信息系统怎么使用？', '学堂云课程怎么操作？') }
            [ordered]@{ Suffix = 'status-degree'; Name = '研究生学籍、学位与中期考核'; Description = '研究生学籍管理、学位授予、中期考核和毕业相关制度。'; Examples = @('研究生中期考核有哪些要求？', '研究生学位授予条件是什么？') }
            [ordered]@{ Suffix = 'scholarships'; Name = '研究生奖学金与评优'; Description = '研究生学业奖学金、国家奖学金和优秀研究生评选。'; Examples = @('研究生学业奖学金如何评定？', '研究生国家奖学金怎么申请？') }
            [ordered]@{ Suffix = 'practice-management'; Name = '研究生校外修课与专业实践'; Description = '研究生校外修课、专业实践和跨学科学习等培养管理事项。'; Examples = @('研究生校外修课需要什么手续？', '专业学位研究生专业实践怎么安排？') }
        )
    }
)

function Get-HudaCorpusInventory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$CorpusRoot)

    if (-not (Test-Path -LiteralPath $CorpusRoot -PathType Container)) {
        throw "Corpus root does not exist: $CorpusRoot"
    }

    $files = @(Get-ChildItem -LiteralPath $CorpusRoot -Recurse -File -Force)
    $ignoredExtensions = @('.ini', '.mp3', '.mp4')
    $reviewExtensions = @('.zip', '.doc', '.xls')
    $ignored = @($files | Where-Object { $ignoredExtensions -contains $_.Extension.ToLowerInvariant() -or $_.Name -ieq 'desktop.ini' })
    $usable = @($files | Where-Object { $ignored -notcontains $_ })
    $review = @($files | Where-Object { $reviewExtensions -contains $_.Extension.ToLowerInvariant() })
    $folderCounts = [ordered]@{}
    foreach ($definition in $script:HudaIntentDefinitions) {
        $count = 0
        foreach ($sourceDir in $definition.SourceDirs) {
            $path = Join-Path $CorpusRoot $sourceDir
            if (-not (Test-Path -LiteralPath $path -PathType Container)) {
                throw "Configured source directory does not exist: $path"
            }
            $count += @(Get-ChildItem -LiteralPath $path -Recurse -File -Force).Count
        }
        $folderCounts[$definition.Name] = $count
    }

    [pscustomobject]@{
        TotalFiles = $files.Count
        UsableFiles = $usable.Count
        IgnoredFiles = $ignored.Count
        IgnoredPaths = @($ignored | ForEach-Object FullName)
        ReviewFiles = $review.Count
        ReviewPaths = @($review | ForEach-Object FullName)
        KnowledgeBaseFolderCounts = $folderCounts
    }
}

function Get-HudaIntentPlan {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$CorpusRoot,
        [Parameter(Mandatory)][hashtable]$KnowledgeBases,
        [int]$TopK = 8
    )

    if ($TopK -le 0) { throw 'TopK must be greater than zero.' }
    $null = Get-HudaCorpusInventory -CorpusRoot $CorpusRoot
    foreach ($definition in $script:HudaIntentDefinitions) {
        if (-not $KnowledgeBases.ContainsKey($definition.Name) -or [string]::IsNullOrWhiteSpace([string]$KnowledgeBases[$definition.Name])) {
            throw "Missing knowledge-base ID for: $($definition.Name)"
        }
    }

    $nodes = [System.Collections.Generic.List[object]]::new()
    $sortOrder = 0
    foreach ($definition in $script:HudaIntentDefinitions) {
        $rootCode = [string]$definition.Code
        $categoryCode = "$rootCode-topics"
        $null = $nodes.Add([pscustomobject][ordered]@{
            kbId = $null
            intentCode = $rootCode
            name = [string]$definition.Name
            level = 0
            parentCode = $null
            description = [string]$definition.Description
            examples = @()
            topK = $null
            kind = 0
            sortOrder = $sortOrder++
            enabled = 1
            sourceFolders = @($definition.SourceDirs)
        })
        $null = $nodes.Add([pscustomobject][ordered]@{
            kbId = $null
            intentCode = $categoryCode
            name = "$($definition.Name)主题"
            level = 1
            parentCode = $rootCode
            description = [string]$definition.Description
            examples = @()
            topK = $null
            kind = 0
            sortOrder = $sortOrder++
            enabled = 1
            sourceFolders = @($definition.SourceDirs)
        })
        foreach ($topic in $definition.Topics) {
            $null = $nodes.Add([pscustomobject][ordered]@{
                kbId = [string]$KnowledgeBases[$definition.Name]
                intentCode = "$rootCode-$($topic.Suffix)"
                name = [string]$topic.Name
                level = 2
                parentCode = $categoryCode
                description = [string]$topic.Description
                examples = @($topic.Examples)
                topK = $TopK
                kind = 0
                sortOrder = $sortOrder++
                enabled = 1
                sourceFolders = @($definition.SourceDirs)
            })
        }
    }
    return @($nodes)
}

function Get-HudaApiData {
    param([Parameter(Mandatory)]$Response)
    if ($null -ne $Response -and $null -ne $Response.PSObject.Properties['data']) {
        return $Response.data
    }
    return $Response
}

function Invoke-HudaApiJson {
    param(
        [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT')][string]$Method,
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

function Get-HudaKnowledgeBaseMap {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [string]$Proxy
    )
    $uri = "$($BaseUrl.TrimEnd('/'))/knowledge-base?current=1&size=100"
    $page = Get-HudaApiData -Response (Invoke-HudaApiJson -Method GET -Uri $uri -Headers $Headers -Proxy $Proxy)
    $records = if ($null -ne $page.PSObject.Properties['records']) { @($page.records) } else { @($page) }
    $map = @{}
    foreach ($record in $records) {
        if (-not [string]::IsNullOrWhiteSpace([string]$record.name)) {
            $map[[string]$record.name] = [string]$record.id
        }
    }
    return $map
}

function Get-HudaExistingIntentMap {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [string]$Proxy
    )
    $tree = Get-HudaApiData -Response (Invoke-HudaApiJson -Method GET -Uri "$($BaseUrl.TrimEnd('/'))/intent-tree/trees" -Headers $Headers -Proxy $Proxy)
    $map = @{}
    function Add-Nodes($items) {
        foreach ($item in @($items)) {
            if ($null -ne $item -and -not [string]::IsNullOrWhiteSpace([string]$item.intentCode)) {
                $map[[string]$item.intentCode] = $item
            }
            $childrenProperty = if ($null -ne $item) { $item.PSObject.Properties['children'] } else { $null }
            if ($null -ne $childrenProperty -and $null -ne $childrenProperty.Value) { Add-Nodes $childrenProperty.Value }
        }
    }
    Add-Nodes $tree
    return $map
}

function ConvertTo-HudaCreatePayload {
    param([Parameter(Mandatory)]$Node)
    return [ordered]@{
        kbId = $Node.kbId
        intentCode = $Node.intentCode
        name = $Node.name
        level = $Node.level
        parentCode = $Node.parentCode
        description = $Node.description
        examples = @($Node.examples)
        topK = $Node.topK
        kind = $Node.kind
        sortOrder = $Node.sortOrder
        enabled = $Node.enabled
    }
}

function ConvertTo-HudaUpdatePayload {
    param([Parameter(Mandatory)]$Node)
    return [ordered]@{
        name = $Node.name
        level = $Node.level
        parentCode = $Node.parentCode
        description = $Node.description
        examples = @($Node.examples)
        topK = $Node.topK
        kind = $Node.kind
        sortOrder = $Node.sortOrder
        enabled = $Node.enabled
    }
}

function Invoke-HudaIntentTreeImport {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][object[]]$Plan,
        [switch]$Apply,
        [switch]$UpdateExisting,
        [string]$Proxy
    )
    $existing = Get-HudaExistingIntentMap -BaseUrl $BaseUrl -Headers $Headers -Proxy $Proxy
    $results = [System.Collections.Generic.List[object]]::new()
    foreach ($node in $Plan) {
        if ($existing.ContainsKey([string]$node.intentCode)) {
            if (-not $UpdateExisting) {
                $null = $results.Add([pscustomobject]@{ Action = 'SKIP'; IntentCode = $node.intentCode; Message = 'already exists' })
                continue
            }
            $existingNode = $existing[[string]$node.intentCode]
            $uri = "$($BaseUrl.TrimEnd('/'))/intent-tree/$($existingNode.id)"
            if ($Apply) {
                Invoke-HudaApiJson -Method PUT -Uri $uri -Headers $Headers -Body (ConvertTo-HudaUpdatePayload -Node $node) -Proxy $Proxy | Out-Null
            }
            $null = $results.Add([pscustomobject]@{ Action = if ($Apply) { 'UPDATE' } else { 'DRY-UPDATE' }; IntentCode = $node.intentCode; Message = $uri })
            continue
        }
        $uri = "$($BaseUrl.TrimEnd('/'))/intent-tree"
        if ($Apply) {
            Invoke-HudaApiJson -Method POST -Uri $uri -Headers $Headers -Body (ConvertTo-HudaCreatePayload -Node $node) -Proxy $Proxy | Out-Null
        }
        $null = $results.Add([pscustomobject]@{ Action = if ($Apply) { 'CREATE' } else { 'DRY-CREATE' }; IntentCode = $node.intentCode; Message = $uri })
    }
    return @($results)
}

Export-ModuleMember -Function Get-HudaCorpusInventory, Get-HudaIntentPlan, Get-HudaKnowledgeBaseMap, Invoke-HudaIntentTreeImport
