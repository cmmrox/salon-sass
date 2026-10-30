package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.domain.IdempotencyKey;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.domain.Slugs;
import com.salonplatform.platform.dto.PlanDefinition;
import com.salonplatform.platform.dto.PlanPage;
import com.salonplatform.platform.repository.PlanStore;
import com.salonplatform.platform.repository.PlanStore.NewPlan;
import com.salonplatform.platform.repository.PlanStore.Plan;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.PlanCatalogueService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
class PlanCatalogueServiceImpl implements PlanCatalogueService {

    static final String ACTION = "plan.create";
    static final int LIMIT_MAX = 1_000_000;

    private final PlanStore plans;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final ProvisioningReceipts receipts;
    private final PlatformAudit audit;
    private final Clock clock;

    PlanCatalogueServiceImpl(
            PlanStore plans,
            OperatorWork work,
            OperatorAccess access,
            ProvisioningReceipts receipts,
            PlatformAudit audit,
            Clock clock) {
        this.plans = plans;
        this.work = work;
        this.access = access;
        this.receipts = receipts;
        this.audit = audit;
        this.clock = clock;
    }

    private static PlanDefinition definition(Plan plan) {
        return new PlanDefinition(
                plan.id(),
                plan.key(),
                plan.name(),
                plan.staffSeats(),
                plan.branches(),
                plan.messageAllowance(),
                plan.customDomain(),
                plan.dedicatedApp(),
                plan.createdAt(),
                plan.updatedAt());
    }

    @Override
    public PlanPage list(Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.PLAN_READ);
            List<PlanDefinition> items = plans.page(paging.q(), paging.size(), paging.offset()).stream()
                    .map(PlanCatalogueServiceImpl::definition)
                    .toList();
            long total = plans.count(paging.q());
            return new PlanPage(items, paging.page(), paging.size(), total);
        });
    }

    @Override
    public PlanDefinition get(UUID id) {
        return work.run(() -> {
            access.require(OperatorPermission.PLAN_READ);
            return find(id);
        });
    }

    private PlanDefinition find(UUID id) {
        return plans.findById(id).map(PlanCatalogueServiceImpl::definition).orElseThrow(NotFoundException::new);
    }

    @Override
    public Created create(String idempotencyKey, PlanInput input) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.PLAN_CREATE);
            UUID key = IdempotencyKey.parse(idempotencyKey);
            Map<String, Object> plan = validate(input);
            String fingerprint = receipts.fingerprint(ACTION, plan);
            var earlier = receipts.replay(operator.id(), ACTION, key, fingerprint);
            if (earlier.isPresent()) {
                return new Created(true, find(earlier.get().subjectId()));
            }
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            try {
                plans.add(
                        id,
                        new NewPlan(
                                (String) plan.get("key"),
                                (String) plan.get("name"),
                                (Integer) plan.get("staffSeats"),
                                (Integer) plan.get("branches"),
                                (Integer) plan.get("messageAllowance"),
                                (Boolean) plan.get("customDomain"),
                                (Boolean) plan.get("dedicatedApp")),
                        now);
            } catch (DuplicateKeyException ex) {
                throw new ApplicationProblemException(409, ErrorCode.PLAN_KEY_TAKEN);
            }
            audit.record(
                    PlatformAudit.OPERATOR, operator.id().toString(), "plan.created", "plan", id.toString(), plan, now);
            receipts.store(operator.id(), ACTION, key, fingerprint, id, Map.of("id", id.toString()), now);
            return new Created(false, find(id));
        });
    }

    /** Every field, in a fixed order: the normalized definition, or 400 naming each bad field. */
    static Map<String, Object> validate(PlanInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        String key = Slugs.normalize(input.key());
        if (!Slugs.wellFormed(key)) {
            errors.add(new FieldProblem(
                    "key",
                    "must be 3 to 30 lowercase letters, digits and hyphens, starting and "
                            + "ending with a letter or digit"));
        }
        String name = PlainText.line(input.name(), 120);
        if (name == null) {
            errors.add(new FieldProblem("name", "must be plain text between 1 and 120 characters"));
        }
        limit(input.staffSeats(), 1, "staffSeats", errors);
        limit(input.branches(), 1, "branches", errors);
        limit(input.messageAllowance(), 0, "messageAllowance", errors);
        if (input.customDomain() == null) {
            errors.add(new FieldProblem("customDomain", "is required: true or false"));
        }
        if (input.dedicatedApp() == null) {
            errors.add(new FieldProblem("dedicatedApp", "is required: true or false"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("key", key);
        plan.put("name", name);
        plan.put("staffSeats", input.staffSeats());
        plan.put("branches", input.branches());
        plan.put("messageAllowance", input.messageAllowance());
        plan.put("customDomain", input.customDomain());
        plan.put("dedicatedApp", input.dedicatedApp());
        return plan;
    }

    private static void limit(Integer value, int min, String field, List<FieldProblem> errors) {
        if (value == null || value < min || value > LIMIT_MAX) {
            errors.add(new FieldProblem(field, "is required: a whole number from " + min + " to " + LIMIT_MAX));
        }
    }
}
