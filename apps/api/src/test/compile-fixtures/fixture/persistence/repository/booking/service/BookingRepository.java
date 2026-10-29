package fixture.persistence.repository.booking.service;

import fixture.persistence.repository.booking.entity.Booking;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository declared beside the use cases instead of in infrastructure. */
public interface BookingRepository extends JpaRepository<Booking, UUID> {}
