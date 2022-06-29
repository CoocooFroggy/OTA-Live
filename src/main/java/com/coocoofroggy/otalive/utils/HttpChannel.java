package com.coocoofroggy.otalive.utils;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;

public final class HttpChannel implements SeekableByteChannel {
    private final URL url;
    private ReadableByteChannel ch;
    private long pos;
    private long length;

    public HttpChannel(URL url) {
        this.url = url;
    }

    @Override
    public long position() {
        return pos;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition == pos) {
            return this;
        } else if (ch != null) {
            ch.close();
            ch = null;
        }
        pos = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new UnsupportedOperationException("Truncate on HTTP is not supported.");
    }

    @Override
    public int read(ByteBuffer buffer) throws IOException {
        ensureOpen();
        int read = ch.read(buffer);
        if (read != -1)
            pos += read;
        return read;
    }

    @Override
    public int write(ByteBuffer buffer) {
        throw new UnsupportedOperationException("Write to HTTP is not supported.");
    }

    @Override
    public boolean isOpen() {
        return ch != null && ch.isOpen();
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }

    private void ensureOpen() throws IOException {
        if (ch == null) {
            URLConnection connection = url.openConnection();
            if (pos > 0)
                connection.addRequestProperty("Range", "bytes=" + pos + "-");
            ch = Channels.newChannel(connection.getInputStream());
            String resp = connection.getHeaderField("Content-Range");
            if (resp != null) {
                length = Long.parseLong(resp.split("/")[1]);
            } else {
                resp = connection.getHeaderField("Content-Length");
                length = Long.parseLong(resp);
            }
        }
    }
}