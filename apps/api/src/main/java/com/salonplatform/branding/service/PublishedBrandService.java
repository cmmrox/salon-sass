package com.salonplatform.branding.service;

/**
 * The published brand of the salon the request is for. The salon comes from the request's context, never from a
 * parameter.
 */
public interface PublishedBrandService {

    /**
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the hostname names no salon, or the
     *     salon has no published brand
     * @throws com.salonplatform.shared.tenancy.TenantSuspendedException when the hostname's salon is suspended;
     *     its brand is not read
     */
    PublishedBrand forCurrentSalon();
}
