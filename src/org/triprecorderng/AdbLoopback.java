package org.triprecorderng;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal ADB client that talks to the head unit's own network-debugging daemon on 127.0.0.1.
 * It is used once per boot to start the small helper process; the first connection needs the
 * "Allow debugging?" prompt on the car screen to be accepted (tick "Always allow").
 */
final class AdbLoopback {
    static final int PORT = 5555;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;

    enum Status { OK, UNREACHABLE, WAITING_FOR_APPROVAL, REFUSED, ERROR }

    static final class Result {
        final Status status;
        final String output;
        final String detail;

        Result(Status s, String output, String detail) {
            this.status = s;
            this.output = output;
            this.detail = detail;
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final Context ctx;
    private final StringBuilder timing = new StringBuilder();
    private long t0;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    AdbLoopback(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Runs one shell command through ADB and returns its output. */
    private void mark(String what) {
        timing.append(what).append('=').append(System.currentTimeMillis() - t0).append("ms ");
    }

    Result runShell(String command, int approvalTimeoutMs) {
        t0 = System.currentTimeMillis();
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            socket.setTcpNoDelay(true);
            mark("connected");
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        } catch (IOException e) {
            return new Result(Status.UNREACHABLE, "", "cannot reach 127.0.0.1:" + PORT + " ("
                    + e.getMessage() + ") - is network debugging on?");
        }
        try {
            Status st = handshake(approvalTimeoutMs);
            mark("handshake");
            if (st != Status.OK) return new Result(st, "", "handshake " + st);
            String out2 = shell(command);
            mark("shell");
            return new Result(Status.OK, out2, "");
        } catch (SocketTimeoutException te) {
            return new Result(Status.WAITING_FOR_APPROVAL, "", "no answer (approval pending?)");
        } catch (Throwable t) {
            return new Result(Status.ERROR, "", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            close();
            Diag.log("adb timing: " + timing.toString().trim());
        }
    }

    private Status handshake(int approvalTimeoutMs) throws Exception {
        AdbKey key = AdbKey.loadOrCreate(ctx);
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes());
        boolean sentSignature = false;
        boolean sentKey = false;
        socket.setSoTimeout(8000);
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) return Status.OK;
            if (m.cmd == A_AUTH && m.arg0 == 1) {
                mark(sentSignature ? "token2" : "token1");
                if (!sentSignature) {
                    sentSignature = true;
                    send(A_AUTH, 2, 0, key.sign(m.data));
                } else if (!sentKey) {
                    sentKey = true;
                    send(A_AUTH, 3, 0, key.publicKeyText("triprec@headunit"));
                    // the car now shows a prompt; give the user time to accept it
                    socket.setSoTimeout(approvalTimeoutMs);
                    Diag.log("adb: key sent, waiting for approval on the car screen");
                } else {
                    return Status.REFUSED;
                }
            } else if (m.cmd == A_CLSE) {
                return Status.REFUSED;
            }
        }
    }

    private String shell(String command) throws IOException {
        socket.setSoTimeout(15000);
        final int localId = 1;
        send(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.cmd == A_WRTE) {
                buf.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, m.arg0, null);
            } else if (m.cmd == A_CLSE) {
                send(A_CLSE, localId, m.arg0 != 0 ? m.arg0 : remoteId, null);
                break;
            }
        }
        return buf.toString("UTF-8");
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(0).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_DATA) throw new IOException("bad adb frame length " + len);
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
