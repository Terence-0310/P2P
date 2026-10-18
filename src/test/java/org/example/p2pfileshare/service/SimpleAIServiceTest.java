package org.example.p2pfileshare.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleAIServiceTest {

    private final SimpleAIService ai = new SimpleAIService();

    @Test
    void predictsSubjectFromFileName() {
        assertEquals("Lập trình mạng", ai.predictSubject("Lap trinh mang - Socket TCP.pdf"));
    }

    @Test
    void predictsTagFromFileName() {
        assertEquals("Bài tập/Lab", ai.predictTag("Lab11.docx"));
        assertEquals("Slide/Bài giảng", ai.predictTag("Chapter3.pptx"));
        assertEquals("Tài liệu", ai.predictTag("ghi-chu.txt"));
    }
}
