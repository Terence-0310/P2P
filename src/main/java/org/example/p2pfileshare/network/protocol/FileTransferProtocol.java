package org.example.p2pfileshare.network.protocol;

/**
 * Giao thức kênh truyền file (TCP, DataInput/OutputStream.writeUTF).
 *
 * Request:
 *   FILE_META_REQUEST|peerId|fileName
 *   GET_CHUNK|peerId|fileName|chunkIndex
 *
 * peerId là ID của peer đang tải, server dùng để kiểm tra peer đã được chấp nhận kết nối chưa.
 * fileName đặt giữa peerId và chunkIndex nên vẫn parse đúng khi tên file chứa ký tự '|'.
 */
public class FileTransferProtocol {

    // Commands
    public static final String FILE_META_REQUEST = "FILE_META_REQUEST";
    public static final String FILE_META_RESPONSE = "FILE_META_RESPONSE";
    public static final String GET_CHUNK = "GET_CHUNK";
    public static final String CHUNK_DATA = "CHUNK_DATA";
    public static final String CHUNK_ERROR = "CHUNK_ERROR";

    // Build request for file metadata
    public static String buildMetaRequest(String peerId, String fileName) {
        return FILE_META_REQUEST + "|" + peerId + "|" + fileName;
    }

    // Build request for specific chunk
    public static String buildChunkRequest(String peerId, String fileName, int chunkIndex) {
        return GET_CHUNK + "|" + peerId + "|" + fileName + "|" + chunkIndex;
    }

    // Parse command
    public static ParsedCommand parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split("\\|", 3);
        if (parts.length < 3) return null;

        String command = parts[0];
        String peerId = parts[1];
        String rest = parts[2];

        if (GET_CHUNK.equals(command)) {
            int sep = rest.lastIndexOf('|');
            if (sep < 0) return null;
            return new ParsedCommand(command, peerId, rest.substring(0, sep), rest.substring(sep + 1));
        }
        return new ParsedCommand(command, peerId, rest, null);
    }

    public static class ParsedCommand {
        public final String command;
        public final String peerId;
        public final String fileName;
        public final String chunkIndex; // chỉ có với GET_CHUNK

        public ParsedCommand(String command, String peerId, String fileName, String chunkIndex) {
            this.command = command;
            this.peerId = peerId;
            this.fileName = fileName;
            this.chunkIndex = chunkIndex;
        }
    }
}
