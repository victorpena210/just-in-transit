package com.victorpena.just_in_transit;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.UUID;
import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
@Component public class RequestReferenceFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString();req.setAttribute("requestReference",id);res.setHeader("X-Request-Reference",id);chain.doFilter(req,res);
    }
}
