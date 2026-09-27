package com.hnu.ragent.core.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.hnu.ragent.core.chunk.VectorChunk;
import com.hnu.ragent.core.parser.model.AssetRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Aggregates compatible block-aware chunks while preserving structural boundaries. */
@Component
public class StructuredChunkAggregator {

    private static final String PARAGRAPH = "PARAGRAPH";
    private static final String IMAGE = "IMAGE";
    private static final String TABLE = "TABLE";
    private static final String CODE = "CODE";
    private static final String LIST = "LIST";
    private static final String SEPARATOR = "\n\n";

    public List<VectorChunk> aggregate(List<VectorChunk> chunks, BlockChunkConfig config) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Group> groups = new ArrayList<>();
        Group current = null;
        for (VectorChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            // 正文为空的块若带资产（无描述图片）仍需保留：它是检索结果里图片的唯一载体，
            // 直接丢弃等于把这张图从知识库里抹掉
            if (textOf(chunk).isEmpty() && (chunk.getAssets() == null || chunk.getAssets().isEmpty())) {
                continue;
            }
            if (isBarrier(chunk)) {
                if (current != null) {
                    groups.add(current);
                    current = null;
                }
                groups.add(new Group(List.of(chunk)));
                continue;
            }
            if (current == null) {
                current = new Group(List.of(chunk));
                continue;
            }
            if (!sameOutline(current.first(), chunk) || !compatible(current, chunk)
                    || shouldStopAtTarget(current, chunk, config)
                    || !canAppend(current, chunk, config)) {
                groups.add(current);
                current = new Group(List.of(chunk));
            } else {
                current = current.append(chunk);
            }
        }
        if (current != null) {
            groups.add(current);
        }
        mergeShortTail(groups, config);
        List<VectorChunk> result = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            result.add(materialize(groups.get(i), i));
        }
        return result;
    }

    /**
     * 短尾合并：不足最小体量的余量并回前一块，避免产出召不回也白占名额的碎块
     * <p>
     * 与常规合并共用同一套边界约束，且额外排除原子块：
     * - <b>不跨提纲</b>：块只带一条 {@code outlinePath}，把 A 节的内容并进 B 节的块会让归属与引用都错，
     *   宁可保留一个短块；
     * - <b>不动原子块</b>：代码 / 表格既不该被并进文本块，也不该吞掉相邻文本。
     */
    private void mergeShortTail(List<Group> groups, BlockChunkConfig config) {
        for (int i = groups.size() - 1; i > 0; i--) {
            Group tail = groups.get(i);
            Group previous = groups.get(i - 1);
            if (tail.length() < config.minChars()
                    && !isBarrier(previous.last())
                    && !isBarrier(tail.first())
                    && sameOutline(previous.last(), tail.first())
                    && compatible(previous, tail.first())
                    && canAppend(previous, tail.first(), config)) {
                groups.set(i - 1, previous.appendAll(tail));
                groups.remove(i);
            }
        }
    }

    private boolean isBarrier(VectorChunk chunk) {
        return !PARAGRAPH.equals(chunk.getBlockType()) && !IMAGE.equals(chunk.getBlockType());
    }

    private boolean compatible(Group group, VectorChunk next) {
        String type = group.first().getBlockType();
        if (PARAGRAPH.equals(type) && PARAGRAPH.equals(next.getBlockType())) {
            return true;
        }
        if (IMAGE.equals(next.getBlockType())) {
            // 图片无论有无描述都可并入文本块：无描述图片文本量太少，单独成块既召不回也白占一个 TopK 名额，
            // 并进相邻段落至少让它的资产（图 URL）随块进入检索结果
            return group.hasParagraph();
        }
        if (PARAGRAPH.equals(next.getBlockType()) && group.hasImage()) {
            return true;
        }
        return false;
    }

    private boolean sameOutline(VectorChunk left, VectorChunk right) {
        return Objects.equals(normalize(left.getOutlinePath()), normalize(right.getOutlinePath()));
    }

    private boolean shouldStopAtTarget(Group group, VectorChunk next, BlockChunkConfig config) {
        if (group.length() < config.minChars() || group.length() >= config.targetChars()) {
            return group.length() >= config.targetChars();
        }
        int candidateLength = group.length() + SEPARATOR.length() + next.getContent().length();
        if (candidateLength > config.maxChars()) {
            return false;
        }
        return candidateLength > config.targetChars()
                && config.targetChars() - group.length() <= candidateLength - config.targetChars();
    }

    private boolean canAppend(Group group, VectorChunk next, BlockChunkConfig config) {
        int length = group.length() + SEPARATOR.length() + next.getContent().length();
        return length <= config.maxChars();
    }

    private boolean hasDescription(VectorChunk chunk) {
        return chunk.getEmbeddingText() != null && !chunk.getEmbeddingText().isBlank();
    }

    private VectorChunk materialize(Group group, int index) {
        List<VectorChunk> parts = group.parts();
        StringBuilder content = new StringBuilder();
        StringBuilder embedding = new StringBuilder();
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        LinkedHashSet<AssetRef> assets = new LinkedHashSet<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<String> sectionContexts = new ArrayList<>();
        boolean hasEmbedding = false;
        for (VectorChunk part : parts) {
            String partContent = textOf(part);
            if (!partContent.isEmpty()) {
                if (!content.isEmpty()) content.append(SEPARATOR);
                content.append(partContent);
            }
            String embeddingText = part.getEmbeddingText();
            if (embeddingText != null && !embeddingText.isBlank()) {
                if (!embedding.isEmpty()) embedding.append(SEPARATOR);
                embedding.append(embeddingText);
                hasEmbedding = true;
            } else if (parts.size() > 1) {
                if (!partContent.isEmpty()) {
                    if (!embedding.isEmpty()) embedding.append(SEPARATOR);
                    embedding.append(partContent);
                    hasEmbedding = true;
                }
            }
            if (part.getSourceBlockIds() != null) sourceIds.addAll(part.getSourceBlockIds());
            if (part.getAssets() != null) assets.addAll(part.getAssets());
            if (part.getMetadata() != null) metadata.putAll(part.getMetadata());
            if (part.getSectionContext() != null && !part.getSectionContext().isBlank()
                    && !sectionContexts.contains(part.getSectionContext())) {
                sectionContexts.add(part.getSectionContext());
            }
        }
        String type = group.hasDescribedImage() ? PARAGRAPH : parts.get(0).getBlockType();
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(index)
                .content(content.toString())
                .embeddingText(hasEmbedding ? embedding.toString() : parts.get(0).getEmbeddingText())
                .metadata(metadata)
                .assets(new ArrayList<>(assets))
                .blockType(type)
                .outlinePath(new ArrayList<>(normalize(parts.get(0).getOutlinePath())))
                .sourceBlockIds(new ArrayList<>(sourceIds))
                .sectionContext(sectionContexts.isEmpty() ? parts.get(0).getSectionContext()
                        : String.join("\n", sectionContexts))
                .build();
    }

    private static List<String> normalize(List<String> path) {
        return path == null ? List.of() : path;
    }

    /**
     * 块的展示文本，null 视为空串（无描述图片的 content 可能为空）
     */
    private static String textOf(VectorChunk chunk) {
        return chunk.getContent() == null ? "" : chunk.getContent();
    }

    private record Group(List<VectorChunk> parts) {
        Group {
            parts = List.copyOf(parts);
        }

        VectorChunk first() {
            return parts.get(0);
        }

        int length() {
            return parts.stream().mapToInt(c -> textOf(c).length()).sum()
                    + Math.max(0, parts.size() - 1) * SEPARATOR.length();
        }

        boolean hasParagraph() {
            return parts.stream().anyMatch(c -> PARAGRAPH.equals(c.getBlockType()));
        }

        boolean hasDescribedImage() {
            return parts.stream().anyMatch(c -> IMAGE.equals(c.getBlockType())
                    && c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank());
        }

        boolean hasImage() {
            return parts.stream().anyMatch(c -> IMAGE.equals(c.getBlockType()));
        }

        VectorChunk last() {
            return parts.get(parts.size() - 1);
        }

        Group append(VectorChunk chunk) {
            List<VectorChunk> copy = new ArrayList<>(parts);
            copy.add(chunk);
            return new Group(copy);
        }

        Group appendAll(Group other) {
            List<VectorChunk> copy = new ArrayList<>(parts);
            copy.addAll(other.parts);
            return new Group(copy);
        }
    }
}
