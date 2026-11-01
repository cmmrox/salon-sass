package com.salonplatform.branding.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One branch's public contact details, each null when it is not set.
 *
 * @param branchId the branch
 * @param address its address, trimmed
 * @param location its map position
 * @param phone its phone number, E.164
 * @param whatsapp its WhatsApp number, a mobile number in E.164
 */
public record BranchContact(UUID branchId, String address, Coordinates location, String phone, String whatsapp) {

    /** A branch with nothing set. */
    public static BranchContact empty(UUID branchId) {
        return new BranchContact(branchId, null, null, null, null);
    }

    boolean isEmpty() {
        return address == null && location == null && phone == null && whatsapp == null;
    }

    /** The names of the fields that differ from another copy of the same branch's details. */
    List<String> changedFrom(BranchContact other) {
        String prefix = "branches[" + branchId + "].";
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(address, other.address)) changed.add(prefix + "address");
        if (location == null ? other.location != null : !location.samePlaceAs(other.location))
            changed.add(prefix + "location");
        if (!Objects.equals(phone, other.phone)) changed.add(prefix + "phone");
        if (!Objects.equals(whatsapp, other.whatsapp)) changed.add(prefix + "whatsapp");
        return changed;
    }
}
