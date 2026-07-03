package me.d1n0.saddle.dap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Listens for DAP clients. Only one debug session is active at a time; a new
 * connection replaces the previous one (e.g. a VS Code restart).
 */
public final class DapServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("saddle");

    private static final Object SESSION_LOCK = new Object();
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static DapSession activeSession;

    private DapServer() {}

    public static synchronized void start(String host, int port) {
        if (acceptThread != null && acceptThread.isAlive()) return;
        ServerSocket socket;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host, port));
        } catch (IOException e) {
            LOGGER.error("Failed to bind DAP server to {}:{} — is another Minecraft instance with "
                    + "Saddle already running? Debug clients connecting to this port will reach "
                    + "THAT instance, not this world. Use -Dsaddle.port to pick a free port.",
                    host, port, e);
            return;
        }
        serverSocket = socket;
        acceptThread = new Thread(() -> acceptLoop(socket), "saddle-dap-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOGGER.info("Saddle DAP server listening on {}:{}", host, port);
    }

    public static synchronized void stop() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        acceptThread = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (SESSION_LOCK) {
            if (activeSession != null) {
                activeSession.close();
                activeSession = null;
            }
        }
    }

    private static void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            DapSession session;
            try {
                session = new DapSession(socket.accept());
            } catch (IOException e) {
                if (!socket.isClosed()) LOGGER.warn("DAP accept failed: {}", e.toString());
                break;
            }
            synchronized (SESSION_LOCK) {
                if (activeSession != null) {
                    LOGGER.info("Replacing existing DAP session");
                    activeSession.close();
                }
                activeSession = session;
            }
            Thread thread = new Thread(() -> {
                try {
                    session.run();
                } finally {
                    synchronized (SESSION_LOCK) {
                        if (activeSession == session) activeSession = null;
                    }
                }
            }, "saddle-dap-session");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
