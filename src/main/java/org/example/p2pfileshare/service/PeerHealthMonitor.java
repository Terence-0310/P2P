package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Connection Monitor: định kỳ PING từng peer đang kết nối (cả LAN và Internet) để phát hiện peer mất mạng
 * hoặc tắt máy đột ngột (không kịp gửi DISCONNECT).
 *
 * Phân biệt rõ 3 trường hợp:
 * - relay của mình mất kết nối -> peer Internet ở trạng thái RECONNECTING (chưa biết peer còn sống không)
 * - relay còn sống nhưng peer không trả lời PING trong timeout (hoặc relay báo peer OFFLINE) -> OFFLINE
 * - peer trả lời nhưng không còn chấp nhận mình -> gỡ kết nối (ConnectionService.handleRemoteGone)
 * Mất kết nối không thu hồi quyền: peer quay lại là tải tiếp được.
 */
public class PeerHealthMonitor implements AutoCloseable {

    public enum Health {
        ONLINE, OFFLINE, CONNECTING, RECONNECTING
    }

    public interface Listener {
        /** Gọi trên luồng nền */
        void onHealthChanged(String peerId, Health health);
    }

    private final ConnectionService connections;
    private final ControlClient client;
    private final long intervalMs;
    private final long timeoutMs;
    private final Map<String, Long> lastOk = new ConcurrentHashMap<>();
    private final Map<String, Health> health = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile BooleanSupplier relayOnline = () -> false;
    private volatile Consumer<String> onReachable = id -> {};
    private ScheduledExecutorService timer;
    private ExecutorService pingers;

    public PeerHealthMonitor(ConnectionService connections, ControlClient client, long intervalMs, long timeoutMs) {
        this.connections = connections;
        this.client = client;
        this.intervalMs = intervalMs;
        this.timeoutMs = Math.max(timeoutMs, intervalMs);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Relay của mình có đang online không (Internet Mode) */
    public void setRelayOnline(BooleanSupplier relayOnline) {
        this.relayOnline = relayOnline == null ? () -> false : relayOnline;
    }

    /** Gọi mỗi lần PING peer thành công (vd. đánh thức tải xuống đang chờ mạng để tải tiếp ngay) */
    public void setOnReachable(Consumer<String> onReachable) {
        this.onReachable = onReachable == null ? id -> {} : onReachable;
    }

    public synchronized void start() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "peer-health"));
        pingers = Executors.newFixedThreadPool(4, r -> daemon(r, "peer-ping"));
        timer.scheduleWithFixedDelay(this::safeTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (timer != null) timer.shutdownNow();
        if (pingers != null) pingers.shutdownNow();
        timer = null;
    }

    public Health getHealth(String peerId) {
        return health.get(peerId);
    }

    /** Relay báo peer online / offline (chỉ áp dụng cho peer Internet) */
    public void onPresence(String peerId, boolean online) {
        if (!connections.isConnected(peerId)) return;
        if (online) {
            pingNow(peerId);
        } else {
            set(peerId, Health.OFFLINE);
        }
    }

    /** Trạng thái relay của mình thay đổi */
    public void onRelayStateChanged(boolean online) {
        for (PeerInfo p : connections.getConnectedPeers()) {
            if (!p.isRelay()) continue;
            if (online) {
                // cho peer thời gian kết nối lại relay trước khi tính timeout
                lastOk.put(p.getPeerId(), System.currentTimeMillis());
                pingNow(p.getPeerId());
            } else {
                set(p.getPeerId(), Health.RECONNECTING);
            }
        }
    }

    /** PING ngay 1 peer (không chờ chu kỳ) */
    public void pingNow(String peerId) {
        PeerInfo p = connections.getConnectedPeer(peerId);
        if (p != null) schedulePing(p);
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            System.err.println("[PeerHealthMonitor] " + e);
        }
    }

    void tick() {
        List<PeerInfo> peers = connections.getConnectedPeers();
        Set<String> ids = new HashSet<>();
        for (PeerInfo p : peers) ids.add(p.getPeerId());
        lastOk.keySet().retainAll(ids);
        health.keySet().retainAll(ids);
        for (PeerInfo p : peers) {
            if (p.isRelay() && !relayOnline.getAsBoolean()) {
                set(p.getPeerId(), Health.RECONNECTING);
                continue;
            }
            schedulePing(p);
        }
    }

    private void schedulePing(PeerInfo p) {
        String id = p.getPeerId();
        lastOk.putIfAbsent(id, System.currentTimeMillis());
        if (!inFlight.add(id)) return;
        ExecutorService exec = pingers;
        try {
            if (exec == null) throw new RejectedExecutionException();
            exec.execute(() -> {
                try {
                    handle(p, client.ping(p));
                } finally {
                    inFlight.remove(id);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(id);
        }
    }

    private void handle(PeerInfo p, ControlClient.PingResult result) {
        String id = p.getPeerId();
        if (!connections.isConnected(id)) return;
        switch (result) {
            case OK -> {
                lastOk.put(id, System.currentTimeMillis());
                set(id, Health.ONLINE);
                onReachable.accept(id);
            }
            case NOT_ACCEPTED -> {
                System.out.println("[PeerHealthMonitor] Peer " + id + " không còn chấp nhận kết nối, gỡ kết nối");
                lastOk.remove(id);
                health.remove(id);
                connections.handleRemoteGone(id);
            }
            case UNREACHABLE -> {
                if (p.isRelay() && !relayOnline.getAsBoolean()) {
                    set(id, Health.RECONNECTING);
                    return;
                }
                long last = lastOk.getOrDefault(id, 0L);
                if (System.currentTimeMillis() - last >= timeoutMs) set(id, Health.OFFLINE);
            }
        }
    }

    private void set(String peerId, Health h) {
        Health old = health.put(peerId, h);
        if (old == h) return;
        for (Listener l : listeners) {
            try {
                l.onHealthChanged(peerId, h);
            } catch (RuntimeException e) {
                System.err.println("[PeerHealthMonitor] listener: " + e);
            }
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
