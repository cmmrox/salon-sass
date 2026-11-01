package com.salonplatform.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.platform.domain.HolidayDays.BadLine;
import com.salonplatform.platform.domain.HolidayDays.Day;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** F04-S03 AC-3: the calendar CSV is read all or nothing, naming each bad line's number and reason. */
class HolidayDaysTest {

    private static HolidayDays.Reading read(String text) {
        return HolidayDays.read(text.getBytes(StandardCharsets.UTF_8), 2027);
    }

    @Test
    void aGoodFileGivesItsDaysWithAnOptionalHeaderQuotedNamesAndBlankLines() {
        HolidayDays.Reading reading = read("""
                date,name,kind
                2027-01-14,Tamil Thai Pongal Day,public

                2027-01-22,"Duruthu Full Moon Poya Day, Sri Lanka",POYA
                2027-04-13,"Day prior to Sinhala and Tamil New Year Day",mercantile
                """);

        assertThat(reading).isInstanceOf(HolidayDays.Read.class);
        assertThat(((HolidayDays.Read) reading).days())
                .containsExactly(
                        new Day(LocalDate.of(2027, 1, 14), "Tamil Thai Pongal Day", "public"),
                        new Day(LocalDate.of(2027, 1, 22), "Duruthu Full Moon Poya Day, Sri Lanka", "poya"),
                        new Day(
                                LocalDate.of(2027, 4, 13),
                                "Day prior to Sinhala and Tamil New Year Day",
                                "mercantile"));
    }

    @Test
    void everyBadLineIsNamedByItsNumberAndNothingIsKept() {
        HolidayDays.Reading reading = read("""
                date,name,kind
                2026-12-25,Christmas Day,public
                2027-01-14,Tamil Thai Pongal Day,public
                2027-01-14,Pongal again,public
                2027-02-04,Independence Day,national
                2027-02-11,,poya
                2027-03-01,Only two fields
                2027-13-01,Not a date,bank
                2027-05-01,"Unclosed quote,public
                """);

        assertThat(reading).isInstanceOf(HolidayDays.Refused.class);
        assertThat(((HolidayDays.Refused) reading).lines())
                .containsExactly(
                        new BadLine(2, "the date is outside 2027"),
                        new BadLine(4, "the date 2027-01-14 is repeated"),
                        new BadLine(5, "the kind is not one of poya, public, bank or mercantile"),
                        new BadLine(6, "the name is empty or longer than 120 characters"),
                        new BadLine(7, "is not three fields: date,name,kind"),
                        new BadLine(8, "the date is not a date written YYYY-MM-DD"),
                        new BadLine(9, "is not three fields: date,name,kind"));
    }

    @Test
    void aFileWithNoDaysTooLargeOrNotUtf8IsRefused() {
        assertThat(read("date,name,kind\n\n"))
                .isEqualTo(new HolidayDays.Refused(java.util.List.of(new BadLine(1, "the file has no days"))));
        assertThat(HolidayDays.read(new byte[HolidayDays.FILE_MAX_BYTES + 1], 2027))
                .isEqualTo(new HolidayDays.Refused(java.util.List.of(new BadLine(1, "the file is larger than 64 KB"))));
        assertThat(HolidayDays.read(new byte[] {(byte) 0xC3, (byte) 0x28}, 2027))
                .isEqualTo(new HolidayDays.Refused(java.util.List.of(new BadLine(1, "the file is not UTF-8 text"))));
    }

    @Test
    void aNameIsTrimmedAndAKindLowerCased() {
        assertThat(HolidayDays.name("  Vesak Full Moon Poya Day ")).isEqualTo("Vesak Full Moon Poya Day");
        assertThat(HolidayDays.name("x".repeat(121))).isNull();
        assertThat(HolidayDays.kind(" Bank ")).isEqualTo("bank");
        assertThat(HolidayDays.date("2027-02-30")).isNull();
    }
}
