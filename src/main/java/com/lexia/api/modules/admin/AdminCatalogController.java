package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogs")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminCatalogController {

  private final CatalogAdminService catalogAdminService;

  public AdminCatalogController(CatalogAdminService catalogAdminService) {
    this.catalogAdminService = catalogAdminService;
  }

  @GetMapping
  public List<AdminDtos.CatalogSummary> list() {
    return catalogAdminService.listCatalogs();
  }

  @GetMapping("/{catalogId}")
  public AdminDtos.CatalogDetail detail(@PathVariable UUID catalogId) {
    return catalogAdminService.getCatalog(catalogId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.CatalogDetail create(@Valid @RequestBody AdminDtos.CreateCatalogRequest request) {
    return catalogAdminService.createCatalog(request);
  }

  @PostMapping("/{catalogId}/items")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.CatalogItemDetail createItem(
      @PathVariable UUID catalogId, @Valid @RequestBody AdminDtos.CreateCatalogItemRequest request) {
    return catalogAdminService.createItem(catalogId, request);
  }

  @PutMapping("/{catalogId}/items/{itemId}")
  public AdminDtos.CatalogItemDetail updateItem(
      @PathVariable UUID catalogId,
      @PathVariable UUID itemId,
      @Valid @RequestBody AdminDtos.UpdateCatalogItemRequest request) {
    return catalogAdminService.updateItem(catalogId, itemId, request);
  }

  @PostMapping("/{catalogId}/items/{itemId}/deactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivateItem(@PathVariable UUID catalogId, @PathVariable UUID itemId) {
    catalogAdminService.deleteItem(catalogId, itemId);
  }
}
