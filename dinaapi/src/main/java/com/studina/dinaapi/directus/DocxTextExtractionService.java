package com.studina.dinaapi.directus;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

@Service
public class DocxTextExtractionService {

    public String extract(byte[] docxBytes) throws IOException {
        try (XWPFDocument document =
                     new XWPFDocument(new ByteArrayInputStream(docxBytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}
