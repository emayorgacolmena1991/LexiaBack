package com.lexia.api.modules.expedientes;



import java.util.List;

import java.util.Locale;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;



@Service

@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")

public class EjdCaseExceptionSyncService {



  private static final String TYPE_VALIDATION = "VALIDATION_FAIL";



  private final CaseExceptionRepository exceptions;

  private final CaseValidationRepository validations;



  public EjdCaseExceptionSyncService(

      CaseExceptionRepository exceptions, CaseValidationRepository validations) {

    this.exceptions = exceptions;

    this.validations = validations;

  }



  @Transactional

  public void syncForCase(LegalCase legalCase) {

    if (legalCase == null || !CaseWorkflowTypes.isOrchestrated(legalCase.getCaseType())) {

      return;

    }

    UUID tenantId = legalCase.getTenantId();

    UUID caseId = legalCase.getId();

    UUID owner = legalCase.getResponsibleMembershipId();



    List<CaseValidation> rows =

        validations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(caseId, tenantId);

    for (CaseValidation validation : rows) {

      String title = validation.getLabel() != null ? validation.getLabel().trim() : "Validación";

      if ("FAIL".equals(validation.getResult())) {

        openOrRefreshValidationException(

            tenantId, caseId, owner, title, validation.getEvidence(), severityFor(title));

      } else if ("PASS".equals(validation.getResult())) {

        exceptions

            .findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(

                caseId, tenantId, TYPE_VALIDATION, title, "OPEN")

            .ifPresent(

                row -> {

                  row.resolve("Cierre automático: la validación volvió a «Cumple».");

                  exceptions.save(row);

                });

      }

    }

  }



  private void openOrRefreshValidationException(

      UUID tenantId,

      UUID caseId,

      UUID owner,

      String title,

      String evidence,

      String severity) {

    var existing =

        exceptions.findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(

            caseId, tenantId, TYPE_VALIDATION, title, "OPEN");

    if (existing.isPresent()) {

      CaseException row = existing.get();

      row.refreshEvidence(evidence != null ? evidence : row.getEvidence());

      exceptions.save(row);

      return;

    }

    exceptions.save(

        CaseException.openFromValidation(

            tenantId,

            caseId,

            owner,

            title,

            evidence != null ? evidence : "—",

            severity));

  }



  private static String severityFor(String validationLabel) {

    String label = validationLabel.toLowerCase(Locale.ROOT);

    if (label.contains("jurídica") || label.contains("juridica") || label.contains("v6")) {

      return "ALTA";

    }

    if (label.contains("v5") || label.contains("cruzada")) {

      return "MEDIA";

    }

    return "MEDIA";

  }

}

