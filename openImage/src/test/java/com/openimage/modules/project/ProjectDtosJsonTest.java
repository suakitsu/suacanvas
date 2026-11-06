package com.openimage.modules.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.openimage.modules.project.dto.ProjectDtos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDtosJsonTest {
    private final ObjectMapper json = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void acceptsCamelCaseAplusFieldsFromExistingClients() throws Exception {
        ProjectDtos.CreateProjectRequest request = json.readValue("""
            {
              "productName": "Test product",
              "visualPrompt": "Clean studio scene",
              "includeAplus": true,
              "aplusModule": "key-feature-module",
              "aplusSize": "970x600",
              "aplusPrompt": "Show three key benefits"
            }
            """, ProjectDtos.CreateProjectRequest.class);

        assertThat(request.productName()).isEqualTo("Test product");
        assertThat(request.visualPrompt()).isEqualTo("Clean studio scene");
        assertThat(request.includeAplus()).isTrue();
        assertThat(request.aplusModule()).isEqualTo("key-feature-module");
        assertThat(request.aplusSize()).isEqualTo("970x600");
        assertThat(request.aplusPrompt()).isEqualTo("Show three key benefits");
    }

    @Test
    void acceptsCanonicalSnakeCaseAplusFields() throws Exception {
        ProjectDtos.CreateProjectRequest request = json.readValue("""
            {
              "product_name": "Test product",
              "visual_prompt": "Clean studio scene",
              "include_aplus": true,
              "aplus_module": "key-feature-module",
              "aplus_size": "970x600",
              "aplus_prompt": "Show three key benefits"
            }
            """, ProjectDtos.CreateProjectRequest.class);

        assertThat(request.includeAplus()).isTrue();
        assertThat(request.aplusModule()).isEqualTo("key-feature-module");
        assertThat(request.aplusSize()).isEqualTo("970x600");
        assertThat(request.aplusPrompt()).isEqualTo("Show three key benefits");
    }

    @Test
    void listingPackageUsesTheSameAplusContract() throws Exception {
        ProjectDtos.ListingPackageRequest request = json.readValue("""
            {
              "includeAplus": true,
              "aplusModule": "key-feature-module",
              "aplusSize": "970x600",
              "aplusPrompt": "Show three key benefits",
              "language": "zh-CN"
            }
            """, ProjectDtos.ListingPackageRequest.class);

        assertThat(request.includeAplus()).isTrue();
        assertThat(request.aplusModule()).isEqualTo("key-feature-module");
        assertThat(request.aplusSize()).isEqualTo("970x600");
        assertThat(request.aplusPrompt()).isEqualTo("Show three key benefits");
        assertThat(request.language()).isEqualTo("zh-CN");
    }
}
