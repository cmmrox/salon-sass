package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.TaxSettingsVersion;
import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One version of a branch's tax settings (F05-S03, decision 095): written once by a save and never changed, so a
 * booking or a sale that keeps its id keeps its tax. It holds the VAT name and rate and the service charge's name
 * copied from configuration when it was saved. Hibernate never updates it ({@link Immutable}), the application role
 * has no update or delete grant, and the database refuses an update by any role. Its number is the branch's previous
 * version's plus one; the unique number keeps one of two saves made from the same version.
 */
@Entity
@Immutable
@Table(name = "catalogue_tax_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaxVersion {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "version", nullable = false, updatable = false)
    private long version;

    @Column(name = "vat_name", nullable = false, updatable = false)
    private String vatName;

    @Column(name = "vat_rate_percent", nullable = false, updatable = false)
    private int vatRatePercent;

    @Column(name = "vat_registered", nullable = false, updatable = false)
    private boolean vatRegistered;

    @Column(name = "vat_registration_number", updatable = false)
    private String vatRegistrationNumber;

    @Column(name = "service_charge_name", nullable = false, updatable = false)
    private String serviceChargeName;

    @Column(name = "service_charge_on", nullable = false, updatable = false)
    private boolean serviceChargeOn;

    @Column(name = "service_charge_percent", updatable = false)
    private Integer serviceChargePercent;

    @Column(name = "service_charge_on_products", nullable = false, updatable = false)
    private boolean serviceChargeOnProducts;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_fields", nullable = false, updatable = false)
    private String[] changedFields;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /**
     * A new version of the branch's tax settings, which Hibernate stamps with the current salon.
     *
     * @param version the branch's previous version plus one (1 for its first)
     * @param vat the VAT as saved, with the configured name and rate
     * @param serviceCharge the service charge as saved, with the configured name; its percent 0 while off
     * @param changedFields the settings it changes from the previous version
     */
    public static TaxVersion saved(
            UUID branchId,
            long version,
            Vat vat,
            ServiceCharge serviceCharge,
            List<String> changedFields,
            UUID createdBy,
            Instant at) {
        TaxVersion row = new TaxVersion();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.version = version;
        row.vatName = vat.name();
        row.vatRatePercent = vat.ratePercent();
        row.vatRegistered = vat.registered();
        row.vatRegistrationNumber = vat.registered() ? vat.registrationNumber() : null;
        row.serviceChargeName = serviceCharge.name();
        row.serviceChargeOn = serviceCharge.on();
        row.serviceChargePercent = serviceCharge.on() ? serviceCharge.percent() : null;
        row.serviceChargeOnProducts = serviceCharge.on() && serviceCharge.onProducts();
        row.changedFields = changedFields.toArray(String[]::new);
        row.createdBy = createdBy;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }

    public Vat vat() {
        return new Vat(vatName, vatRatePercent, vatRegistered, vatRegistrationNumber);
    }

    public ServiceCharge serviceCharge() {
        return new ServiceCharge(
                serviceChargeName,
                serviceChargeOn,
                serviceChargePercent == null ? 0 : serviceChargePercent,
                serviceChargeOnProducts);
    }

    /** This version as other modules read it. */
    public TaxSettingsVersion settings() {
        return new TaxSettingsVersion(id, version, branchId, createdAt, vat(), serviceCharge());
    }

    /** The settings this version changed from the one before it. */
    public List<String> changedFieldNames() {
        return List.of(changedFields);
    }
}
