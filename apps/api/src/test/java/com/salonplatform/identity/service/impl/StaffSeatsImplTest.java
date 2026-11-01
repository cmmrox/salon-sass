package com.salonplatform.identity.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonplatform.identity.inprocess.TeamMemberSeats;
import com.salonplatform.identity.repository.IdentityLock;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.entitlements.SalonEntitlements;
import com.salonplatform.shared.web.ApplicationProblemException;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code identity} stands alone (decision 100): with no {@link TeamMemberSeats} registered, its seats are its active
 * accounts; with one, a member with no linked account takes a seat too.
 */
class StaffSeatsImplTest {

    private static final SalonEntitlements.Limits TWO_SEATS = new SalonEntitlements.Limits(2, 1, 0, false, false);

    private final StaffAccountRepository accounts = mock(StaffAccountRepository.class);
    private final SalonEntitlements entitlements = mock(SalonEntitlements.class);

    @BeforeEach
    void inAWritableTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(entitlements.forCurrentSalon()).thenReturn(Optional.of(TWO_SEATS));
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clear();
    }

    private StaffSeatsImpl seats(StaticListableBeanFactory beans) {
        return new StaffSeatsImpl(
                mock(IdentityLock.class),
                accounts,
                entitlements,
                beans.getBeanProvider(TeamMemberSeats.class),
                Clock.systemUTC());
    }

    @Test
    void withNoStaffModuleItsSeatsAreItsActiveAccountsAlone() {
        when(accounts.countByActiveTrue()).thenReturn(1L);
        StaffSeatsImpl alone = seats(new StaticListableBeanFactory());

        assertThatCode(alone::requireFreeSeat).doesNotThrowAnyException();
    }

    @Test
    void withNoStaffModuleItsLastAccountStillFillsThePlan() {
        when(accounts.countByActiveTrue()).thenReturn(2L);
        StaffSeatsImpl alone = seats(new StaticListableBeanFactory());

        assertThatExceptionOfType(ApplicationProblemException.class).isThrownBy(alone::requireFreeSeat);
    }

    @Test
    void aMemberWithNoLinkedAccountTakesASeatBesideTheAccounts() {
        when(accounts.countByActiveTrue()).thenReturn(1L);
        TeamMemberSeats team = mock(TeamMemberSeats.class);
        when(team.activeMembersWithoutAccount()).thenReturn(1L);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("team", team);

        assertThatExceptionOfType(ApplicationProblemException.class).isThrownBy(seats(beans)::requireFreeSeat);
    }

    @Test
    void aSalonWithoutAPlanIsNotLimited() {
        when(entitlements.forCurrentSalon()).thenReturn(Optional.empty());
        when(accounts.countByActiveTrue()).thenReturn(99L);

        assertThatCode(seats(new StaticListableBeanFactory())::requireFreeSeat).doesNotThrowAnyException();
    }

    @Test
    void refusesToHoldOutsideAWritableTransaction() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatIllegalStateException().isThrownBy(seats(new StaticListableBeanFactory())::hold);
    }
}
