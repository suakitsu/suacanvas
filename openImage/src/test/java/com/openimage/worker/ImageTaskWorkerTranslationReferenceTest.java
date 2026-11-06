package com.openimage.worker;

import com.openimage.domain.entity.ProductImageEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageTaskWorkerTranslationReferenceTest {
    @Test
    void selectsOnlyTheSourceBoundToTranslationTask() {
        ProductImageEntity first = image("source-1");
        ProductImageEntity second = image("source-2");

        List<ProductImageEntity> selected = ImageTaskWorker.translationReferences(List.of(first, second), "source-2");

        assertThat(selected).containsExactly(second);
    }

    @Test
    void keepsLegacyReferenceBehaviorWithoutTranslationBinding() {
        ProductImageEntity first = image("source-1");
        ProductImageEntity second = image("source-2");

        assertThat(ImageTaskWorker.translationReferences(List.of(first, second), "")).containsExactly(first, second);
    }

    @Test
    void rejectsMissingBoundSourceInsteadOfGeneratingFromTheWrongImage() {
        assertThatThrownBy(() -> ImageTaskWorker.translationReferences(List.of(image("source-1")), "missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("翻译源图不存在");
    }

    private ProductImageEntity image(String id) {
        ProductImageEntity image = new ProductImageEntity();
        image.setId(id);
        return image;
    }
}
