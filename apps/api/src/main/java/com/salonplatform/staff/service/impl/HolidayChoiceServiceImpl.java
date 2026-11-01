package com.salonplatform.staff.service.impl;

import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.platform.PublishedHolidayYear;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.domain.HolidayChoiceKind;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.entity.HolidayChoice;
import com.salonplatform.staff.entity.HolidayReview;
import com.salonplatform.staff.repository.HolidayChoiceRepository;
import com.salonplatform.staff.repository.HolidayReviewRepository;
import com.salonplatform.staff.service.HolidayChoiceService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The published year is read first, outside any transaction; every change then takes the year's review row
 * lock and checks the version it was sent. A branch's first acceptance inserts the review, and its unique key
 * turns a second simultaneous first acceptance into a {@code conflict}. A choice records the revision of the
 * day it was made against, so a later revision that changes the day puts it back to review.
 */
@Service
class HolidayChoiceServiceImpl implements HolidayChoiceService {

    private static final String SUBJECT = "salon_holidays";

    private final StaffScope scope;
    private final HolidayReviewRepository reviews;
    private final HolidayChoiceRepository choices;
    private final StaffAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    HolidayChoiceServiceImpl(
            StaffScope scope,
            HolidayReviewRepository reviews,
            HolidayChoiceRepository choices,
            StaffAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.reviews = reviews;
        this.choices = choices;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    /** A choice that passed validation. */
    private record Valid(LocalDate date, HolidayChoiceKind kind, LocalTime opens, LocalTime closes, int revision) {}

    @Override
    public List<HolidayYear> years() {
        Scope at = scope.of(StaffScope.READ);
        List<PublishedHolidayYear> published = scope.yearsFrom(at.today().getYear());
        if (published.isEmpty()) {
            return List.of();
        }
        return reading.execute(status -> {
            Map<Integer, HolidayReview> byYear =
                    reviews
                            .findForYears(
                                    at.branch(),
                                    published.stream()
                                            .map(PublishedHolidayYear::year)
                                            .toList())
                            .stream()
                            .collect(Collectors.toMap(HolidayReview::getYear, Function.identity()));
            Map<LocalDate, HolidayChoice> chosen = chosen(
                    at,
                    LocalDate.of(published.getFirst().year(), 1, 1),
                    LocalDate.of(published.getLast().year(), 12, 31));
            return published.stream()
                    .map(year -> view(year, byYear.get(year.year()), chosen, at.today()))
                    .toList();
        });
    }

    @Override
    public HolidayYear accept(int year, Long version, List<ChoiceInput> inputs) {
        Scope at = scope.of(StaffScope.MANAGE);
        PublishedHolidayYear published = published(year);
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        List<Valid> valid = validAll(published, inputs, at.today());
        return writing.execute(status -> {
            Instant now = clock.instant();
            HolidayReview review = lockAt(at, year, version, now);
            Map<LocalDate, HolidayChoice> chosen = chosen(at, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            Set<LocalDate> given = valid.stream().map(Valid::date).collect(Collectors.toSet());
            Set<LocalDate> missing = new TreeSet<>();
            for (PublishedHoliday day : published.days()) {
                boolean past = day.date().isBefore(at.today());
                if (!past && toReview(day, chosen.get(day.date())) && !given.contains(day.date())) {
                    missing.add(day.date());
                }
            }
            if (!missing.isEmpty()) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem(
                                "choices",
                                "must give a choice for every day to review: "
                                        + missing.stream()
                                                .map(LocalDate::toString)
                                                .collect(Collectors.joining(", ")))));
            }
            List<String> dates = new ArrayList<>();
            for (Valid choice : valid) {
                write(at, review, chosen.get(choice.date()), choice, now);
                dates.add(choice.date().toString());
            }
            choices.flush();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("year", year);
            details.put("dates", dates);
            details.put("version", review.getVersion());
            audit.record(at.salon(), at.actor(), "staff.holidays_accepted", SUBJECT, details, now);
            return view(
                    published, review, chosen(at, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)), at.today());
        });
    }

    @Override
    public HolidayYear choose(int year, String date, ChoiceInput input) {
        Scope at = scope.of(StaffScope.MANAGE);
        PublishedHolidayYear published = published(year);
        LocalDate on = date == null ? null : parse(date);
        PublishedHoliday day = published.days().stream()
                .filter(each -> each.date().equals(on))
                .findFirst()
                .orElseThrow(NotFoundException::new);
        if (day.date().isBefore(at.today())) {
            throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
        }
        List<FieldProblem> errors = new ArrayList<>();
        Valid choice = valid(day, input, "", errors);
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return writing.execute(status -> {
            Instant now = clock.instant();
            HolidayReview review = reviews.lock(at.branch(), year)
                    .orElseThrow(() -> new ApplicationProblemException(409, ErrorCode.INVALID_STATE));
            Map<LocalDate, HolidayChoice> chosen = chosen(at, day.date(), day.date());
            write(at, review, chosen.get(day.date()), choice, now);
            review.dayChanged(now);
            choices.flush();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("year", year);
            details.put("date", day.date().toString());
            details.put("version", review.getVersion());
            audit.record(at.salon(), at.actor(), "staff.holiday_choice_changed", SUBJECT, details, now);
            return view(
                    published, review, chosen(at, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)), at.today());
        });
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private PublishedHolidayYear published(int year) {
        return scope.yearsFrom(year).stream()
                .filter(each -> each.year() == year)
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    private HolidayReview lockAt(Scope at, int year, long version, Instant now) {
        Optional<HolidayReview> existing = reviews.lock(at.branch(), year);
        if (existing.isEmpty()) {
            if (version != 0) {
                throw conflict();
            }
            HolidayReview first =
                    HolidayReview.firstAcceptance(at.branch(), year, at.actor().staffId(), now);
            try {
                reviews.persist(first);
                reviews.flush();
            } catch (DataIntegrityViolationException ex) {
                throw conflict();
            }
            return first;
        }
        HolidayReview review = existing.get();
        if (review.getVersion() != version) {
            throw conflict();
        }
        review.accepted(at.actor().staffId(), now);
        return review;
    }

    private void write(Scope at, HolidayReview review, HolidayChoice existing, Valid choice, Instant now) {
        if (existing == null) {
            choices.persist(HolidayChoice.made(
                    at.branch(),
                    review.getId(),
                    choice.date(),
                    choice.kind(),
                    choice.opens(),
                    choice.closes(),
                    choice.revision(),
                    now));
        } else {
            existing.change(choice.kind(), choice.opens(), choice.closes(), choice.revision(), now);
        }
    }

    private Map<LocalDate, HolidayChoice> chosen(Scope at, LocalDate from, LocalDate to) {
        return choices.findBetween(at.branch(), from, to).stream()
                .collect(Collectors.toMap(HolidayChoice::getHolidayDate, Function.identity()));
    }

    private static ApplicationProblemException conflict() {
        return new ApplicationProblemException(409, ErrorCode.CONFLICT);
    }

    private static boolean toReview(PublishedHoliday day, HolidayChoice choice) {
        return choice == null || !choice.madeAgainst(day.revision());
    }

    private static LocalDate parse(String date) {
        try {
            return date.matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(date) : null;
        } catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    private static List<Valid> validAll(PublishedHolidayYear year, List<ChoiceInput> inputs, LocalDate today) {
        if (inputs == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("choices", "is required")));
        }
        Map<LocalDate, PublishedHoliday> days =
                year.days().stream().collect(Collectors.toMap(PublishedHoliday::date, Function.identity()));
        List<FieldProblem> errors = new ArrayList<>();
        List<Valid> valid = new ArrayList<>();
        Set<LocalDate> seen = new java.util.HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            String at = "choices[" + i + "]";
            ChoiceInput input = inputs.get(i);
            if (input == null) {
                errors.add(new FieldProblem(at, "is required"));
                continue;
            }
            LocalDate date = input.date() == null ? null : parse(input.date());
            PublishedHoliday day = date == null ? null : days.get(date);
            if (day == null) {
                errors.add(new FieldProblem(at + ".date", "must be one of the year's published holidays"));
            } else if (date.isBefore(today)) {
                errors.add(new FieldProblem(at + ".date", "has passed"));
                day = null;
            } else if (!seen.add(date)) {
                errors.add(new FieldProblem(at + ".date", "is given twice"));
                day = null;
            }
            Valid choice = valid(day, input, at + ".", errors);
            if (choice != null) {
                valid.add(choice);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return valid;
    }

    /** The choice, or null with its errors added; the day may be null when the date was already refused. */
    private static Valid valid(PublishedHoliday day, ChoiceInput input, String at, List<FieldProblem> errors) {
        int before = errors.size();
        HolidayChoiceKind kind = HolidayChoiceKind.of(input.choice());
        LocalTime opens = null;
        LocalTime closes = null;
        if (kind == null) {
            errors.add(new FieldProblem(at + "choice", "must be closed, open or special"));
        } else if (kind == HolidayChoiceKind.SPECIAL) {
            opens = LocalTimes.quarterHour(input.opens());
            closes = LocalTimes.quarterHour(input.closes());
            if (opens == null) {
                errors.add(new FieldProblem(at + "opens", "must be a time on a quarter hour, HH:MM"));
            }
            if (closes == null) {
                errors.add(new FieldProblem(at + "closes", "must be a time on a quarter hour, HH:MM"));
            } else if (opens != null && !closes.isAfter(opens)) {
                errors.add(new FieldProblem(at + "closes", "must be after the opening time"));
            }
        } else {
            if (input.opens() != null) {
                errors.add(new FieldProblem(at + "opens", "must be empty unless the choice is special"));
            }
            if (input.closes() != null) {
                errors.add(new FieldProblem(at + "closes", "must be empty unless the choice is special"));
            }
        }
        if (day == null || errors.size() > before) {
            return null;
        }
        return new Valid(day.date(), kind, opens, closes, day.revision());
    }

    private static HolidayYear view(
            PublishedHolidayYear year, HolidayReview review, Map<LocalDate, HolidayChoice> chosen, LocalDate today) {
        List<HolidayDay> days = year.days().stream()
                .map(day -> {
                    HolidayChoice choice = chosen.get(day.date());
                    return new HolidayDay(
                            day.date(),
                            day.name(),
                            day.kind(),
                            choice == null ? null : choice.getChoice(),
                            choice == null ? null : choice.getOpens(),
                            choice == null ? null : choice.getCloses(),
                            toReview(day, choice),
                            day.date().isBefore(today));
                })
                .toList();
        boolean reviewed = review != null && days.stream().noneMatch(day -> !day.past() && day.toReview());
        return new HolidayYear(
                year.year(),
                year.revision(),
                year.publishedAt(),
                reviewed,
                review == null ? 0 : review.getVersion(),
                days);
    }
}
