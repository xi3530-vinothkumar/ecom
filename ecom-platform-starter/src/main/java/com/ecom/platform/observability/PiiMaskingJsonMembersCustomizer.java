package com.ecom.platform.observability;

import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks e-mail addresses and phone numbers in every string value of a structured (JSON) log line:
 * message, MDC entries, stack trace. Registered via {@code logging.structured.json.customizer}.
 */
public class PiiMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+[0-9][0-9 ()-]{7,}[0-9]");

    @Override
    public void customize(Members<Object> members) {
        members.applyingValueProcessor(ValueProcessor.of(String.class, PiiMaskingJsonMembersCustomizer::mask));
    }

    static String mask(String value) {
        if (value == null) {
            return null;
        }
        return PHONE.matcher(EMAIL.matcher(value).replaceAll("***@***")).replaceAll("***PHONE***");
    }
}
