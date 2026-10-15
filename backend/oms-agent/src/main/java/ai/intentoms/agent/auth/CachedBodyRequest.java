package ai.intentoms.agent.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Buffers the request body once so the signing filter can hash it and the controller can still read it. */
public final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        try (InputStream in = request.getInputStream()) {
            byte[] buf = in.readNBytes(maxBytes + 1);
            if (buf.length > maxBytes) throw new IOException("Request body exceeds " + maxBytes + " bytes");
            this.body = buf;
        }
    }

    public byte[] body() { return body; }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("async read not supported"); }
        };
    }

    @Override
    public BufferedReader getReader() {
        String enc = getCharacterEncoding();
        Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }

    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }
}
