package org.example.p2pfileshare.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class DocumentSummaryService {

    // loại bỏ từ vô nghĩa
    public static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
        "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
        "below", "between", "both", "but", "by", "can't", "cannot", "could", "couldn't",
        "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during",
        "each", "few", "for", "from", "further",  "had", "hadn't", "has", "hasn't",
        "have", "haven't",  "having",  "he",  "he'd",  "he'll",  "he's",  "her",
        "here",  "here's",  "hers",  "herself",  "him",  "himself",  "his",
        "how",  "how's",  "i",  "i'd",  "i'll",  "i'm",  "i've",  "if",
        "in",  "into",  "is",  "isn't",  "it",  "it's",  "its",
        "itself",  "just",  "ll",  "ma",  "me",  "mightn't",
        "more",  "most" ,  "mustn't" ,  "my" ,  "myself" ,
        "là", "của", "và", "các", "những", "cái", "trong", "khi", "cho", "đến",
        "tại", "với", "như", "này", "đó", "theo", "để", "từ", "một", "có",
        "được", "sẽ", "đang", "nhiều", "ít", "về", "nên", "phải", "cũng"
    ));

    public String summarize(File file) throws IOException {
        String fullText = "";

        // trich xuat van ban tho
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            fullText = readPdf(file);
        } else if (fileName.endsWith(".docx")) {
            fullText = readDocx(file);
        } else if (fileName.endsWith(".txt")) {
            fullText = readTxt(file);
        } else {
            return "Chỉ hỗ trợ tóm tắt file PDF, DOCX và TXT.";
        }
        if (fullText.trim().isEmpty()) return "Không trích xuất được nội dung văn bản.";

        return extractKeySentences(fullText, 3);
    }

    // doc file pdf
    private String readPdf(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();

            stripper.setEndPage(10); // chi doc toi da 10 trang
            return stripper.getText(document);
        }
    }

    private String readDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String readTxt(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    // Tóm tắt kiểu trích xuất: chấm điểm câu theo tần suất từ khóa, lấy numSentences câu điểm cao nhất
    // và giữ nguyên thứ tự xuất hiện trong văn bản gốc.
    String extractKeySentences(String text, int numSentences) {
        // tach van ban thanh cau (theo dấu câu hoặc dòng trống)
        List<String> sentences = new ArrayList<>();
        for (String raw : text.split("(?<=[.!?])\\s+|\\n\\s*\\n")) {
            String sentence = raw.replaceAll("\\s+", " ").trim();
            if (!sentence.isEmpty()) sentences.add(sentence);
        }
        if (sentences.size() <= numSentences) return String.join("\n\n", sentences);

        Map<String, Integer> wordFrequencies = new HashMap<>();
        for (String sentence : sentences) {
            for (String w : words(sentence)) {
                if (!STOP_WORDS.contains(w)) {
                    wordFrequencies.merge(w, 1, Integer::sum);
                }
            }
        }

        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            List<String> ws = words(sentences.get(i));
            // câu quá ngắn (tiêu đề, số trang...) không nên được chọn
            if (ws.size() < 4) {
                scores[i] = -1;
                continue;
            }
            double score = 0.0;
            for (String w : ws) {
                score += wordFrequencies.getOrDefault(w, 0);
            }
            scores[i] = score / ws.size();
        }

        return java.util.stream.IntStream.range(0, sentences.size()).boxed()
                .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                .limit(numSentences)
                .sorted()
                .map(sentences::get)
                .collect(Collectors.joining(" (...) \n\n"));
    }

    // tách câu thành các từ đã chuẩn hóa (chữ thường, bỏ dấu câu, giữ chữ có dấu tiếng Việt)
    private static List<String> words(String sentence) {
        List<String> result = new ArrayList<>();
        for (String token : sentence.toLowerCase().split("\\s+")) {
            String w = token.replaceAll("[^\\p{L}\\p{N}]", "");
            if (!w.isEmpty()) result.add(w);
        }
        return result;
    }
}
