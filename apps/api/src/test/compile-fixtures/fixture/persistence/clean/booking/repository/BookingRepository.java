package fixture.persistence.clean.booking.repository;

import fixture.persistence.clean.booking.entity.Booking;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {}
