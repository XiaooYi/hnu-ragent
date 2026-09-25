import { useEffect, useState } from "react";
import { History, RefreshCw, Search } from "lucide-react";
import { toast } from "sonner";

import { PageSizeSelect } from "@/components/admin/PageSizeSelect";
import { RelativeTime } from "@/components/RelativeTime";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  BIZ_TYPE_OPTIONS,
  OPERATION_TYPE_OPTIONS,
  bizTypeLabel,
  formatDiffValue,
  operationTypeLabel,
  parseChangeDiff,
  summarizeChangeDiff,
  toQueryDateTime
} from "@/lib/changeLogDiff";
import { getBizChangeLogs, type BizChangeLog, type PageResult } from "@/services/bizChangeLogService";
import { usePageSize } from "@/hooks/usePageSize";
import { getErrorMessage } from "@/utils/error";

const ALL = "ALL";

const prettyJson = (raw?: string | null) => {
  if (!raw) return "-";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

export function BizChangeLogPage() {
  const [pageSize, setPageSize] = usePageSize("biz-change-logs");
  const [pageNo, setPageNo] = useState(1);
  const [pageData, setPageData] = useState<PageResult<BizChangeLog> | null>(null);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<BizChangeLog | null>(null);

  const [filters, setFilters] = useState({
    bizType: ALL,
    operationType: ALL,
    success: ALL,
    bizId: "",
    operatorName: "",
    beginTime: "",
    endTime: ""
  });
  const [query, setQuery] = useState(filters);

  const loadData = async (current = pageNo, nextQuery = query) => {
    setLoading(true);
    try {
      const result = await getBizChangeLogs({
        current,
        size: pageSize,
        bizType: nextQuery.bizType === ALL ? undefined : nextQuery.bizType,
        operationType: nextQuery.operationType === ALL ? undefined : nextQuery.operationType,
        success: nextQuery.success === ALL ? undefined : nextQuery.success === "true",
        bizId: nextQuery.bizId.trim() || undefined,
        operatorName: nextQuery.operatorName.trim() || undefined,
        beginTime: toQueryDateTime(nextQuery.beginTime),
        endTime: toQueryDateTime(nextQuery.endTime)
      });
      setPageData(result);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载变更记录失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNo, pageSize, query]);

  const handleSearch = () => {
    setPageNo(1);
    setQuery({ ...filters });
  };

  const handleReset = () => {
    const reset = {
      bizType: ALL,
      operationType: ALL,
      success: ALL,
      bizId: "",
      operatorName: "",
      beginTime: "",
      endTime: ""
    };
    setFilters(reset);
    setPageNo(1);
    setQuery(reset);
  };

  const records = pageData?.records || [];
  const detailDiff = parseChangeDiff(detail?.changeDiff);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">变更记录</h1>
          <p className="admin-page-subtitle">知识库、文档、意图等写操作的操作人与字段级变更留痕</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => loadData()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="space-y-4 pt-6">
          <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-4">
            <Select
              value={filters.bizType}
              onValueChange={(value) => setFilters((prev) => ({ ...prev, bizType: value }))}
            >
              <SelectTrigger aria-label="业务类型">
                <SelectValue placeholder="业务类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部业务类型</SelectItem>
                {BIZ_TYPE_OPTIONS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {bizTypeLabel(type)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Select
              value={filters.operationType}
              onValueChange={(value) => setFilters((prev) => ({ ...prev, operationType: value }))}
            >
              <SelectTrigger aria-label="操作类型">
                <SelectValue placeholder="操作类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部操作类型</SelectItem>
                {OPERATION_TYPE_OPTIONS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {operationTypeLabel(type)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Select
              value={filters.success}
              onValueChange={(value) => setFilters((prev) => ({ ...prev, success: value }))}
            >
              <SelectTrigger aria-label="操作结果">
                <SelectValue placeholder="操作结果" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部结果</SelectItem>
                <SelectItem value="true">成功</SelectItem>
                <SelectItem value="false">失败</SelectItem>
              </SelectContent>
            </Select>

            <Input
              value={filters.bizId}
              onChange={(event) => setFilters((prev) => ({ ...prev, bizId: event.target.value }))}
              onKeyDown={(event) => event.key === "Enter" && handleSearch()}
              placeholder="业务主键（模糊）"
            />

            <Input
              value={filters.operatorName}
              onChange={(event) => setFilters((prev) => ({ ...prev, operatorName: event.target.value }))}
              onKeyDown={(event) => event.key === "Enter" && handleSearch()}
              placeholder="操作人（模糊）"
            />

            <Input
              type="datetime-local"
              value={filters.beginTime}
              onChange={(event) => setFilters((prev) => ({ ...prev, beginTime: event.target.value }))}
              aria-label="开始时间"
            />

            <Input
              type="datetime-local"
              value={filters.endTime}
              onChange={(event) => setFilters((prev) => ({ ...prev, endTime: event.target.value }))}
              aria-label="结束时间"
            />

            <div className="flex gap-2">
              <Button variant="outline" onClick={handleSearch}>
                <Search className="mr-2 h-4 w-4" />
                查询
              </Button>
              <Button variant="ghost" onClick={handleReset}>
                重置
              </Button>
            </div>
          </div>

          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无变更记录</div>
          ) : (
            <Table className="min-w-[1080px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[170px]">时间</TableHead>
                  <TableHead className="w-[130px]">业务类型</TableHead>
                  <TableHead className="w-[160px]">业务主键</TableHead>
                  <TableHead className="w-[90px]">操作</TableHead>
                  <TableHead>操作描述</TableHead>
                  <TableHead className="w-[140px]">操作人</TableHead>
                  <TableHead className="w-[90px]">结果</TableHead>
                  <TableHead className="w-[110px] text-left">详情</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell>
                      <RelativeTime value={item.createTime} />
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">{bizTypeLabel(item.bizType)}</Badge>
                    </TableCell>
                    <TableCell className="max-w-[160px] truncate" title={item.bizId}>
                      {item.bizId}
                    </TableCell>
                    <TableCell>{operationTypeLabel(item.operationType)}</TableCell>
                    <TableCell className="max-w-[360px] truncate" title={item.actionDesc || ""}>
                      {item.actionDesc || "-"}
                    </TableCell>
                    <TableCell className="max-w-[140px] truncate" title={item.operatorName || ""}>
                      {item.operatorName || item.operatorId || "-"}
                    </TableCell>
                    <TableCell>
                      <Badge variant={item.success === false ? "destructive" : "default"}>
                        {item.success === false ? "失败" : "成功"}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-left">
                      <Button variant="outline" size="sm" onClick={() => setDetail(item)}>
                        <History className="mr-0.5 h-4 w-4" />
                        查看
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <PageSizeSelect
              value={pageSize}
              onValueChange={(value) => {
                setPageSize(value);
                setPageNo(1);
              }}
            />
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={pageData.current <= 1}
            >
              上一页
            </Button>
            <span>
              {pageData.current} / {pageData.pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => prev + 1)}
              disabled={pageData.current >= pageData.pages}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      <Dialog open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-[860px]">
          <DialogHeader>
            <DialogTitle>变更详情</DialogTitle>
            <DialogDescription>{detail?.actionDesc || "-"}</DialogDescription>
          </DialogHeader>
          {detail ? (
            <div className="space-y-4 text-sm">
              <div className="grid gap-3 md:grid-cols-2">
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">业务对象</div>
                  <div className="font-medium">
                    {bizTypeLabel(detail.bizType)} · {detail.bizId}
                  </div>
                </div>
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">操作 / 结果</div>
                  <div className="font-medium">
                    {operationTypeLabel(detail.operationType)} · {detail.success === false ? "失败" : "成功"}
                  </div>
                </div>
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">操作人</div>
                  <div className="font-medium">
                    {detail.operatorName || "-"}（{detail.operatorRole || "-"} / {detail.operatorId || "-"}）
                  </div>
                </div>
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">触发位置</div>
                  <div className="font-medium break-all">
                    {detail.className || "-"}#{detail.methodName || "-"}
                  </div>
                </div>
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">来源</div>
                  <div className="font-medium break-all">
                    {detail.ip || "-"} · {detail.userAgent || "-"}
                  </div>
                </div>
                <div className="rounded-lg border px-3 py-2">
                  <div className="text-xs text-muted-foreground">时间</div>
                  <div className="font-medium">{detail.createTime || "-"}</div>
                </div>
              </div>

              {detail.success === false ? (
                <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-rose-700">
                  {detail.errorMessage || "操作失败"}
                </div>
              ) : null}

              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">
                  字段差异（{summarizeChangeDiff(detailDiff)}）
                </div>
                {detailDiff.length === 0 ? (
                  <div className="text-muted-foreground">本次操作没有字段级变化</div>
                ) : (
                  <Table className="min-w-[640px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[220px]">字段</TableHead>
                        <TableHead>变更前</TableHead>
                        <TableHead>变更后</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {detailDiff.map((row, index) => (
                        <TableRow key={`${row.field}-${index}`}>
                          <TableCell className="font-mono text-xs">{row.field}</TableCell>
                          <TableCell className="break-all">{formatDiffValue(row.before)}</TableCell>
                          <TableCell className="break-all">{formatDiffValue(row.after)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>

              <details className="space-y-2">
                <summary className="cursor-pointer text-xs font-medium text-muted-foreground">
                  原始快照 JSON
                </summary>
                <div className="grid gap-3 md:grid-cols-2">
                  <div>
                    <div className="mb-1 text-xs text-muted-foreground">变更前</div>
                    <pre className="max-h-[280px] overflow-auto rounded-lg bg-slate-950/90 p-3 text-xs text-slate-100">
                      {prettyJson(detail.beforeSnapshot)}
                    </pre>
                  </div>
                  <div>
                    <div className="mb-1 text-xs text-muted-foreground">变更后</div>
                    <pre className="max-h-[280px] overflow-auto rounded-lg bg-slate-950/90 p-3 text-xs text-slate-100">
                      {prettyJson(detail.afterSnapshot)}
                    </pre>
                  </div>
                </div>
              </details>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
