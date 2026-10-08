package com.lexia.api.modules.expedientes.ocr;

import org.springframework.stereotype.Service;

@Service
public class OcrContentReducerService {

    public String reduce(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}