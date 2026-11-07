package edu.iitm.instichain.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeFilterTest {
    private final RequestSizeFilter filter = new RequestSizeFilter();

    private MockHttpServletRequest post(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/shares");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void oversizedDeclaredBodyIsRejectedBeforeTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post((int) RequestSizeFilter.MAX_BODY_BYTES + 1), response, chain);
        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest(), "the request must not reach the controller");
    }

    @Test
    void undeclaredLengthIsCappedWhileReading() throws Exception {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/shares") {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent(new byte[(int) RequestSizeFilter.MAX_BODY_BYTES + 10]);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(chunked, new MockHttpServletResponse(), chain);
        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThrows(IOException.class, () -> passed.getInputStream().readAllBytes());
    }

    @Test
    void normalBodiesPassUnchanged() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post(2048), new MockHttpServletResponse(), chain);
        assertEquals(2048, ((HttpServletRequest) chain.getRequest()).getInputStream().readAllBytes().length);
    }
}
