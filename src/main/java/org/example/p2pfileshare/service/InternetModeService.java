package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlProtocol;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.network.relay.IdentityKey;
import org.example.p2pfileshare.network.relay.RelayClient;
import org.example.p2pfileshare.network.relay.RelaySettings;
import org.example.p2pfileshare.network.relay.RelayStream;
import org.example.p2pfileshare.network.relay.RelayTransport;
import org.example.p2pfileshare.network.transport.Channel;
import org.example.p2pfileshare.network.transport.InboundOrigin;
import org.example.p2pfileshare.network.transport.TransportRouter;
import org.example.p2prelay.protocol.RelayProtocol;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Internet Mode: nối Relay Server với các service nghiệp vụ sẵn có.
 *
 * - Luồng đến từ relay: kênh control -> ControlServer, kênh file -> ChunkedFileServer (cùng code với LAN).
 * - Luồng đi tới peer Internet: TransportRouter chọn RelayTransport theo PeerInfo.Route.RELAY.
 * - Kết nối bằng mã mời: tra mã -> danh tính peer -> gửi CONNECT_REQUEST như LAN -> bên kia bấm Chấp nhận / Từ chối.
 */
public class InternetModeService implements AutoCloseable {

    private final IdentityKey identity;
    private final Supplier<String> displayName;
    private final ControlServer controlServer;
    private final FileShareService fileShareService;
    private final ConnectionService connectionService;
    private final TransportRouter router;
    private final PeerHealthMonitor monitor;
    private final List<RelayClient.Listener> listeners = new CopyOnWriteArrayList<>();
    // link token relay cấp, giữ qua các lần kết nối lại / đổi relay
    private final Map<String, String> linkTokens = new ConcurrentHashMap<>();

    private volatile RelayClient client;

    public InternetModeService(IdentityKey identity, Supplier<String> displayName, ControlServer controlServer,
                               FileShareService fileShareService, ConnectionService connectionService,
                               TransportRouter router, PeerHealthMonitor monitor) {
        this.identity = identity;
        this.displayName = displayName;
        this.controlServer = controlServer;
        this.fileShareService = fileShareService;
        this.connectionService = connectionService;
        this.router = router;
        this.monitor = monitor;
        if (monitor != null) monitor.setRelayOnline(this::isRelayOnline);

        // Ngắt kết nối với peer Internet (do mình hoặc do peer) -> huỷ luôn liên kết trên relay
        connectionService.addListener(new ConnectionService.Listener() {
            @Override
            public void onDisconnected(String peerId, boolean byRemote) {
                RelayClient c = client;
                if (c != null && c.isLinked(peerId)) c.unlink(peerId);
            }
        });
    }

    /** Listener được gắn vào mọi RelayClient tạo ra (kể cả khi đổi URL relay) */
    public void addRelayListener(RelayClient.Listener l) {
        listeners.add(l);
        RelayClient c = client;
        if (c != null) c.addListener(l);
    }

    /** Kết nối relay (chạy nền, tự kết nối lại). Ném IllegalArgumentException nếu URL không hợp lệ. */
    public synchronized void connect(RelaySettings settings) {
        RelayClient old = client;
        if (old != null) old.close();

        RelayClient c = new RelayClient(settings, identity, displayName, linkTokens);
        c.setInboundHandler(this::dispatchInbound);
        c.addListener(new RelayClient.Listener() {
            @Override
            public void onStateChanged(RelayClient.State state, String detail) {
                if (monitor != null && client == c) monitor.onRelayStateChanged(isRelayOnline());
            }

            @Override
            public void onPresence(String peerId, boolean online) {
                if (monitor != null) monitor.onPresence(peerId, online);
            }
        });
        for (RelayClient.Listener l : listeners) c.addListener(l);
        client = c;
        router.setRelayTransport(new RelayTransport(c));
        c.start();
    }

    /** Ngắt relay. Kết nối LAN không bị ảnh hưởng. */
    public synchronized void disconnect() {
        RelayClient c = client;
        client = null;
        router.setRelayTransport(null);
        if (c != null) c.close();
    }

    @Override
    public void close() {
        disconnect();
    }

    public boolean isRelayOnline() {
        RelayClient c = client;
        return c != null && c.isOnline();
    }

    public RelayClient.State getState() {
        RelayClient c = client;
        return c == null ? RelayClient.State.DISCONNECTED : c.getState();
    }

    public String getStateDetail() {
        RelayClient c = client;
        return c == null ? "Chưa kết nối relay" : c.getStateDetail();
    }

    /** RelayClient hiện tại (null nếu chưa bật Internet Mode) */
    public RelayClient getRelayClient() {
        return client;
    }

    public String getMyPeerId() {
        return identity.peerId();
    }

    /** Tạo mã kết nối mới (mã cũ hết hiệu lực) để đọc cho máy kia */
    public RelayClient.Invite createInvite() throws IOException {
        return requireClient().createInvite();
    }

    /**
     * Nhập mã của máy kia: relay tra ra danh tính, rồi gửi yêu cầu kết nối và chờ bên kia quyết định
     * (chặn luồng tối đa ~1 phút, phải gọi ở luồng nền).
     *
     * @return peer đã kết nối
     * @throws IOException mã sai / hết hạn / máy kia offline / từ chối (thông báo tiếng Việt trong getMessage)
     */
    public PeerInfo connectByCode(String code) throws IOException {
        RelayClient c = requireClient();
        RelayClient.RemotePeer remote = c.resolveInvite(code);
        String name = remote.displayName().isBlank()
                ? "Peer " + RelayProtocol.shortId(remote.peerId()) : remote.displayName();
        PeerInfo existing = connectionService.getConnectedPeer(remote.peerId());
        if (existing != null && existing.isRelay()) return existing;

        PeerInfo peer = PeerInfo.viaRelay(remote.peerId(), name);
        if (!connectionService.connect(peer)) {
            c.unlink(remote.peerId());
            throw new IOException("\"" + name + "\" đã từ chối hoặc không trả lời trong "
                    + ControlProtocol.CONNECT_DECISION_SECONDS + " giây");
        }
        return peer;
    }

    /** Luồng do peer Internet mở tới mình: giao cho đúng server như kết nối TCP trong LAN */
    private void dispatchInbound(RelayStream stream) {
        InboundOrigin origin = InboundOrigin.relay(stream.remotePeerId());
        if (stream.channel() == Channel.CONTROL) {
            controlServer.acceptExternal(stream, origin);
        } else {
            fileShareService.acceptInboundFileStream(stream, origin);
        }
    }

    private RelayClient requireClient() throws IOException {
        RelayClient c = client;
        if (c == null) throw new IOException("Chưa kết nối relay");
        return c;
    }
}
