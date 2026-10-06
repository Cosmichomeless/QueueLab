package com.queuelab.worker.job;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Acumulador de una columna: ocupa lo mismo vea 10 filas o 10 millones. */
final class ColumnStats {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");

    private final String name;
    private long nonEmpty;
    private long empty;
    private int minLength = Integer.MAX_VALUE;
    private int maxLength;
    private boolean numeric = true;
    private BigDecimal min;
    private BigDecimal max;
    private BigDecimal sum = BigDecimal.ZERO;

    ColumnStats(String name) {
        this.name = name;
    }

    void add(String value) {
        if (value.isEmpty()) {
            empty++;
            return;
        }
        nonEmpty++;
        minLength = Math.min(minLength, value.codePointCount(0, value.length()));
        maxLength = Math.max(maxLength, value.codePointCount(0, value.length()));
        if (numeric) {
            if (NUMBER.matcher(value).matches()) {
                BigDecimal number = new BigDecimal(value);
                min = min == null || number.compareTo(min) < 0 ? number : min;
                max = max == null || number.compareTo(max) > 0 ? number : max;
                sum = sum.add(number);
            } else {
                numeric = false;
            }
        }
    }

    /** Estadísticas en el orden del contrato ({@code docs/csv-workload.md}). */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        boolean number = numeric && nonEmpty > 0;
        out.put("type", number ? "number" : "text");
        out.put("nonEmpty", nonEmpty);
        out.put("empty", empty);
        if (number) {
            out.put("min", min);
            out.put("max", max);
            out.put("sum", sum);
            out.put("mean", sum.divide(BigDecimal.valueOf(nonEmpty), MathContext.DECIMAL64).stripTrailingZeros());
        } else if (nonEmpty > 0) {
            out.put("minLength", minLength);
            out.put("maxLength", maxLength);
        }
        return out;
    }
}
