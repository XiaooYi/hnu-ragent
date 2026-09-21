package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockChunkConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkingOptionsValidationTest {

    @Test
    void acceptsValidStructureAwareBounds() {
        assertDoesNotThrow(() -> new TextBoundaryOptions(1400, 0, 1600, 600));
    }

    @Test
    void rejectsInvalidStructureAwareBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TextBoundaryOptions(500, 0, 1600, 600));
        assertThrows(IllegalArgumentException.class, () -> new TextBoundaryOptions(1400, 0, 1200, 600));
        assertThrows(IllegalArgumentException.class, () -> new TextBoundaryOptions(1400, 1600, 1600, 600));
    }

    @Test
    void keepsWholeDocumentSentinel() {
        assertDoesNotThrow(() -> new TextBoundaryOptions(-1, 0, 1600, 600));
        assertDoesNotThrow(() -> new FixedSizeOptions(-1, 0));
    }

    @Test
    void validatesBlockAwareBounds() {
        assertDoesNotThrow(() -> new BlockChunkConfig(600, 1400, 1600, 0, 50, 15, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockChunkConfig(1601, 1400, 1600, 0, 50, 15, 10));
    }
}
