package fixture.persistence.entitymanager.booking.service;

import jakarta.persistence.EntityManager;

/** A use case reaching past the repositories to the EntityManager. */
public class BookBooking {

    private final EntityManager entityManager;

    public BookBooking(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void flush() {
        entityManager.flush();
    }
}
