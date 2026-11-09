package com.salonplatform.booking.repository;

import com.salonplatform.booking.entity.BookingStateChange;
import com.salonplatform.shared.persistence.NewEntities;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's booking history. Entries are only inserted, through {@link NewEntities#persist}. */
public interface BookingStateChangeRepository
        extends JpaRepository<BookingStateChange, UUID>, NewEntities<BookingStateChange> {

    /** A booking's changes, oldest first. */
    @Query("select c from BookingStateChange c where c.bookingId = :booking order by c.position")
    List<BookingStateChange> of(@Param("booking") UUID bookingId);

    /** How many changes the booking has; the next one's position is one more. */
    @Query("select count(c) from BookingStateChange c where c.bookingId = :booking")
    long countOf(@Param("booking") UUID bookingId);
}
