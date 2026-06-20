package com.devicespooflab.hooks.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Transparent SOCKS5 relay: accepts iptables-redirected TCP connections,
 * reads the original destination via SO_ORIGINAL_DST, and forwards through
 * the configured remote SOCKS5 server (with optional auth).
 */
public class SocksRelayServer {

    private final String profileId;
    private final int bindPort;
    private final ProxyConfig proxy;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workerPool;
    private volatile boolean running;

    public SocksRelayServer(String profileId, int bindPort, ProxyConfig proxy) {
        this.profileId = profileId;
        this.bindPort = bindPort;
        this.proxy = proxy;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(Inet4Address.getByName("127.0.0.1"), bindPort));
        workerPool = Executors.newCachedThreadPool();
        running = true;
        acceptThread = new Thread(this::acceptLoop, "spoofproxy-" + profileId);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public int getLocalPort() {
        return (serverSocket != null && !serverSocket.isClosed())
            ? serverSocket.getLocalPort()
            : bindPort;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                workerPool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    android.util.Log.w("SocksRelay", "Accept error on " + bindPort, e);
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30_000);
            InetSocketAddress originalDst = OrigDst.getOriginalDst(client);
            if (originalDst == null || isLoopback(originalDst)) {
                // Not a redirected connection — reject silently
                client.close();
                return;
            }

            Socket remote = new Socket();
            try {
                remote.connect(new InetSocketAddress(proxy.host, proxy.port), 15_000);
                remote.setSoTimeout(30_000);
                socks5Handshake(remote, originalDst);
                pipe(client, remote);
            } finally {
                safeClose(remote);
            }
        } catch (Exception ignored) {
        } finally {
            safeClose(client);
        }
    }

    private boolean isLoopback(InetSocketAddress addr) {
        return addr.getAddress() != null && addr.getAddress().isLoopbackAddress();
    }

    private void socks5Handshake(Socket remote, InetSocketAddress dst) throws IOException {
        OutputStream out = remote.getOutputStream();
        InputStream in = remote.getInputStream();

        // Method negotiation
        if (proxy.hasAuth()) {
            out.write(new byte[]{0x05, 0x01, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] reply = readExact(in, 2);
        if (reply[0] != 0x05) throw new IOException("Not SOCKS5");

        if (proxy.hasAuth()) {
            if (reply[1] != 0x02) throw new IOException("Auth method rejected");
            byte[] user = proxy.user.getBytes(StandardCharsets.UTF_8);
            byte[] pass = (proxy.password != null ? proxy.password : "")
                .getBytes(StandardCharsets.UTF_8);
            byte[] authPkt = new byte[3 + user.length + pass.length];
            authPkt[0] = 0x01;
            authPkt[1] = (byte) user.length;
            System.arraycopy(user, 0, authPkt, 2, user.length);
            authPkt[2 + user.length] = (byte) pass.length;
            System.arraycopy(pass, 0, authPkt, 3 + user.length, pass.length);
            out.write(authPkt);
            out.flush();
            byte[] authReply = readExact(in, 2);
            if (authReply[1] != 0x00) throw new IOException("SOCKS5 auth failed");
        } else {
            if (reply[1] != 0x00) throw new IOException("No-auth rejected");
        }

        // CONNECT request — IPv4 only (SO_ORIGINAL_DST gives IPv4)
        byte[] ip = dst.getAddress().getAddress();
        int port = dst.getPort();
        byte[] req = {
            0x05, 0x01, 0x00, 0x01,
            ip[0], ip[1], ip[2], ip[3],
            (byte) (port >> 8), (byte) port
        };
        out.write(req);
        out.flush();

        byte[] resp = readExact(in, 4);
        if (resp[0] != 0x05 || resp[1] != 0x00)
            throw new IOException("CONNECT refused: " + (resp[1] & 0xFF));

        // Drain bound address from server reply
        switch (resp[3]) {
            case 0x01: readExact(in, 6); break;
            case 0x03: readExact(in, (in.read() & 0xFF) + 2); break;
            case 0x04: readExact(in, 18); break;
        }
    }

    private void pipe(final Socket client, final Socket remote) throws IOException {
        Thread t = new Thread(() -> {
            try {
                transfer(remote.getInputStream(), client.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                safeClose(client);
                safeClose(remote);
            }
        }, "spoofproxy-r2c");
        t.setDaemon(true);
        t.start();
        try {
            transfer(client.getInputStream(), remote.getOutputStream());
        } finally {
            safeClose(client);
            safeClose(remote);
        }
        try {
            t.join(5_000);
        } catch (InterruptedException ignored) {
        }
    }

    private void transfer(InputStream src, OutputStream dst) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = src.read(buf)) != -1) {
            dst.write(buf, 0, n);
            dst.flush();
        }
    }

    private byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Premature EOF");
            off += n;
        }
        return buf;
    }

    private static void safeClose(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
