package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredChunkAggregatorTest {

    private final StructuredChunkAggregator aggregator = new StructuredChunkAggregator();
    private final BlockChunkConfig config = new BlockChunkConfig(10, 30, 40, 0, 5, 15, 10);

    @Test
    void mergesParagraphsWithinSameOutlineAndPreservesSources() {
        VectorChunk first = paragraph("p1", "abcdefgh", List.of("A"));
        VectorChunk second = paragraph("p2", "ijklmnop", List.of("A"));

        List<VectorChunk> result = aggregator.aggregate(List.of(first, second), config);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("abcdefgh\n\nijklmnop"));
        assertEquals(List.of("p1", "p2"), result.get(0).getSourceBlockIds());
        assertEquals(0, result.get(0).getIndex());
        assertNotEquals(first.getChunkId(), result.get(0).getChunkId());
    }

    @Test
    void doesNotCrossOutlineOrAtomicBoundaries() {
        VectorChunk first = paragraph("p1", "abcdefgh", List.of("A"));
        VectorChunk otherSection = paragraph("p2", "ijklmnop", List.of("B"));
        VectorChunk code = VectorChunk.builder().chunkId("code").index(2).content("code")
                .blockType("CODE").outlinePath(List.of("B")).sourceBlockIds(List.of("c1")).build();
        VectorChunk last = paragraph("p3", "qrstuvwx", List.of("B"));

        List<VectorChunk> result = aggregator.aggregate(List.of(first, otherSection, code, last), config);

        assertEquals(4, result.size());
        assertEquals(List.of(0, 1, 2, 3), result.stream().map(VectorChunk::getIndex).toList());
    }

    @Test
    void mergesDescribedImageWithParagraphAndRetainsAsset() {
        AssetRef asset = new AssetRef("https://example.test/a.png", "image/png", "img1");
        VectorChunk text = paragraph("p1", "说明文字", List.of("A"));
        VectorChunk image = VectorChunk.builder().chunkId("img").index(1).content("图片描述")
                .embeddingText("图片描述").assets(List.of(asset)).blockType("IMAGE")
                .outlinePath(List.of("A")).sourceBlockIds(List.of("img1")).build();

        List<VectorChunk> result = aggregator.aggregate(List.of(text, image), config);

        assertEquals(1, result.size());
        assertEquals(List.of(asset), result.get(0).getAssets());
        assertEquals(List.of("p1", "img1"), result.get(0).getSourceBlockIds());
        assertEquals("PARAGRAPH", result.get(0).getBlockType());
    }

    @Test
    void mergesUndescribedImageIntoAdjacentParagraph() {
        // 解析器没产出图注时图片也并入相邻段落：否则它单独成块，既召不回又白占一个 topK 名额
        AssetRef asset = new AssetRef("https://example.test/b.png", "image/png", "img2");
        VectorChunk text = paragraph("p1", "正文说明", List.of("A"));
        VectorChunk image = VectorChunk.builder().chunkId("img").index(1).content("")
                .assets(List.of(asset)).blockType("IMAGE")
                .outlinePath(List.of("A")).sourceBlockIds(List.of("img2")).build();

        List<VectorChunk> result = aggregator.aggregate(List.of(text, image), config);

        assertEquals(1, result.size());
        assertEquals(List.of(asset), result.get(0).getAssets());
        assertEquals("PARAGRAPH", result.get(0).getBlockType());
    }

    @Test
    void shortTailIsNotMergedAcrossOutlineBoundary() {
        // A 节的短块不得被并进 B 节的块：块只带一条 outlinePath，跨节合并会让归属与引用都错
        VectorChunk sectionA = paragraph("p1", "abcdefgh", List.of("A"));
        VectorChunk sectionB = paragraph("p2", "ijklmnop", List.of("B"));

        List<VectorChunk> result = aggregator.aggregate(List.of(sectionA, sectionB), config);

        assertEquals(2, result.size());
        assertEquals(List.of("A"), result.get(0).getOutlinePath());
        assertEquals(List.of("B"), result.get(1).getOutlinePath());
    }

    @Test
    void atomicBlockIsNeverMergedIntoNeighbouringText() {
        // 代码块体量虽小，但它是原子内容：既不并入前块，也不吞掉后面的短段落
        VectorChunk text = paragraph("p1", "简短说明", List.of("B"));
        VectorChunk code = VectorChunk.builder().chunkId("code").index(1).content("x=1")
                .blockType("CODE").outlinePath(List.of("B")).sourceBlockIds(List.of("c1")).build();
        VectorChunk tail = paragraph("p2", "后续说明", List.of("B"));

        List<VectorChunk> result = aggregator.aggregate(List.of(text, code, tail), config);

        assertEquals(3, result.size());
        assertEquals("CODE", result.get(1).getBlockType());
    }

    private VectorChunk paragraph(String id, String text, List<String> outline) {
        return VectorChunk.builder().chunkId(id).index(0).content(text).blockType("PARAGRAPH")
                .outlinePath(outline).sourceBlockIds(List.of(id)).build();
    }
}
