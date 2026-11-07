package edu.iitm.instichain.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps API request bodies. Spring places no limit on JSON bodies, and the largest legitimate
 * one, a signed issuance for 100 recipients, is about 20 KB.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {
    static final long MAX_BODY_BYTES = 256 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"detail\":\"The request body is too large.\"}");
            return;
        }
        // A chunked body declares no length, so its stream is capped as it is read.
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() throws IOException {
                return new Limited(super.getInputStream());
            }
        }, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    static final class Limited extends ServletInputStream {
        private final ServletInputStream source;
        private long read;

        Limited(ServletInputStream source) { this.source = source; }

        private int count(int bytes) throws IOException {
            if (bytes > 0 && (read += bytes) > MAX_BODY_BYTES) throw new IOException("The request body is too large.");
            return bytes;
        }

        @Override public int read() throws IOException {
            int value = source.read();
            if (value >= 0) count(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return count(source.read(buffer, offset, length));
        }
        @Override public boolean isFinished() { return source.isFinished(); }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
    }
}
