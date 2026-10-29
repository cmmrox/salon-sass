package fixture.persistence.repository.booking.application;

import fixture.persistence.repository.booking.domain.Booking;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository declared beside the use cases instead of in infrastructure. */
public interface BookingRepository extends JpaRepository<Booking, UUID> {}
