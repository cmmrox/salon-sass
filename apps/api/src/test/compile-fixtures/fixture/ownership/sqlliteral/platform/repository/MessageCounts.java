package fixture.ownership.sqlliteral.platform.repository;

/** Reads shared's outbox table from platform. */
public class MessageCounts {

    static final String COUNTS = "SELECT status, count(*) FROM outbox_message WHERE tenant_id = ? GROUP BY status";
}
