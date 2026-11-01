package com.salonplatform.staff.service.impl;

import com.salonplatform.identity.inprocess.TeamMemberSeats;
import com.salonplatform.staff.repository.MemberRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Staff's answers to {@code identity} (decision 100). Both join identity's transaction, which holds the salon's
 * identity lock, and read through the same persistence context, so a change made in it is counted.
 */
@Service
class TeamMemberSeatsImpl implements TeamMemberSeats {

    private final MemberRepository members;
    private final TeamAudit audit;

    TeamMemberSeatsImpl(MemberRepository members, TeamAudit audit) {
        this.members = members;
        this.audit = audit;
    }

    @Override
    public long activeMembersWithoutAccount() {
        return members.countActiveWithoutAccount();
    }

    @Override
    public void accountRemoved(UUID accountId, UUID removedBy, Instant at) {
        members.findLinkedTo(accountId).ifPresent(member -> {
            member.clearLink(at);
            members.flush();
            Map<String, Object> after = new HashMap<>();
            after.put("signInAccountId", null);
            audit.linkCleared(member.getId(), Map.of("signInAccountId", accountId.toString()), after, removedBy, at);
        });
    }
}
