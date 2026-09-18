import type { ChunkStrategyOption, KnowledgeDocumentUploadPayload } from "@/services/knowledgeService";

interface UploadValues {
  sourceType: "file" | "url";
  sourceLocation?: string;
  scheduleEnabled: boolean;
  scheduleCron?: string;
  processMode: "chunk" | "pipeline";
  pipelineId?: string;
  chunkStrategy?: string;
  chunkSize?: string;
  overlapSize?: string;
  targetChars?: string;
  maxChars?: string;
  minChars?: string;
  overlapChars?: string;
  tableChunkSize?: string;
  rowsPerChunk?: string;
  excelParser?: string;
}

export const fileExtension = (name?: string) => name?.split(".").pop()?.toLowerCase() ?? "";
export const isTableFile = (file: File) => ["csv", "xls", "xlsx"].includes(fileExtension(file.name));

export function buildUploadPayload(
  file: File | null,
  values: UploadValues,
  strategies: ChunkStrategyOption[]
): KnowledgeDocumentUploadPayload {
  const table = values.sourceType === "file" && file !== null && isTableFile(file);
  let chunkConfig: string | null = null;
  if (values.processMode === "chunk") {
    if (table) {
      const config: Record<string, number | string> = {
        chunkSize: Number(values.tableChunkSize || 512),
        overlapSize: 0,
        rowsPerChunk: Number(values.rowsPerChunk || 50)
      };
      if (fileExtension(file.name) !== "csv") config.excelParser = values.excelParser || "poi";
      chunkConfig = JSON.stringify(config);
    } else {
      const strategy = strategies.find((item) => item.value === values.chunkStrategy);
      if (!strategy) throw new Error("分块策略尚未加载，请稍后重试");
      const config: Record<string, number> = {};
      const fields: Record<string, string | undefined> = {
        chunkSize: values.chunkSize,
        overlapSize: values.overlapSize,
        targetChars: values.targetChars,
        maxChars: values.maxChars,
        minChars: values.minChars,
        overlapChars: values.overlapChars
      };
      for (const key of Object.keys(strategy.defaultConfig)) {
        const value = fields[key];
        if (value?.trim() && Number.isFinite(Number(value))) config[key] = Number(value);
      }
      chunkConfig = JSON.stringify(config);
    }
  }
  return {
    sourceType: values.sourceType,
    file: values.sourceType === "file" ? file : null,
    sourceLocation: values.sourceType === "url" ? values.sourceLocation?.trim() : null,
    scheduleEnabled: values.sourceType === "url" && values.scheduleEnabled,
    scheduleCron: values.sourceType === "url" && values.scheduleEnabled ? values.scheduleCron?.trim() : null,
    processMode: values.processMode,
    chunkStrategy: values.processMode === "chunk" ? (table ? "fixed_size" : values.chunkStrategy) : undefined,
    chunkConfig,
    pipelineId: values.processMode === "pipeline" ? values.pipelineId : null
  };
}
