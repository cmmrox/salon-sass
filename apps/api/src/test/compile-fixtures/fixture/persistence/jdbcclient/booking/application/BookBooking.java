package fixture.persistence.jdbcclient.booking.application;

import org.springframework.jdbc.core.simple.JdbcClient;

/** A use case writing its own SQL. */
public class BookBooking {

    private final JdbcClient jdbc;

    public BookBooking(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int confirmAll() {
        return jdbc.sql("UPDATE booking SET status = 'confirmed'").update();
    }
}
