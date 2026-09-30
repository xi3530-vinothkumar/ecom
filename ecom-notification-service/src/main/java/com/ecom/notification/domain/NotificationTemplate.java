package com.ecom.notification.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subject and body with {@code ${var}} placeholders. */
public record NotificationTemplate(String key, Channel channel, String subject, String body) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");

    public record Rendered(String subject, String body) {}

    public Rendered render(Map<String, String> vars) {
        return new Rendered(render(key, subject, vars), render(key, body, vars));
    }

    /**
     * Single pass: substituted values are never re-scanned, so a user-controlled value containing
     * {@code ${...}} cannot inject other variables. A missing variable is a template/code bug and fails.
     */
    static String render(String key, String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("Template " + key + " needs variable '" + m.group(1) + "'");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
