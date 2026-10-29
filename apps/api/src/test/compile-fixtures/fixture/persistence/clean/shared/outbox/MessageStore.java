package fixture.persistence.clean.shared.outbox;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.simple.JdbcClient;

/** shared keeps its own persistence, wherever in shared it lives. */
public class MessageStore {

    private final EntityManager entityManager;

    private final JdbcClient jdbc;

    public MessageStore(EntityManager entityManager, JdbcClient jdbc) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    public boolean open() {
        return entityManager.isOpen() && jdbc != null;
    }
}
