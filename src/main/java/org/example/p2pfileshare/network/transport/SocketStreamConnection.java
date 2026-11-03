package org.example.p2pfileshare.network.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** StreamConnection trên 1 socket TCP (LAN Mode) */
public final class SocketStreamConnection implements StreamConnection {

    private final Socket socket;

    public SocketStreamConnection(Socket socket) {
        this.socket = socket;
    }

    /** Mở kết nối TCP, báo lỗi sau connectTimeoutMs nếu máy kia không trả lời */
    public static SocketStreamConnection connect(String host, int port, int connectTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            return new SocketStreamConnection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void setReadTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    @Override
    public String describeRemote() {
        return socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
