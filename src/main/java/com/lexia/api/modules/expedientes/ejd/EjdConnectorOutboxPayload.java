package com.lexia.api.modules.expedientes.ejd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EjdConnectorOutboxPayload(
    String caseId,
    String caseCode,
    String connector,
    String stageCode,
    String trigger,
    String integrationCallId) {}
