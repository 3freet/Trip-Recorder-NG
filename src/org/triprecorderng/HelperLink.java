package org.triprecorderng;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A connection to the helper of its own, for what has to answer within a fraction of a second (the turn signal)
 * and so cannot wait behind the recorder's once-a-second reads on the shared connection.
 */
final class HelperLink {
    private final Context ctx;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String lastReply = "";
    private int gear = -1;

    HelperLink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Opens the connection and checks the helper is the version this build expects. */
    synchronized boolean connect() {
        close();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", HelperVehicle.PORT), 800);
            s.setSoTimeout(1500);
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out = new PrintWriter(s.getOutputStream(), true);
            out.println(HelperLauncher.token(ctx));
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("ok") || !hello.contains(Helper.VERSION)) {
                close();
                return false;
            }
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    synchronized boolean connected() {
        return socket != null;
    }

    /** The turn signal (1 off, 2 and 3 left, 4 and 5 right), or -1 when it cannot be read. */
    synchronized int turn() {
        return ask("turn", "t1");
    }

    /** Starts (1) or stops (2) the head-unit-to-instrument projection, or just reads it (0); returns the state, or -1. */
    synchronized int projection(int state) {
        return ask("proj " + state, "p1");
    }

    private int ask(String request, String tag) {
        if (socket == null) return -1;
        try {
            out.println(request);
            String line = in.readLine();
            if (line == null) throw new IOException("helper closed the connection");
            lastReply = line;
            String[] p = line.split(" ");
            if (p.length >= 5 && p[0].equals("t1")) {
                try {
                    gear = Integer.parseInt(p[4]);
                } catch (NumberFormatException e) {
                    gear = -1;
                }
            }
            if (p.length < 2 || !p[0].equals(tag)) return -1; // an older helper does not know the request
            try {
                return Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (IOException e) {
            close();
            return -1;
        }
    }

    /** The gearbox mode from the last turn reading (1 = P, 4 = D), or -1 when it was not there. */
    synchronized int gear() {
        return gear;
    }

    /** The helper's last answer in full, for the log. */
    synchronized String lastReply() {
        return lastReply;
    }

    synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        socket = null;
        in = null;
        out = null;
    }
}
