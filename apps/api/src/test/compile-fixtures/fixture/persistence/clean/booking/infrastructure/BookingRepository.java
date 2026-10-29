package fixture.persistence.clean.booking.infrastructure;

import fixture.persistence.clean.booking.domain.Booking;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {}
