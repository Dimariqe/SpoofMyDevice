package com.devicespooflab.hooks.proxy;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketImpl;

public final class OrigDst {

    static {
        try {
            System.loadLibrary("spoofproxy");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private static native int nativeReadDst(int fd, byte[] out);

    public static InetSocketAddress getOriginalDst(Socket socket) {
        try {
            int fd = getFd(socket);
            if (fd < 0) return null;
            byte[] buf = new byte[6];
            if (nativeReadDst(fd, buf) != 0) return null;
            byte[] ipBytes = new byte[4];
            System.arraycopy(buf, 0, ipBytes, 0, 4);
            Inet4Address addr = (Inet4Address) Inet4Address.getByAddress(ipBytes);
            int port = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
            return new InetSocketAddress(addr, port);
        } catch (Exception e) {
            return null;
        }
    }

    private static int getFd(Socket socket) {
        try {
            Method m = socket.getClass().getDeclaredMethod("getFileDescriptor$");
            m.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) m.invoke(socket);
            return fdInt(fd);
        } catch (Exception ignored) {
        }
        try {
            Field implField = Socket.class.getDeclaredField("impl");
            implField.setAccessible(true);
            SocketImpl impl = (SocketImpl) implField.get(socket);
            Field fdField = SocketImpl.class.getDeclaredField("fd");
            fdField.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) fdField.get(impl);
            return fdInt(fd);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static int fdInt(FileDescriptor fd) {
        if (fd == null) return -1;
        try {
            Method m = FileDescriptor.class.getDeclaredMethod("getInt$");
            m.setAccessible(true);
            return (int) m.invoke(fd);
        } catch (Exception ignored) {
        }
        try {
            Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            return (int) f.get(fd);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private OrigDst() {}
}
