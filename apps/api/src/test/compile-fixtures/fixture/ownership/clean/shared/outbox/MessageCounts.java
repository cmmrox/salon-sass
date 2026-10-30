package fixture.ownership.clean.shared.outbox;

import org.springframework.beans.factory.annotation.Value;

public class MessageCounts {

    static final String COUNTS = "SELECT status, count(*) FROM outbox_message WHERE tenant_id = ? GROUP BY status";

    @Value("${salon.outbox.batch-size}")
    private int batchSize;
}
