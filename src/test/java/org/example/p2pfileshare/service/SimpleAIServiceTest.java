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

    @Test
    void shortKeywordsDoNotMatchInsideOtherWords() {
        // "database" từng bị xếp vào Công nghệ phần mềm vì chứa "se", "feedback" chứa "db"
        assertEquals("Cơ sở dữ liệu", ai.predictSubject("database-design.sql"));
        assertEquals("Khác", ai.predictSubject("feedback.docx"));
        assertEquals("Công nghệ phần mềm", ai.predictSubject("SE2022 - Chapter 1.pdf"));
        // "text"/"index" chứa "ex", "label" chứa "lab", "example" chứa "exam"
        assertEquals("Tài liệu", ai.predictTag("text-notes.txt"));
        assertEquals("Tài liệu", ai.predictTag("index.html"));
        assertEquals("Tài liệu", ai.predictTag("label.png"));
        assertEquals("Tài liệu", ai.predictTag("example.pdf"));
        assertEquals("Bài tập/Lab", ai.predictTag("BT2 - mang.docx"));
    }

    @Test
    void dataAnalysisIsCheckedBeforeSystemAnalysis() {
        assertEquals("Phân tích dữ liệu", ai.predictSubject("Phân tích dữ liệu - Bài 1.pdf"));
        assertEquals("Phân tích & TK hệ thống", ai.predictSubject("Phân tích thiết kế hệ thống.pdf"));
    }
}
