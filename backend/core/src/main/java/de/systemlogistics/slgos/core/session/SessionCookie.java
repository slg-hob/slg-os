package de.systemlogistics.slgos.core.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/** The session cookie: {@code __Host-} prefix, HttpOnly, Secure, SameSite=Strict, no Domain (PRD-0003 SK-3 AC2). */
public final class SessionCookie {

    public static final String NAME = "__Host-slg-session";

    private SessionCookie() {}

    /** The cookie value of the request, or null. */
    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The Set-Cookie header value that stores the id in the browser (a browser-session cookie; timeouts are server-side). */
    public static String issue(String id) {
        return base(id).build().toString();
    }

    /** The Set-Cookie header value that deletes the cookie. */
    public static String clear() {
        return base("").maxAge(0).build().toString();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path("/");
    }
}
