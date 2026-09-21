package com.nageoffer.ai.ragent.core.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
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
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isEmpty()) {
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

    private void mergeShortTail(List<Group> groups, BlockChunkConfig config) {
        for (int i = groups.size() - 1; i > 0; i--) {
            Group tail = groups.get(i);
            Group previous = groups.get(i - 1);
            if (tail.length() < config.minChars()
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
            return hasDescription(next) && group.hasParagraph();
        }
        if (PARAGRAPH.equals(next.getBlockType()) && group.hasDescribedImage()) {
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
            if (!content.isEmpty()) content.append(SEPARATOR);
            content.append(part.getContent());
            String embeddingText = part.getEmbeddingText();
            if (embeddingText != null && !embeddingText.isBlank()) {
                if (!embedding.isEmpty()) embedding.append(SEPARATOR);
                embedding.append(embeddingText);
                hasEmbedding = true;
            } else if (parts.size() > 1) {
                if (!embedding.isEmpty()) embedding.append(SEPARATOR);
                embedding.append(part.getContent());
                hasEmbedding = true;
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

    private record Group(List<VectorChunk> parts) {
        Group {
            parts = List.copyOf(parts);
        }

        VectorChunk first() {
            return parts.get(0);
        }

        int length() {
            return parts.stream().mapToInt(c -> c.getContent().length()).sum()
                    + Math.max(0, parts.size() - 1) * SEPARATOR.length();
        }

        boolean hasParagraph() {
            return parts.stream().anyMatch(c -> PARAGRAPH.equals(c.getBlockType()));
        }

        boolean hasDescribedImage() {
            return parts.stream().anyMatch(c -> IMAGE.equals(c.getBlockType())
                    && c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank());
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
