package com.lexia.api.modules.notifications;

public record EmailAttachment(String fileName, String mimeType, byte[] content) {}