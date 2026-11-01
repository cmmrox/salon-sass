package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.repository.SalonBrandRepository;
import com.salonplatform.branding.service.BrandView;
import com.salonplatform.branding.service.ProfileView;
import com.salonplatform.branding.service.PublishedBrand;
import com.salonplatform.branding.service.PublishedBrandService;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The salon is resolved before the transaction starts, so that the transaction starts with it set. The
 * brand and the published profile are read in the same transaction, so a reader never sees half of a
 * publication; the draft is never read here.
 */
@Service
class PublishedBrandServiceImpl implements PublishedBrandService {

    private final SalonBrandRepository brands;

    private final ProfileRecords profiles;

    private final BrandRecords brandRecords;

    private final TransactionTemplate readOnly;

    PublishedBrandServiceImpl(
            SalonBrandRepository brands,
            ProfileRecords profiles,
            BrandRecords brandRecords,
            PlatformTransactionManager transactions) {
        this.brands = brands;
        this.profiles = profiles;
        this.brandRecords = brandRecords;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public PublishedBrand forCurrentSalon() {
        CurrentTenant salon = TenantContext.require();
        return readOnly.execute(status -> brands.findCurrent()
                .map(brand -> {
                    BrandView view = brandRecords.view(brand.content());
                    return new PublishedBrand(
                            salon.slug(),
                            brand.getDisplayName(),
                            brand.getBrandColour(),
                            salon.timeZone().getId(),
                            view.tagline(),
                            view.logo(),
                            view.cover(),
                            profiles.publishedContent()
                                    .map(content -> new ProfileView(content, profiles.defaultBranchId())));
                })
                .orElseThrow(TenantNotFoundException::new));
    }
}
