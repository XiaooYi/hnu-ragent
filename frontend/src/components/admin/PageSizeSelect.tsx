import { PAGE_SIZE_OPTIONS, type PageSize } from "@/hooks/usePageSize";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

interface PageSizeSelectProps {
  value: PageSize;
  onValueChange: (value: number) => void;
  disabled?: boolean;
}

export function PageSizeSelect({ value, onValueChange, disabled = false }: PageSizeSelectProps) {
  return (
    <div className="flex items-center gap-2 whitespace-nowrap">
      <span>每页显示</span>
      <Select value={String(value)} onValueChange={(nextValue) => onValueChange(Number(nextValue))} disabled={disabled}>
        <SelectTrigger className="h-8 w-[96px]" aria-label="每页展示条数">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {PAGE_SIZE_OPTIONS.map((size) => (
            <SelectItem key={size} value={String(size)}>
              {size} 条
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
