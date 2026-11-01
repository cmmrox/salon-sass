package com.salonplatform.branding.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * A profile as a settings user sent it, before any rule is applied: text untrimmed, phone numbers as
 * typed, branch ids as given.
 *
 * @param description the description
 * @param instagram the Instagram link
 * @param facebook the Facebook link
 * @param website the website
 * @param branches the branches whose contact details are set; a branch not listed keeps its draft
 */
public record ProfileInput(
        String description, String instagram, String facebook, String website, List<Branch> branches) {

    public ProfileInput {
        branches = List.copyOf(branches);
    }

    /**
     * One branch's contact details as sent.
     *
     * @param id the branch's id, as text
     * @param address the address
     * @param location the map position, or null for none
     * @param phone the phone number as typed
     * @param whatsapp the WhatsApp number as typed
     */
    public record Branch(String id, String address, Location location, String phone, String whatsapp) {}

    /** A map position as sent; either coordinate may be missing, which is a field error. */
    public record Location(BigDecimal latitude, BigDecimal longitude) {}
}
