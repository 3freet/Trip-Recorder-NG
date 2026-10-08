package org.triprecorderng;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** This app's own RSA identity for the head unit's network debugging (ADB) authorisation. */
final class AdbKey {
    // DER prefix of a SHA-1 DigestInfo: ADB signs the 20-byte token as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final PrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKey(PrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    static AdbKey loadOrCreate(Context ctx) throws Exception {
        File pk = new File(ctx.getFilesDir(), "adbkey.pk8");
        File pb = new File(ctx.getFilesDir(), "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pk.exists() && pb.exists()) {
            try {
                PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pk)));
                RSAPublicKey q = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(pb)));
                return new AdbKey(p, q);
            } catch (Exception e) {
                Diag.log("adb key unreadable, creating a new one", e);
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(pk, kp.getPrivate().getEncoded());
        writeAll(pb, kp.getPublic().getEncoded());
        Diag.log("created a new adb key");
        return new AdbKey(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** Removes this app's stored debugging key (a new one, needing approval again, is made on next use). */
    static void delete(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), "adbkey.pk8").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), "adbkey.x509").delete();
        Diag.log("adb key removed");
    }

    byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** The public key in Android's ADB text format (base64 of the "mincrypt" struct + a name). */
    byte[] publicKeyText(String name) {
        BigInteger n = pub.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = two32.subtract(n.mod(two32).modInverse(two32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        putInt(o, 64);
        putInt(o, n0inv.intValue());
        o.write(littleEndian(n, 256), 0, 256);
        o.write(littleEndian(rr, 256), 0, 256);
        putInt(o, pub.getPublicExponent().intValue());
        String b64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        return (b64 + " " + name + "\0").getBytes();
    }

    private static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            return b;
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
