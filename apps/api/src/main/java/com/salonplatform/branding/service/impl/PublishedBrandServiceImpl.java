package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.repository.SalonBrandRepository;
import com.salonplatform.branding.service.PublishedBrand;
import com.salonplatform.branding.service.PublishedBrandService;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The salon is resolved before the transaction starts, so that the transaction starts with it set. */
@Service
class PublishedBrandServiceImpl implements PublishedBrandService {

    private final SalonBrandRepository brands;

    private final TransactionTemplate readOnly;

    PublishedBrandServiceImpl(SalonBrandRepository brands, PlatformTransactionManager transactions) {
        this.brands = brands;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
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
