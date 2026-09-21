package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockChunkConfig;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StructuredChunkingServiceTest {

    @Mock
    private BlockAwareChunkerDispatcher dispatcher;
    @Mock
    private ChunkingStrategyFactory factory;

    @Test
    void passesStructureAwareBoundsSeparately() {
        StructuredChunkingService service = new StructuredChunkingService(dispatcher, factory);
        List<ParagraphBlock> ignored = List.of();
        var blocks = List.of(new ParagraphBlock("p", Provenance.ofFile("a.md"), List.of(), "text"));
        TextBoundaryOptions options = new TextBoundaryOptions(1400, 0, 1600, 600);
        service.chunk(blocks, "text", ChunkingMode.STRUCTURE_AWARE, options, null);

        ArgumentCaptor<BlockChunkConfig> captor = ArgumentCaptor.forClass(BlockChunkConfig.class);
        verify(dispatcher).dispatch(any(), captor.capture());
        BlockChunkConfig config = captor.getValue();
        assertEquals(600, config.minChars());
        assertEquals(1400, config.targetChars());
        assertEquals(1600, config.maxChars());
    }

    @Test
    void mapsFixedSizeToAllThreeBounds() {
        StructuredChunkingService service = new StructuredChunkingService(dispatcher, factory);
        var blocks = List.of(new ParagraphBlock("p", Provenance.ofFile("a.md"), List.of(), "text"));
        service.chunk(blocks, "text", ChunkingMode.FIXED_SIZE, new FixedSizeOptions(256, 32), null);

        ArgumentCaptor<BlockChunkConfig> captor = ArgumentCaptor.forClass(BlockChunkConfig.class);
        verify(dispatcher).dispatch(any(), captor.capture());
        BlockChunkConfig config = captor.getValue();
        assertEquals(256, config.minChars());
        assertEquals(256, config.targetChars());
        assertEquals(256, config.maxChars());
        assertEquals(32, config.overlapChars());
    }

    @Test
    void wholeDocumentSkipsBlockAwareDispatcher() {
        StructuredChunkingService service = new StructuredChunkingService(dispatcher, factory);
        var blocks = List.of(new ParagraphBlock("p", Provenance.ofFile("a.md"), List.of(), "text"));
        List<VectorChunk> result = service.chunk(blocks, "text", ChunkingMode.STRUCTURE_AWARE,
                new TextBoundaryOptions(-1, 0, 1600, 600), null);

        assertEquals(1, result.size());
        assertEquals("DOCUMENT", result.get(0).getBlockType());
        verify(dispatcher, never()).dispatch(any(), any());
    }
}
