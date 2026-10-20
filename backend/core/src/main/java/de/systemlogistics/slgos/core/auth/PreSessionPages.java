package de.systemlogistics.slgos.core.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Static HTML pages outside the shell (PRD-0003 SK-3 AC9): callback interstitial, signed-out, callback error, sign-in
 * unavailable, rate limit and the popup landing page. Texts are German and English, chosen by {@code Accept-Language}, then
 * German. Texts from outside are never inserted except the already validated return target (HTML-escaped).
 */
@Component
public class PreSessionPages {

    /** The pages. */
    public enum Page {
        INTERSTITIAL("interstitial", false),
        SIGNED_OUT("signedOut", false),
        CALLBACK_ERROR("callbackError", true),
        SIGN_IN_UNAVAILABLE("unavailable", true),
        RATE_LIMITED("rateLimited", true),
        POPUP_DONE("popupDone", false),
        APP_PLACEHOLDER("appPlaceholder", false);

        private final String key;
        private final boolean alert;

        Page(String key, boolean alert) {
            this.key = key;
            this.alert = alert;
        }
    }

    private final Map<String, Properties> messages = Map.of("de", load("de"), "en", load("en"));

    /** The language of the response: the first of {@code de} or {@code en} in the header by quality, else {@code de}. */
    public static String pickLanguage(String acceptLanguage) {
        if (acceptLanguage == null) {
            return "de";
        }
        String best = "de";
        double bestQuality = -1;
        for (String part : acceptLanguage.split(",")) {
            String[] fields = part.strip().split(";");
            String tag = fields[0].strip().toLowerCase(Locale.ROOT);
            double quality = 1;
            for (int i = 1; i < fields.length; i++) {
                String field = fields[i].strip();
                if (field.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(field.substring(2));
                    } catch (NumberFormatException e) {
                        quality = 0;
                    }
                }
            }
            String primary = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
            if (("de".equals(primary) || "en".equals(primary)) && quality > bestQuality && quality > 0) {
                best = primary;
                bestQuality = quality;
            }
        }
        return best;
    }

    /**
     * Renders a page.
     *
     * @param target the validated return target for the interstitial (meta refresh and visible link); ignored otherwise
     */
    public String render(Page page, String language, String target) {
        Properties text = messages.get(messages.containsKey(language) ? language : "de");
        String lang = messages.containsKey(language) ? language : "de";
        String title = text.getProperty(page.key + ".title");
        StringBuilder html = new StringBuilder(1024);
        html.append("<!doctype html>\n<html lang=\"").append(lang).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n");
        if (page == Page.INTERSTITIAL) {
            html.append("<meta http-equiv=\"refresh\" content=\"0;url=").append(escape(target)).append("\">\n");
        }
        html.append("<title>").append(escape(title)).append(" - SLG OS</title>\n")
                .append("<link rel=\"stylesheet\" href=\"/core/auth/assets/pages.css\">\n</head>\n<body>\n<main class=\"card\">\n")
                .append("<h1>").append(escape(title)).append("</h1>\n<p");
        if (page.alert) {
            html.append(" role=\"alert\"");
        }
        html.append('>').append(escape(text.getProperty(page.key + ".text"))).append("</p>\n");
        switch (page) {
            case INTERSTITIAL -> html.append("<p><a href=\"").append(escape(target)).append("\">")
                    .append(escape(text.getProperty("interstitial.link"))).append("</a></p>\n");
            case SIGNED_OUT, CALLBACK_ERROR, RATE_LIMITED -> html.append("<p><a href=\"/core/auth/login\">")
                    .append(escape(text.getProperty("signIn.link"))).append("</a></p>\n");
            case SIGN_IN_UNAVAILABLE -> html.append("<p><a href=\"/core/auth/login\">")
                    .append(escape(text.getProperty("retry.link"))).append("</a></p>\n");
            case POPUP_DONE -> html.append("<p><button type=\"button\" id=\"close-window\">")
                    .append(escape(text.getProperty("popupDone.close"))).append("</button></p>\n")
                    .append("<script src=\"/core/auth/assets/popup-done.js\"></script>\n");
            default -> { }
        }
        return html.append("</main>\n</body>\n</html>\n").toString();
    }

    /** Escapes text for HTML content and attribute values. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static Properties load(String language) {
        Properties properties = new Properties();
        try (InputStream in = PreSessionPages.class.getResourceAsStream("/pages/messages_" + language + ".properties")) {
            if (in == null) {
                throw new IllegalStateException("missing page texts for " + language);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
