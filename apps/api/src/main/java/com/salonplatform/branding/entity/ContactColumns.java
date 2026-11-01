package com.salonplatform.branding.entity;

import com.salonplatform.branding.domain.BranchContact;
import com.salonplatform.branding.domain.Coordinates;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A branch's contact details as stored, in its draft and in its published copy alike. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactColumns {

    @Column(name = "address")
    private String address;

    @Column(name = "latitude", precision = 8, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "phone")
    private String phone;

    @Column(name = "whatsapp")
    private String whatsapp;

    static ContactColumns of(BranchContact contact) {
        ContactColumns columns = new ContactColumns();
        columns.address = contact.address();
        columns.latitude =
                contact.location() == null ? null : contact.location().latitude();
        columns.longitude =
                contact.location() == null ? null : contact.location().longitude();
        columns.phone = contact.phone();
        columns.whatsapp = contact.whatsapp();
        return columns;
    }

    BranchContact toContact(UUID branchId) {
        Coordinates location = latitude == null ? null : new Coordinates(latitude, longitude);
        return new BranchContact(branchId, address, location, phone, whatsapp);
    }
}
