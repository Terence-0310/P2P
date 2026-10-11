package org.example.p2pfileshare.model;

public class SharedFileLocal {

    private final String fileName;        // Tên file
    private final String relativePath;
    private final String extension;// Đường dẫn tương đối trong thư mục chia sẻ
    private final long size;              // Dung lượng file
    private String subject;         // Chủ đề/môn học (optional)
    private String tag;            // Tags (optional)

    private boolean visible;              // Có chia sẻ hay không

    public SharedFileLocal(String fileName,
                           String relativePath,
                           String extension,
                           long size,
                           String subject,
                           String tag,
                           boolean visible) {

        this.fileName = fileName;
        this.relativePath = relativePath;
        this.size = size;
        this.extension = extension;
        this.subject = subject;
        this.tag = tag;

        this.visible = visible;
    }

    // ===== GETTERS =====
    public String getFileName()        { return fileName; }
    public String getRelativePath()    { return relativePath; }
    public long getSize()              { return size; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public boolean isVisible()         { return visible; }
    public String getExtension()       { return extension; }
    // ===== SETTERS =====
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
