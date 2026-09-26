import { useEffect, useState } from "react";
import { Brain, RefreshCw, Trash2, Wrench } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  getAgentDashboard,
  forgetAgentMemory,
  getAgentMemories,
  getAgentSkills,
  getAgentTools,
  type AgentMemory,
  type AgentDashboard,
  type AgentSkill,
  type AgentTool
} from "@/services/agentService";
import { getErrorMessage } from "@/utils/error";

const Metric = ({ label, value }: { label: string; value: string | number }) => (
  <div className="rounded-lg border border-slate-200/70 bg-white px-3 py-2">
    <div className="text-xs text-muted-foreground">{label}</div>
    <div className="truncate text-sm font-medium text-slate-800" title={String(value)}>
      {value}
    </div>
  </div>
);

export function AgentAdminPage() {
  const [tools, setTools] = useState<AgentTool[]>([]);
  const [skills, setSkills] = useState<AgentSkill[]>([]);
  const [memories, setMemories] = useState<AgentMemory[]>([]);
  const [dashboard, setDashboard] = useState<AgentDashboard | null>(null);
  const [loading, setLoading] = useState(true);

  const loadAll = async () => {
    setLoading(true);
    try {
      const [toolList, skillList, memoryList] = await Promise.all([
        getAgentTools(),
        getAgentSkills(),
        getAgentMemories()
      ]);
      setTools(toolList || []);
      setSkills(skillList || []);
      setMemories(memoryList || []);
      setDashboard(await getAgentDashboard(7));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 Agent 配置失败（请确认 ai.agent.enabled=true）"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const handleForget = async (id: string) => {
    try {
      await forgetAgentMemory(id);
      toast.success("已忘掉这条记忆");
      setMemories((prev) => prev.filter((memory) => memory.id !== id));
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">Agent 配置</h1>
          <p className="admin-page-subtitle">工具能力、技能手册与长期记忆的只读视图（记忆可删除）</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={loadAll}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>近 7 天运行指标</CardTitle>
          <CardDescription>
            只统计你自己的数据：会话与消息数、结束状态分布、平均耗时、工具使用次数与平均耗时
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {dashboard == null ? (
            <div className="text-sm text-muted-foreground">暂无指标（Agent 未启用或尚无运行记录）</div>
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
                <Metric label="会话数" value={dashboard.conversations} />
                <Metric label="消息数" value={dashboard.messages} />
                <Metric label="回答数" value={dashboard.assistantMessages} />
                <Metric label="平均耗时" value={`${dashboard.avgDurationMs} ms`} />
                <Metric label="生效记忆" value={dashboard.activeMemories} />
                <Metric
                  label="状态分布"
                  value={Object.entries(dashboard.statusCounts || {})
                    .map(([status, count]) => `${status}:${count}`)
                    .join(" / ") || "-"}
                />
              </div>
              <div>
                <div className="mb-1 text-xs font-medium text-muted-foreground">工具使用</div>
                {dashboard.toolUsage.length === 0 ? (
                  <div className="text-sm text-muted-foreground">窗口内没有工具调用</div>
                ) : (
                  <Table className="min-w-[520px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[240px]">工具</TableHead>
                        <TableHead className="w-[120px]">调用次数</TableHead>
                        <TableHead>平均耗时</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {dashboard.toolUsage.map((usage) => (
                        <TableRow key={usage.toolId}>
                          <TableCell className="font-medium">{usage.toolId}</TableCell>
                          <TableCell>{usage.calls}</TableCell>
                          <TableCell>{usage.avgLatencyMs} ms</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>可用工具</CardTitle>
          <CardDescription>
            非只读工具在执行前需要人工确认；MCP 工具默认按写操作处理，确认只读后可在
            ai.agent.read-only-tools 登记
          </CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="py-6 text-center text-muted-foreground">加载中...</div>
          ) : tools.length === 0 ? (
            <div className="py-6 text-center text-muted-foreground">当前没有可用工具</div>
          ) : (
            <Table className="min-w-[900px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[200px]">工具</TableHead>
                  <TableHead className="w-[100px]">来源</TableHead>
                  <TableHead className="w-[110px]">读写</TableHead>
                  <TableHead className="w-[110px]">需确认</TableHead>
                  <TableHead>说明</TableHead>
                  <TableHead className="w-[260px]">参数</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tools.map((tool) => (
                  <TableRow key={tool.id}>
                    <TableCell className="font-medium">
                      <div className="flex items-center gap-2">
                        <Wrench className="h-4 w-4 text-slate-400" />
                        {tool.id}
                      </div>
                      <div className="text-xs text-muted-foreground">{tool.name}</div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">{tool.source}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={tool.readOnly ? "default" : "outline"}>
                        {tool.readOnly ? "只读" : "写操作"}
                      </Badge>
                    </TableCell>
                    <TableCell>{tool.requiresConfirmation ? "需要" : "否"}</TableCell>
                    <TableCell className="text-muted-foreground">{tool.description}</TableCell>
                    <TableCell className="space-y-1 text-xs text-muted-foreground">
                      {tool.parameters.length === 0 ? (
                        <span>-</span>
                      ) : (
                        tool.parameters.map((parameter) => (
                          <div key={parameter.name}>
                            {parameter.name}（{parameter.type}
                            {parameter.required ? "，必填" : ""}）：{parameter.description}
                          </div>
                        ))
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>技能手册</CardTitle>
          <CardDescription>触发词命中时注入提示词；手册随代码版本发布</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {skills.length === 0 ? (
            <div className="text-sm text-muted-foreground">未加载任何技能手册</div>
          ) : (
            skills.map((skill) => (
              <div key={skill.name} className="rounded-lg border px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{skill.name}</span>
                  {skill.triggers.map((trigger) => (
                    <Badge key={trigger} variant="secondary">
                      {trigger}
                    </Badge>
                  ))}
                </div>
                <div className="mt-1 text-xs text-muted-foreground">{skill.description}</div>
                <pre className="mt-2 max-h-[220px] overflow-auto whitespace-pre-wrap rounded bg-slate-50 p-3 text-xs text-slate-700">
                  {skill.content}
                </pre>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>我的长期记忆</CardTitle>
          <CardDescription>
            Agent 跨会话记住的用户事实；删除后不再参与召回（表中保留失效记录，便于回溯）
          </CardDescription>
        </CardHeader>
        <CardContent>
          {memories.length === 0 ? (
            <div className="text-sm text-muted-foreground">
              暂无记忆（记忆默认关闭，需把 ai.agent.memory.enabled 置为 true）
            </div>
          ) : (
            <Table className="min-w-[720px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[120px]">类型</TableHead>
                  <TableHead>内容</TableHead>
                  <TableHead className="w-[180px]">记录时间</TableHead>
                  <TableHead className="w-[100px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {memories.map((memory) => (
                  <TableRow key={memory.id}>
                    <TableCell>
                      <Badge variant="secondary">
                        <Brain className="mr-1 h-3 w-3" />
                        {memory.sourceType}
                      </Badge>
                    </TableCell>
                    <TableCell>{memory.content}</TableCell>
                    <TableCell className="text-muted-foreground">{memory.createTime || "-"}</TableCell>
                    <TableCell className="text-left">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => handleForget(memory.id)}
                      >
                        <Trash2 className="mr-0.5 h-4 w-4" />
                        忘掉
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
