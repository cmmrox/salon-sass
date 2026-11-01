package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.config.BrandingSettings;
import com.salonplatform.branding.domain.BrandImages;
import com.salonplatform.branding.entity.BrandImage;
import com.salonplatform.branding.entity.SalonBrand;
import com.salonplatform.branding.repository.BrandImageRepository;
import com.salonplatform.branding.repository.SalonBrandRepository;
import com.salonplatform.branding.service.BrandImageService;
import com.salonplatform.branding.service.BrandImageView;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The image is checked, decoded and remade before any transaction opens, so a slow decode holds no database
 * connection; only the finished image is written. Whether an image may be served is decided on every
 * request from the published brand (decision 086): a draft-only image needs {@code settings.read}, and an
 * image the caller may not see is not found, exactly as one that does not exist.
 */
@Service
class BrandImageServiceImpl implements BrandImageService {

    private final Principals principals;
    private final BrandImageRepository images;
    private final SalonBrandRepository published;
    private final SettingsAudit audit;
    private final Clock clock;
    private final BrandingSettings.Images limits;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    BrandImageServiceImpl(
            Principals principals,
            BrandImageRepository images,
            SalonBrandRepository published,
            SettingsAudit audit,
            Clock clock,
            BrandingSettings settings,
            PlatformTransactionManager transactions) {
        this.principals = principals;
        this.images = images;
        this.published = published;
        this.audit = audit;
        this.clock = clock;
        this.limits = settings.images();
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public BrandImageView upload(String kind, byte[] content) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(ProfileServiceImpl.MANAGE);
        BrandImages.Kind which = kindOf(kind);
        BrandImages.Accepted accepted =
                switch (BrandImages.check(content, which, limits.maxBytes().toBytes(), limits.maxPixels())) {
                    case BrandImages.Accepted image -> image;
                    case BrandImages.Refused refused ->
                        throw new ApplicationProblemException(
                                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", refused.detail())));
                };
        return writing.execute(status -> {
            Instant now = clock.instant();
            BrandImage image = images.persist(BrandImage.uploaded(
                    which.value(), accepted.mediaType(), accepted.width(), accepted.height(), accepted.bytes(), now));
            audit.record(
                    salon,
                    actor,
                    "branding.brand_image_uploaded",
                    "salon_brand",
                    Map.of("kind", which.value(), "imageId", image.getId().toString()),
                    now);
            return new BrandImageView(image.getId(), image.getKind(), image.getWidth(), image.getHeight());
        });
    }

    @Override
    public Stored read(UUID id) {
        TenantContext.require();
        record Found(Stored stored, boolean isPublished) {}
        Found found = reading.execute(status -> {
            Optional<BrandImage> image = images.findById(id);
            if (image.isEmpty()) return null;
            SalonBrand brand = published.findCurrent().orElse(null);
            boolean isPublished =
                    brand != null && (id.equals(brand.getLogoImageId()) || id.equals(brand.getCoverImageId()));
            return new Found(new Stored(image.get().getBytes(), image.get().getMediaType()), isPublished);
        });
        if (found == null) {
            throw new NotFoundException();
        }
        if (!found.isPublished() && !mayReadSettings()) {
            throw new NotFoundException();
        }
        return found.stored();
    }

    /** Whether the request is made by someone who may read the salon's settings; no session means no. */
    private boolean mayReadSettings() {
        try {
            return principals.current().permissions().contains(ProfileServiceImpl.READ);
        } catch (ApplicationProblemException ex) {
            return false;
        }
    }

    private static BrandImages.Kind kindOf(String kind) {
        for (BrandImages.Kind each : BrandImages.Kind.values()) {
            if (each.value().equals(Objects.requireNonNullElse(kind, "").toLowerCase(Locale.ROOT))) {
                return each;
            }
        }
        throw new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("kind", "must be logo or cover")));
    }
}
