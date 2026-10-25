package org.example.p2pfileshare.network.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferProtocolTest {

    @Test
    void parsesMetaRequest() {
        var cmd = FileTransferProtocol.parse(FileTransferProtocol.buildMetaRequest("peer-1", "docs/a.pdf"));
        assertEquals(FileTransferProtocol.FILE_META_REQUEST, cmd.command);
        assertEquals("peer-1", cmd.peerId);
        assertEquals("docs/a.pdf", cmd.fileName);
        assertNull(cmd.chunkIndex);
    }

    @Test
    void parsesChunkRequestWithPipeInFileName() {
        var cmd = FileTransferProtocol.parse(FileTransferProtocol.buildChunkRequest("peer-1", "a|b.txt", 7));
        assertEquals(FileTransferProtocol.GET_CHUNK, cmd.command);
        assertEquals("peer-1", cmd.peerId);
        assertEquals("a|b.txt", cmd.fileName);
        assertEquals("7", cmd.chunkIndex);
    }

    @Test
    void rejectsMalformedRequest() {
        assertNull(FileTransferProtocol.parse("GET_CHUNK|onlypeer"));
        assertNull(FileTransferProtocol.parse(""));
    }
}
