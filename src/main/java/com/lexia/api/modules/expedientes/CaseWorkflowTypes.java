package com.lexia.api.modules.expedientes;

public final class CaseWorkflowTypes {

  private CaseWorkflowTypes() {}

  public static boolean isOrchestrated(String caseType) {
    return "EJD".equals(caseType) || "ECD".equals(caseType);
  }
}
