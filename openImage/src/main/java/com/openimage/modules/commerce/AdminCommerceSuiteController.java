package com.openimage.modules.commerce;

import com.openimage.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/commerce-suite")
@PreAuthorize("hasAnyRole('admin','super_admin')")
public class AdminCommerceSuiteController {
    private final CommerceSuiteCatalogService catalog;

    public AdminCommerceSuiteController(CommerceSuiteCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    public ApiResponse<CommerceSuiteDtos.Catalog> catalog() {
        return ApiResponse.ok(catalog.catalog());
    }

    @PostMapping("/catalog")
    public ApiResponse<CommerceSuiteDtos.Catalog> saveCatalog(@RequestBody CommerceSuiteDtos.Catalog request) {
        return ApiResponse.ok(catalog.saveCatalog(request));
    }

    @GetMapping("/platforms")
    public ApiResponse<List<CommerceSuiteDtos.PlatformPreset>> platforms() {
        return ApiResponse.ok(catalog.platforms());
    }

    @PutMapping("/platforms/{code}")
    public ApiResponse<CommerceSuiteDtos.PlatformPreset> savePlatform(
        @PathVariable String code,
        @RequestBody CommerceSuiteDtos.PlatformPreset request
    ) {
        return ApiResponse.ok(catalog.savePlatform(code, request));
    }

    @GetMapping("/templates")
    public ApiResponse<List<CommerceSuiteDtos.TemplatePreset>> templates(@RequestParam(required = false) String platform) {
        return ApiResponse.ok(catalog.templates(platform));
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<CommerceSuiteDtos.TemplatePreset> saveTemplate(
        @PathVariable String id,
        @RequestBody CommerceSuiteDtos.TemplatePreset request
    ) {
        return ApiResponse.ok(catalog.saveTemplate(id, request));
    }
}
