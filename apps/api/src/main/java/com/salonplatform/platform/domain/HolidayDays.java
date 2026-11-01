package com.salonplatform.platform.domain;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules of a holiday calendar's days (docs/architecture/operator-console.md, "Holiday calendar"): a date
 * inside the year, once per year; a name of 1 to 120 characters; one of the gazette's four kinds. And the CSV
 * file an Admin imports a year's draft from ({@code date,name,kind}), read all or nothing: any bad line
 * refuses the whole file, naming each bad line's number and reason. Pure: no Spring, no database.
 */
public final class HolidayDays {

    /** The four kinds of day the gazette publishes. */
    public static final List<String> KINDS = List.of("poya", "public", "bank", "mercantile");

    public static final int NAME_MAX = 120;

    /** The largest file accepted: a year has a few dozen days, so this is generous. */
    public static final int FILE_MAX_BYTES = 64 * 1024;

    /** The most data lines accepted in one file. */
    public static final int LINES_MAX = 400;

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private HolidayDays() {}

    /** One day of a calendar, its name trimmed and its kind in lower case. */
    public record Day(LocalDate date, String name, String kind) {}

    /** A bad line of a file: its number, counted from 1, and why it was refused. */
    public record BadLine(int line, String reason) {}

    /** What reading a file gave. */
    public sealed interface Reading permits Read, Refused {}

    /** Every line was good: the days, in the file's order. */
    public record Read(List<Day> days) implements Reading {
        public Read {
            days = List.copyOf(days);
        }
    }

    /** At least one line was bad; nothing of the file is used. */
    public record Refused(List<BadLine> lines) implements Reading {
        public Refused {
            lines = List.copyOf(lines);
        }
    }

    /** The name trimmed, or null when it is empty or longer than {@link #NAME_MAX}. */
    public static String name(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        return trimmed.isEmpty() || trimmed.length() > NAME_MAX || hasControl(trimmed) ? null : trimmed;
    }

    /** The kind in lower case, or null when it is not one of {@link #KINDS}. */
    public static String kind(String kind) {
        if (kind == null) {
            return null;
        }
        String lower = kind.strip().toLowerCase(Locale.ROOT);
        return KINDS.contains(lower) ? lower : null;
    }

    /** A date written {@code YYYY-MM-DD}, or null when it is not one. */
    public static LocalDate date(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip(), ISO_DATE);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * Reads a CSV file of the year's days: UTF-8, one day per line as {@code date,name,kind}, an optional first
     * line {@code date,name,kind} naming the columns, fields quoted with {@code "} when they hold a comma, and
     * blank lines ignored.
     */
    public static Reading read(byte[] file, int year) {
        if (file.length > FILE_MAX_BYTES) {
            return new Refused(List.of(new BadLine(1, "the file is larger than 64 KB")));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(file))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new Refused(List.of(new BadLine(1, "the file is not UTF-8 text")));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r\n|\n|\r", -1);
        List<Day> days = new ArrayList<>();
        List<BadLine> bad = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        int dataLines = 0;
        for (int index = 0; index < lines.length; index++) {
            int number = index + 1;
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = fields(line);
            if (dataLines == 0 && fields != null && isHeader(fields)) {
                continue;
            }
            dataLines++;
            if (dataLines > LINES_MAX) {
                bad.add(new BadLine(number, "the file has more than " + LINES_MAX + " days"));
                break;
            }
            if (fields == null || fields.size() != 3) {
                bad.add(new BadLine(number, "is not three fields: date,name,kind"));
                continue;
            }
            LocalDate date = date(fields.get(0));
            String name = name(fields.get(1));
            String kind = kind(fields.get(2));
            if (date == null) {
                bad.add(new BadLine(number, "the date is not a date written YYYY-MM-DD"));
            } else if (date.getYear() != year) {
                bad.add(new BadLine(number, "the date is outside " + year));
            } else if (!seen.add(date)) {
                bad.add(new BadLine(number, "the date " + date + " is repeated"));
            }
            if (name == null) {
                bad.add(new BadLine(number, "the name is empty or longer than " + NAME_MAX + " characters"));
            }
            if (kind == null) {
                bad.add(new BadLine(number, "the kind is not one of poya, public, bank or mercantile"));
            }
            if (date != null && date.getYear() == year && name != null && kind != null) {
                days.add(new Day(date, name, kind));
            }
        }
        if (bad.isEmpty() && days.isEmpty()) {
            return new Refused(List.of(new BadLine(1, "the file has no days")));
        }
        return bad.isEmpty() ? new Read(days) : new Refused(bad);
    }

    private static boolean isHeader(List<String> fields) {
        return fields.size() == 3
                && fields.get(0).strip().equalsIgnoreCase("date")
                && fields.get(1).strip().equalsIgnoreCase("name")
                && fields.get(2).strip().equalsIgnoreCase("kind");
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    /**
     * The fields of one line, RFC 4180 style: a field wrapped in double quotes may hold commas, and a doubled
     * quote inside it is one quote. Null when the line is malformed (an unclosed quote, or text after a
     * closing quote).
     */
    static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int i = 0;
        boolean quoted = false;
        boolean afterQuote = false;
        boolean fieldStart = true;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    afterQuote = true;
                } else {
                    field.append(c);
                }
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                fieldStart = true;
                i++;
                continue;
            } else if (afterQuote) {
                if (!Character.isWhitespace(c)) {
                    return null;
                }
            } else if (c == '"' && fieldStart && field.toString().isBlank()) {
                field.setLength(0);
                quoted = true;
            } else if (c == '"') {
                return null;
            } else {
                field.append(c);
            }
            fieldStart = false;
            i++;
        }
        if (quoted) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }
}
