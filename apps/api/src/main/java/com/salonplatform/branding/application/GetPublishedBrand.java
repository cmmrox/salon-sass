package com.salonplatform.branding.application;

import com.salonplatform.branding.infrastructure.SalonBrandRepository;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The published brand of the salon the request is for. The salon comes from the request's context,
 * never from a parameter, and is resolved before the transaction starts so that the transaction
 * starts with it set.
 */
@Service
public class GetPublishedBrand {

    private final SalonBrandRepository brands;

    private final TransactionTemplate readOnly;

    GetPublishedBrand(SalonBrandRepository brands, PlatformTransactionManager transactions) {
        this.brands = brands;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @throws TenantNotFoundException when the hostname names no salon, or the salon has no
     *         published brand
     * @throws com.salonplatform.shared.tenancy.TenantSuspendedException when the hostname's salon
     *         is suspended; its brand is not read
     */
    public PublishedBrand forCurrentSalon() {
        CurrentTenant salon = TenantContext.require();
        return readOnly.execute(status -> brands.findCurrent()
                .map(brand -> new PublishedBrand(
                        salon.slug(),
                        brand.getDisplayName(),
                        brand.getBrandColour(),
                        salon.timeZone().getId()))
                .orElseThrow(TenantNotFoundException::new));
    }
}
