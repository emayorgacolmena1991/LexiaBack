package com.lexia.api.common.storage.migracion;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum StorageMigracionTabla {
  COACTIVA_ARCHIVO(
      "coactiva_archivo",
      "SELECT id::text, storage_path, sha256, mime_type FROM app.coactiva_archivo WHERE storage_path IS NOT NULL AND btrim(storage_path) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE app.coactiva_archivo SET storage_path = ? WHERE id = ?::uuid",
      false),
  COACTIVA_DOCUMENTO_DRAFT(
      "coactiva_documento_draft",
      "SELECT id::text, storage_path, NULL, NULL FROM app.coactiva_documento_draft WHERE storage_path IS NOT NULL AND btrim(storage_path) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE app.coactiva_documento_draft SET storage_path = ? WHERE id = ?::uuid",
      false),
  DOCUMENT_VERSION(
      "document_version",
      "SELECT id::text, storage_key, content_hash, mime_type FROM app.document_version WHERE storage_key IS NOT NULL AND btrim(storage_key) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE app.document_version SET storage_key = ? WHERE id = ?::uuid",
      false),
  MINUTA_DRAFT(
      "minuta_draft",
      "SELECT id::text, storage_path, NULL, NULL FROM app.minuta_draft WHERE storage_path IS NOT NULL AND btrim(storage_path) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE app.minuta_draft SET storage_path = ? WHERE id = ?::uuid",
      false),
  NOTARY_ESCRITURA(
      "notary_escritura",
      "SELECT id::text, storage_ref, NULL, NULL FROM control.notary_escritura WHERE storage_ref IS NOT NULL AND btrim(storage_ref) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE control.notary_escritura SET storage_ref = ? WHERE id = ?::uuid",
      false),
  NOTARY_REMISION_VERSION_MINUTA(
      "notary_remision_version_minuta",
      "SELECT id::text, minuta_storage_ref, NULL, NULL FROM control.notary_remision_version WHERE minuta_storage_ref IS NOT NULL AND btrim(minuta_storage_ref) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE control.notary_remision_version SET minuta_storage_ref = ? WHERE id = ?::uuid",
      false),
  NOTARY_REMISION_VERSION_CONTRATO(
      "notary_remision_version_contrato",
      "SELECT id::text, contrato_storage_ref, NULL, NULL FROM control.notary_remision_version WHERE contrato_storage_ref IS NOT NULL AND btrim(contrato_storage_ref) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE control.notary_remision_version SET contrato_storage_ref = ? WHERE id = ?::uuid",
      false),
  NOTARY_REMISION_DOCUMENT(
      "notary_remision_document",
      "SELECT id::text, storage_key, NULL, NULL FROM control.notary_remision_document WHERE storage_key IS NOT NULL AND btrim(storage_key) <> '' AND id > ?::uuid ORDER BY id LIMIT ?",
      "UPDATE control.notary_remision_document SET storage_key = ? WHERE id = ?::uuid",
      false),
  NOTARY_TENANT_PROFILE(
      "notary_tenant_profile",
      "SELECT tenant_id::text, logo_storage_ref, NULL, logo_content_type FROM control.notary_tenant_profile WHERE logo_storage_ref IS NOT NULL AND btrim(logo_storage_ref) <> '' AND tenant_id > ?::uuid ORDER BY tenant_id LIMIT ?",
      "UPDATE control.notary_tenant_profile SET logo_storage_ref = ? WHERE tenant_id = ?::uuid",
      false),
  PRODUCT_TEMPLATE(
      "product_template",
      """
          SELECT id::text, storage_key, NULL, NULL FROM app.product_template
          WHERE storage_key IS NOT NULL AND btrim(storage_key) <> ''
            AND storage_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/'
            AND id > ?::uuid ORDER BY id LIMIT ?
          """,
      "UPDATE app.product_template SET storage_key = ? WHERE id = ?::uuid",
      false);

  private final String nombre;
  private final String select;
  private final String update;

  StorageMigracionTabla(String nombre, String select, String update, boolean ignored) {
    this.nombre = nombre;
    this.select = select;
    this.update = update;
  }

  public String nombre() {
    return nombre;
  }

  public String select() {
    return select;
  }

  public String update() {
    return update;
  }

  public static Optional<StorageMigracionTabla> porNombre(String nombre) {
    if (nombre == null || nombre.isBlank()) {
      return Optional.empty();
    }
    String buscado = nombre.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(t -> t.nombre.equals(buscado)).findFirst();
  }
}