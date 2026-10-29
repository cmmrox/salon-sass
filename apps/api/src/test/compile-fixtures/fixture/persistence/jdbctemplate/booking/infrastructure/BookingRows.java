package fixture.persistence.jdbctemplate.booking.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/** Direct SQL in the right place, through the wrong client. */
public class BookingRows {

    private final JdbcTemplate jdbc;

    public BookingRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count() {
        return jdbc.queryForObject("SELECT count(*) FROM booking", Integer.class);
    }
}
