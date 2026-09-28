package io.zodia.pgledger.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class NoSessionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public HttpSession getSession() {
                throw new IllegalStateException("sessions are disabled");
            }

            @Override
            public HttpSession getSession(boolean create) {
                if (create) {
                    throw new IllegalStateException("sessions are disabled");
                }
                return null;
            }
        }, response);
    }
}
