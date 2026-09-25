import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import type { SystemSettings } from "@/services/settingsService";
import { getSystemSettings } from "@/services/settingsService";
import { tierRows } from "@/lib/settingsTiers";
import { backendRows, channelRows, featureFlags, pipelineRows } from "@/lib/settingsRetrieval";
import { getErrorMessage } from "@/utils/error";

const BoolBadge = ({ value }: { value: boolean }) => (
  <Badge variant={value ? "default" : "outline"}>{value ? "启用" : "禁用"}</Badge>
);

function InfoItem({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-slate-200/70 bg-white px-4 py-3">
      <span className="text-xs text-slate-500">{label}</span>
      <div className="text-sm font-medium text-slate-800">{value}</div>
    </div>
  );
}

export function SystemSettingsPage() {
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [loading, setLoading] = useState(true);

  const loadSettings = async () => {
    try {
      setLoading(true);
      const data = await getSystemSettings();
      setSettings(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载系统配置失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  if (loading) {
    return (
      <div className="admin-page">
        <div className="text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!settings) {
    return (
      <div className="admin-page">
        <div className="text-sm text-muted-foreground">暂无可展示的配置</div>
      </div>
    );
  }

  const { rag, ai, backends } = settings;
  const providers = Object.entries(ai.providers || {});
  const features = featureFlags(rag.features);
  const channels = channelRows(rag.search);
  const pipeline = pipelineRows(rag.search);
  const backendItems = backendRows(backends);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">系统配置</h1>
          <p className="admin-page-subtitle">只读展示当前 application 配置</p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>RAG 默认配置</CardTitle>
          <CardDescription>向量空间与检索基础参数</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Collection" value={rag.default.collectionName} />
          <InfoItem label="Dimension" value={rag.default.dimension} />
          <InfoItem label="Metric Type" value={rag.default.metricType} />
          <InfoItem label="SSE Timeout (ms)" value={rag.default.sseTimeoutMs ?? "-"} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>后端选型</CardTitle>
          <CardDescription>检索与存储当前使用的实现（只读，不含任何凭据）</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          {backendItems.length === 0 ? (
            <div className="text-sm text-muted-foreground">接口未返回后端选型信息</div>
          ) : (
            backendItems.map((item) => <InfoItem key={item.label} label={item.label} value={item.value} />)
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>能力开关</CardTitle>
          <CardDescription>影响检索与回答组装的全局开关</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {features.length === 0 ? (
            <div className="text-sm text-muted-foreground">接口未返回能力开关</div>
          ) : (
            features.map((item) => (
              <div
                key={item.key}
                className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200/70 bg-white px-4 py-3"
              >
                <div className="min-w-0">
                  <div className="text-sm font-medium text-slate-800">{item.label}</div>
                  <div className="text-xs text-slate-500">{item.hint}</div>
                </div>
                <BoolBadge value={item.enabled} />
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>检索管线</CardTitle>
          <CardDescription>线上真正生效的通道、融合与证据闸门参数</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            {pipeline.map((item) => (
              <InfoItem key={item.label} label={item.label} value={item.value} />
            ))}
          </div>
          {channels.length === 0 ? (
            <div className="text-sm text-muted-foreground">接口未返回检索通道信息</div>
          ) : (
            <Table className="min-w-[720px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">通道</TableHead>
                  <TableHead className="w-[100px]">状态</TableHead>
                  <TableHead>关键阈值</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {channels.map((row) => (
                  <TableRow key={row.name}>
                    <TableCell className="font-medium">{row.name}</TableCell>
                    <TableCell>{row.enabled}</TableCell>
                    <TableCell className="text-muted-foreground">{row.detail}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>查询改写</CardTitle>
          <CardDescription>历史上下文压缩与改写策略</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Enabled" value={<BoolBadge value={rag.queryRewrite.enabled} />} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>全局限流</CardTitle>
          <CardDescription>并发与租约控制</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="Enabled" value={<BoolBadge value={rag.rateLimit.global.enabled} />} />
          <InfoItem label="Max Concurrent" value={rag.rateLimit.global.maxConcurrent} />
          <InfoItem label="Max Wait Seconds" value={rag.rateLimit.global.maxWaitSeconds} />
          <InfoItem label="Lease Seconds" value={rag.rateLimit.global.leaseSeconds} />
          <InfoItem label="Poll Interval (ms)" value={rag.rateLimit.global.pollIntervalMs} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>记忆管理</CardTitle>
          <CardDescription>摘要与上下文保留策略</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="History Keep Turns" value={rag.memory.historyKeepTurns} />
          <InfoItem label="Summary Start Turns" value={rag.memory.summaryStartTurns} />
          <InfoItem
            label="Summary Enabled"
            value={<BoolBadge value={rag.memory.summaryEnabled} />}
          />
          <InfoItem label="Summary Max Chars" value={rag.memory.summaryMaxChars} />
          <InfoItem label="Title Max Length" value={rag.memory.titleMaxLength} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>模型服务提供方</CardTitle>
          <CardDescription>接入地址与端点配置</CardDescription>
        </CardHeader>
        <CardContent>
          <Table className="min-w-[760px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[140px]">Provider</TableHead>
                <TableHead className="w-[240px]">URL</TableHead>
                <TableHead className="w-[200px]">API Key</TableHead>
                <TableHead>Endpoints</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {providers.map(([name, provider]) => (
                <TableRow key={name}>
                  <TableCell className="font-medium">{name}</TableCell>
                  <TableCell>{provider.url}</TableCell>
                  <TableCell>{provider.apiKey ? provider.apiKey : "-"}</TableCell>
                  <TableCell>
                    <div className="space-y-1 text-xs text-muted-foreground">
                      {Object.entries(provider.endpoints).map(([key, value]) => (
                        <div key={key}>
                          {key}: {value}
                        </div>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>模型选择策略</CardTitle>
          <CardDescription>熔断与选择阈值</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <InfoItem label="Failure Threshold" value={ai.selection.failureThreshold} />
          <InfoItem label="Open Duration (ms)" value={ai.selection.openDurationMs} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>流式响应</CardTitle>
          <CardDescription>输出分片大小</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <InfoItem label="Message Chunk Size" value={ai.stream.messageChunkSize} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Chat 模型配置</CardTitle>
          <CardDescription>档位路由与候选注册表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Tier" value={ai.chat.defaultTier ?? "-"} />
            <InfoItem label="Deep Thinking Tier" value={ai.chat.deepThinkingTier ?? "-"} />
          </div>
          <div className="space-y-2">
            <div className="text-xs font-medium text-muted-foreground">
              档位（Tiers）：每个档位的候选按序回退，Timeout 为该档位的调用预算（流式为首包预算）
            </div>
            <Table className="min-w-[560px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[140px]">Tier</TableHead>
                  <TableHead>候选（有序）</TableHead>
                  <TableHead className="w-[160px]">Timeout (ms/候选)</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tierRows(ai.chat.tiers).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={3} className="text-muted-foreground">
                      未配置档位
                    </TableCell>
                  </TableRow>
                ) : (
                  tierRows(ai.chat.tiers).map((row) => (
                    <TableRow key={row.name}>
                      <TableCell className="font-medium">{row.name}</TableCell>
                      <TableCell>{row.candidates}</TableCell>
                      <TableCell>{row.timeout}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
          <div className="space-y-2">
            <div className="text-xs font-medium text-muted-foreground">
              候选注册表（Candidates）：chat 组的 Default Model / Deep Thinking Model 为兼容字段，不再参与路由
              {ai.chat.defaultModel ? `（default-model: ${ai.chat.defaultModel}）` : ""}
            </div>
          <Table className="min-w-[720px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[100px]">Thinking</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.chat.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.supportsThinking ? "支持" : "-"}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Embedding 模型配置</CardTitle>
          <CardDescription>向量化模型列表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Model" value={ai.embedding.defaultModel} />
          </div>
          <Table className="min-w-[720px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[110px]">Dimension</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.embedding.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.dimension}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Rerank 模型配置</CardTitle>
          <CardDescription>重排模型列表</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <InfoItem label="Default Model" value={ai.rerank.defaultModel} />
          </div>
          <Table className="min-w-[640px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[220px]">ID</TableHead>
                <TableHead className="w-[120px]">Provider</TableHead>
                <TableHead className="w-[200px]">Model</TableHead>
                <TableHead className="w-[90px]">Priority</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ai.rerank.candidates.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">{item.id}</TableCell>
                  <TableCell>{item.provider}</TableCell>
                  <TableCell>{item.model}</TableCell>
                  <TableCell>{item.priority}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
