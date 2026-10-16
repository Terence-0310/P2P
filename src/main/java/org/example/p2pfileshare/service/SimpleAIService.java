package org.example.p2pfileshare.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

public class SimpleAIService {

    public String predictSubject(String fileName) {
        String normalized = removeAccents(fileName).toLowerCase();

        // 1. Lập trình mạng & Hệ thống
        if (contain(normalized, "lap trinh mang", "ltm", "network", "socket", "tcp", "udp",
                "multicast", "broadcast", "rmi", "giao thuc", "cs2004", "soa", "mo hinh", "p2p")) return "Lập trình mạng";
        if (contain(normalized, "lap trinh he thong", "laptrinhhethong","hethong", "system programming", "ltht")) return "Lập trình hệ thống";
        if (contain(normalized, "linux", "ubuntu", "centos", "open source", "nguon mo", "mini-devops")) return "Linux & PM Nguồn mở";
        if (contain(normalized, "kien truc may tinh", "computer architecture", "ktmt")) return "Kiến trúc máy tính";
        if (contain(normalized, "nguyen ly he dieu hanh",  "operating system", "hdh") || containWord(normalized, "os")) return "Nguyên lý Hệ điều hành";
        if (contain(normalized, "html", "css", "javascript", "php", "jsp", "servlet")) return "Thiết kế Web";

        // 2. Web & Mobile
        if (contain(normalized, "web", "php", "jsp", "servlet")) return "Công nghệ và lập trình Web";
        if (contain(normalized, "di dong", "mobile", "android", "ios", "flutter", "react native")) return "Lập trình di động";
        if (contain(normalized, "may chu", "server", "cloud", "aws", "azure", "google cloud")) return "Máy chủ & Điện toán đám mây";
        if (contain(normalized, "an ninh mang", "network security", "cyber security", "bao mat thong tin")) return "An ninh mạng";

        // 3. Công nghệ phần mềm & Phân tích
        if (contain(normalized, "cong nghe phan mem", "cnpm", "se", "software eng", "extreme",
                "software architecture", "sw processes", "se2022")) return "Công nghệ phần mềm";
        if (contain(normalized, "phan tich", "thiet ke he thong", "uml", "design pattern") || normalized.endsWith(".mdj")) return "Phân tích & TK hệ thống";
        if (contain(normalized, "phan tich du lieu", "data analysis", "analyst", "dataset", "data", "visualization")) return "Phân tích dữ liệu";

        // toán học
        if (contain(normalized, "giai tich", "giaitich", "calculus")) return "Giải tích";
        if (contain(normalized, "dai so", "tuyen tinh", "algebra")) return "Đại số tuyến tính";
        if (contain(normalized, "vat ly", "physical")) return "Vật lý";
        // 4. Dữ liệu & Giải thuật
        if (contain(normalized, "cau truc du lieu", "giai thuat", "dsa", "algorithm")) return "CTDL & Giải thuật";
        if (contain(normalized, "co so du lieu","cosodukieu","du-lieu", "csdl", "db", "sql", "mysql", "database") || normalized.endsWith(".sql")) return "Cơ sở dữ liệu";
        if (contain(normalized, "do hoa may tinh", "computer graphics", "opengl", "graphics", "curver", "surface")|| containWord(normalized, "os", "cg", "2d", "3d")) return "Đồ họa máy tính";
        if (contain(normalized, "thamlam", "dophuctap", "chiadetri", "quyhoachdong", "quaylui", "dequy", "chungminhdungdan")) return "Phân tích & TK giải thuật";
        if (contain(normalized, "tri tue nhan tao", "minimax", "constraint", "genetic", "solving", "evolutionary", "ai4life")|| containWord(normalized, "ai")) return "Trí tuệ nhân tạo";

        // 5. Ngôn ngữ lập trình căn bản
        if (contain(normalized, "java", "oop", "huong doi tuong")) return "Lập trình Java / OOP";
        if (contain(normalized, "lap trinh co ban", "nhap mon lap trinh", "cpp", "c++", "vong lap")) return "Lập trình cơ bản";

        if (contain(normalized, "do an co so","doancoso", "dacs", "do an")) {
            if ( contain(normalized, "doancoso1","co_so_1","co-so-1")||containWord(normalized, "1")) return "Đồ án cơ sở 1";
            if (contain(normalized, "doancoso2","co_so_2","co-so-2")||containWord(normalized, "2")) return "Đồ án cơ sở 2";
            if (contain(normalized, "doancoso3","co_so_3","co-so-3")||containWord(normalized, "3")) return "Đồ án cơ sở 3";
            if (contain(normalized, "doancoso4","co_so_4","co-so-4")||containWord(normalized, "4")) return "Đồ án cơ sở 4";
            return "Đồ án cơ sở";
        }
        if (contain(normalized, "thuc tap", "internship")) return "Thực tập thực tế";
        if (contain(normalized, "khoi nghiep")) return "Khởi nghiệp & ĐMST";



        if (contain(normalized, "tieng anh", "english", "toeic", "extract", "tacn")) return "Tiếng Anh";
        if (contain(normalized, "tieng nhat", "japanese", "nihongo", "sakubun", "furikaeri", "portfolio",
                "choukai", "cvbank", "phieu phan tu", "genkou", "essay","katakana", "kanji", "jlpt", "n5", "n4", "n3", "n2", "n1")) return "Tiếng Nhật";

        return "Khác";
    }

    public String predictTag(String fileName) {
        String lower = removeAccents(fileName).toLowerCase();

        // 1. Nhóm Báo cáo / Đồ án
        if (contain(lower, "bao cao", "report", "do an", "tieuluan", "final", "examcise", "ex")) return "Báo cáo/Đồ án";

        // 2. Nhóm Bài tập / Lab
        if (contain(lower, "lab", "bai tap", "exercise", "homework", "bt")) return "Bài tập/Lab";

        // 3. Nhóm Học liệu
        if (contain(lower, "slide", "bai giang", "ly thuyet", "chapter", "chuong")) return "Slide/Bài giảng";
        if (contain(lower, "giao trinh", "sach", "book", "ebook")) return "Giáo trình";
        if (contain(lower, "de thi", "exam", "midterm", "quiz", "trac nghiem")) return "Đề thi";
        if (contain(lower, "xac suat thong ke", "giai tich", "dai so tuyen tinh", "toan roi rac")) return "Toán";

        // 4. Nhóm Source Code
        if (lower.endsWith(".java") || lower.endsWith(".cpp") || lower.endsWith(".py") || lower.endsWith(".sql") || lower.endsWith(".zip") || lower.endsWith(".rar")) {
            return "Source Code";
        }

        return "Tài liệu";
    }

    // Hàm kiểm tra chuỗi có chứa bất kỳ từ khóa nào không
    public boolean contain(String input, String... keywords) {
        for (String key : keywords) {
            if (input.contains(key)) {
                return true;
            }
        }
        return false;
    }


    // Hàm loại bỏ dấu tiếng Việt
    private String removeAccents(String s) {
        if (s == null) return "";
        String temp = Normalizer.normalize(s, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(temp).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
    }
    private boolean containWord(String input, String... words) {
        String[] tokens = input.split("[^a-z0-9]+");
        for (String w : words) {
            for (String t : tokens) {
                if (t.equals(w)) return true;
            }
        }
        return false;
    }
}
