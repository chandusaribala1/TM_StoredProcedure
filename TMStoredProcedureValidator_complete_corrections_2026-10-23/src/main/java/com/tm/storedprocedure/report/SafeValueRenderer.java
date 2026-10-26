package com.tm.storedprocedure.report;

import com.tm.storedprocedure.domain.GeneratedInput;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SafeValueRenderer {
    private static final Pattern SENSITIVE_NAME = Pattern.compile("(?i).*(password|passwd|secret|token|credential|api.?key|ssn|tax.?id|email|phone|address|birth|name|account|card).*" );
    private SafeValueRenderer() { }

    public static GeneratedInputReport render(String name, GeneratedInput input) {
        boolean nullValue = input.value() == null;
        String value;
        if (nullValue) value = "null";
        else if (input.reusedReference() || SENSITIVE_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches()) value = "[REDACTED]";
        else if (input.value() instanceof byte[]) value = "[BINARY]";
        else value = String.valueOf(input.value());
        return new GeneratedInputReport(name, input.sqlType(), input.source(), value, nullValue);
    }
}
