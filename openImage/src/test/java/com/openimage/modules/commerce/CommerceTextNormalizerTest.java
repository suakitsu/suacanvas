package com.openimage.modules.commerce;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommerceTextNormalizerTest {
    @Test
    void removesPresentationMarkersButPreservesMeaningfulNumbers() {
        assertEquals(
            List.of("新鲜采摘", "果肉饱满", "清甜多汁", "1.5L 大容量", "2K 清晰度", "第1代升级款"),
            CommerceTextNormalizer.sellingPoints(List.of(
                "1. 新鲜采摘",
                "2、果肉饱满",
                "(3) 清甜多汁",
                "1.5L 大容量",
                "2K 清晰度",
                "第1代升级款"
            ))
        );
    }
}
