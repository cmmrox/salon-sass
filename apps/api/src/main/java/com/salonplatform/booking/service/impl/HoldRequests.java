package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.config.BookingSettings;
import com.salonplatform.booking.domain.BookingChannel;
import com.salonplatform.booking.domain.CustomerMobile;
import com.salonplatform.booking.domain.CustomerName;
import com.salonplatform.booking.service.HoldService.HoldInput;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.catalogue.ServiceTerms;
import com.salonplatform.catalogue.ServiceTermsVersion;
import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A hold's request as it arrives, checked field by field (AC-2): every failing field is named in one 400, and nothing
 * is stored. The request's fingerprint is what a repeat with the same {@code Idempotency-Key} must match.
 */
@Component
class HoldRequests {

    static final String KEY_HEADER = "Idempotency-Key";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final BookingSettings settings;
    private final ServiceTerms terms;
    private final CatalogueListings listings;

    HoldRequests(BookingSettings settings, ServiceTerms terms, CatalogueListings listings) {
        this.settings = settings;
        this.terms = terms;
        this.listings = listings;
    }

    /** A request whose every field has the right form; {@code memberId} null for "No preference". */
    record Checked(
            UUID key,
            String fingerprint,
            UUID memberId,
            UUID serviceId,
            UUID variantId,
            LocalDate date,
            LocalTime time,
            String customerName,
            String customerMobile,
            BookingChannel channel) {}

    Checked check(HoldInput input, String keyHeader) {
        List<FieldProblem> errors = new ArrayList<>();
        UUID key = id(keyHeader, KEY_HEADER, true, errors);
        // Null, or left out, is "No preference" (F07-S02 AC-5): the server assigns the professional.
        UUID member = id(input.memberId(), "memberId", false, errors);
        UUID service = id(input.serviceId(), "serviceId", true, errors);
        UUID variant = id(input.variantId(), "variantId", false, errors);
        LocalDate date = date(input.date(), errors);
        LocalTime time = time(input.time(), errors);
        Optional<String> name = CustomerName.parse(input.customerName());
        if (name.isEmpty()) {
            errors.add(new FieldProblem("customer.name", "must be 2 to 60 characters"));
        }
        Optional<String> mobile = CustomerMobile.parse(input.customerMobile(), settings.phoneRegion());
        if (mobile.isEmpty()) {
            errors.add(new FieldProblem("customer.mobile", "must be a mobile number of the salon's country"));
        }
        Optional<BookingChannel> channel =
                input.channel() == null ? Optional.empty() : BookingChannel.staff(input.channel());
        if (channel.isEmpty()) {
            errors.add(new FieldProblem(
                    "channel", "must be phone, whatsapp, front_desk, walk_in, instagram, google or staff_web"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Checked(
                key,
                fingerprint(input),
                member,
                service,
                variant,
                date,
                time,
                name.orElseThrow(),
                mobile.orElseThrow(),
                channel.orElseThrow());
    }

    /**
     * The variant the hold is for. A service with variants needs one of its own: none, or one of the salon's other
     * services', is a field error; any other id (another salon's, removed or unknown) is not found. A service with
     * none takes none.
     */
    Optional<ServiceVariant> variantOf(ServiceTermsVersion service, UUID variantId) {
        if (service.variants().isEmpty()) {
            if (variantId != null) {
                throw variantError("this service has no variants");
            }
            return Optional.empty();
        }
        if (variantId == null) {
            throw variantError("this service needs one of its variants");
        }
        Optional<ServiceVariant> own = service.variants().stream()
                .filter(variant -> variant.variantId().equals(variantId))
                .findFirst();
        if (own.isPresent()) {
            return own;
        }
        // Only on this refusal: whether the id is a variant of another of the salon's services (at most 300).
        boolean anotherServices = listings.current().services().stream()
                .filter(listed -> !listed.id().equals(service.serviceId()))
                .map(listed -> terms.current(listed.id()))
                .flatMap(Optional::stream)
                .flatMap(other -> other.variants().stream())
                .anyMatch(variant -> variant.variantId().equals(variantId));
        if (anotherServices) {
            throw variantError("must be one of this service's variants");
        }
        throw new NotFoundException();
    }

    private static ApplicationProblemException variantError(String detail) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("variantId", detail)));
    }

    private static UUID id(String text, String field, boolean required, List<FieldProblem> errors) {
        if (text == null) {
            if (required) {
                errors.add(new FieldProblem(field, "is required"));
            }
            return null;
        }
        if (!UUID_TEXT.matcher(text).matches()) {
            errors.add(new FieldProblem(field, "must be a UUID"));
            return null;
        }
        return UUID.fromString(text);
    }

    private static LocalDate date(String text, List<FieldProblem> errors) {
        if (text != null) {
            try {
                return LocalDate.parse(text, DATE);
            } catch (DateTimeParseException ex) {
                // Named below.
            }
        }
        errors.add(new FieldProblem("date", "must be a date, YYYY-MM-DD"));
        return null;
    }

    private static LocalTime time(String text, List<FieldProblem> errors) {
        if (text != null && CLOCK.matcher(text).matches()) {
            return LocalTime.parse(text);
        }
        errors.add(new FieldProblem("time", "must be a time, HH:MM"));
        return null;
    }

    /** The request's fields as sent, hashed: a repeat with the same key must send exactly these. */
    private static String fingerprint(HoldInput input) {
        String joined = String.join(
                "\u001f",
                part(input.memberId()),
                part(input.serviceId()),
                part(input.variantId()),
                part(input.date()),
                part(input.time()),
                part(input.customerName()),
                part(input.customerMobile()),
                part(input.channel()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has SHA-256", ex);
        }
    }

    private static String part(String value) {
        return value == null ? "\u0000" : value;
    }
}
