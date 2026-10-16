package com.victorpena.just_in_transit;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+50)
public class BrowserIdentityFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        String p=r.getRequestURI();return p.startsWith("/vendor/") || p.matches(".*\\.(css|js|svg|png|ico|webmanifest)$") || p.equals("/offline.html");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String token=null;
        if(request.getCookies()!=null)for(var c:request.getCookies())if(BrowserIdentity.COOKIE.equals(c.getName()) && BrowserIdentity.valid(c.getValue()))token=c.getValue();
        var session=request.getSession(false);
        String old=session==null?null:(String)session.getAttribute(BrowserIdentity.SESSION_TOKEN);
        // Prevent a session's live plan from following a different browser credential.
        if(token!=null && old!=null && !token.equals(old)) { session.invalidate();session=null;old=null; }
        boolean issue=token==null;
        if(token==null)token=BrowserIdentity.valid(old)?old:BrowserIdentity.newToken();
        request.getSession(true).setAttribute(BrowserIdentity.SESSION_TOKEN,token);
        request.setAttribute(BrowserIdentity.ATTRIBUTE,BrowserIdentity.hash(token));
        if(issue)response.addHeader("Set-Cookie",ResponseCookie.from(BrowserIdentity.COOKIE,token).httpOnly(true).secure(request.isSecure())
            .sameSite("Lax").path("/").maxAge(365L*24*3600).build().toString());
        chain.doFilter(request,response);
    }
}
