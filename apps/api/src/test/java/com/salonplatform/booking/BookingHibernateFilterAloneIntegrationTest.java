package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.domain.BookingChannel;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.entity.BookingStateChange;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.repository.BookingStateChangeRepository;
import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for F07-S01's tables (AC-14): Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security disabled on them and every write granted in this database, so that Hibernate and nothing else stands between
 * one salon and another. Both salons hold a booking with the same reference and the same professional's id.
 */
class BookingHibernateFilterAloneIntegrationTest {

    private static final Instant NOW = Instant.parse("2086-06-15T02:30:00Z");
    private static final Instant START = Instant.parse("2086-06-16T04:30:00Z");
    private static final UUID MEMBER = UUID.randomUUID();

    private static TestDatabase database;
    private static StartedApplication application;
    private static TestSalons salons;
    private static TestSalon alpha;
    private static TestSalon beta;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;
    private static Map<UUID, UUID> bookingOf;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() throws Exception {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : BookingRowLevelSecurityAloneIntegrationTest.TABLES) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + TestPostgres.APP_USER);
        }
        salons = new TestSalons(owner);
        alpha = salons.create("Filter Alpha", "#111111");
        beta = salons.create("Filter Beta", "#222222");
        entityManager =
                SharedEntityManagerCreator.createSharedEntityManager(application.bean(EntityManagerFactory.class));
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        bookingOf = new java.util.HashMap<>();
        for (TestSalon salon : List.of(alpha, beta)) {
            UUID branch = salons.defaultBranch(salon);
            bookingOf.put(
                    salon.id(),
                    as(
                            salon,
                            () -> inTransaction(
                                    () -> place(branch, "K7M4QX", MEMBER).getId())));
        }
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    private static Booking place(UUID branch, String reference, UUID member) {
        Booking booking = Booking.hold(
                branch,
                reference,
                new Booking.Terms(
                        member,
                        "Nimali Perera",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Cut and finish",
                        null,
                        null,
                        UUID.randomUUID(),
                        List.of(new ServiceSegment(SegmentKind.ACTIVE, 45), new ServiceSegment(SegmentKind.BUFFER, 15)),
                        350_000,
                        "LKR"),
                new Booking.Request(
                        "Dilani Fernando",
                        "+94771234567",
                        BookingChannel.PHONE,
                        UUID.randomUUID(),
                        "f",
                        UUID.randomUUID()),
                START,
                NOW,
                java.time.Duration.ofMinutes(10),
                false);
        bookings().saveAndFlush(booking);
        application
                .bean(BookingStateChangeRepository.class)
                .persist(BookingStateChange.of(booking, 1, null, BookingState.HELD, null, null, null, NOW));
        return booking;
    }

    private static BookingRepository bookings() {
        return application.bean(BookingRepository.class);
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHereAndEachSalonWasStamped() {
        for (String table : BookingRowLevelSecurityAloneIntegrationTest.TABLES) {
            assertThat(application.jdbc().queryForList("SELECT tenant_id FROM " + table, UUID.class))
                    .containsOnly(alpha.id(), beta.id())
                    .contains(alpha.id(), beta.id());
        }
    }

    @Test
    void alphaReadsOnlyItsOwnBookingsAndHistoryAndBetasAreNotFound() throws Exception {
        UUID betas = bookingOf.get(beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(bookings().findAll())
                            .extracting(Booking::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bookings().findBooking(betas)).isEmpty();
                    assertThat(bookings().lockBooking(betas)).isEmpty();
                    assertThat(bookings().referenceTaken("K7M4QX")).isTrue();
                    assertThat(bookings()
                                    .page(
                                            START.minusSeconds(86_400),
                                            START.plusSeconds(86_400),
                                            NOW,
                                            List.of("held"),
                                            true,
                                            new UUID(0, 0),
                                            false,
                                            "%",
                                            null,
                                            null,
                                            PageRequest.of(0, 50))
                                    .getContent())
                            .extracting(Booking::getId)
                            .contains(bookingOf.get(alpha.id()))
                            .doesNotContain(betas);
                    assertThat(bookings().countUpcomingForMember(MEMBER, NOW)).isEqualTo(1);
                    assertThat(application
                                    .bean(BookingStateChangeRepository.class)
                                    .of(betas))
                            .isEmpty();
                    return null;
                }));
    }

    @Test
    void alphaCannotChangeOrRemoveBetasBookings() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery("update Booking b set b.state = 'cancelled' where b.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("delete from BookingStateChange c where c.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    return null;
                }));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT state FROM booking_appointment WHERE tenant_id = ?", String.class, beta.id()))
                .isEqualTo("held");
    }

    @Test
    void aBookingPlacedWhileAlphaIsSetIsAlphasAndBetaNeverSeesIt() throws Exception {
        UUID placed = as(
                alpha,
                () -> inTransaction(() -> place(salons.defaultBranch(alpha), "P2Q3RS", UUID.randomUUID())
                        .getId()));
        assertThat(database.asOwner()
                        .queryForObject("SELECT tenant_id FROM booking_appointment WHERE id = ?", UUID.class, placed))
                .isEqualTo(alpha.id());
        as(
                beta,
                () -> inTransaction(() -> {
                    assertThat(bookings().findBooking(placed)).isEmpty();
                    return null;
                }));
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private static <T> T inTransaction(Callable<T> work) {
        return transaction.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }
}
