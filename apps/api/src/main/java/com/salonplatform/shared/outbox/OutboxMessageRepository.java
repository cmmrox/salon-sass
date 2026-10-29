package com.salonplatform.shared.outbox;

import com.salonplatform.shared.persistence.NewEntities;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The current salon's outgoing messages: Hibernate's tenant filter and row-level security see to that. */
interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID>, NewEntities<OutboxMessage> {

    /** A message's id and what its provider would be given. */
    record Payload(UUID id, String payload) {}

    /** The first page of the channels' messages in id order. */
    List<Payload> findFirst100ByChannelInOrderById(Collection<String> channels);

    /** The next page of the channels' messages in id order, after the last id of the previous page. */
    List<Payload> findFirst100ByChannelInAndIdGreaterThanOrderById(Collection<String> channels, UUID after);
}
