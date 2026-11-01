package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.config.CatalogueSettings;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.ServicePhoto;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.repository.ServicePhotoRepository;
import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import com.salonplatform.catalogue.service.ServicePhotos;
import com.salonplatform.catalogue.service.impl.CatalogueScope.Scope;
import com.salonplatform.shared.images.DecodePermits;
import com.salonplatform.shared.images.ImageCheck;
import com.salonplatform.shared.images.ImageFormat;
import com.salonplatform.shared.images.ImageShape;
import com.salonplatform.shared.images.ImageUpload;
import com.salonplatform.shared.images.UploadedImages;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload is checked, decoded and remade before the writing transaction opens, so a slow decode holds no
 * database connection; only the finished photo is written. A refused file changes nothing, so the service keeps its
 * previous photo. A replaced or removed photo is deleted in the same transaction.
 */
@Service
class ServicePhotosImpl implements ServicePhotos {

    /**
     * A service photo (F05-S01 AC-10): at least 320 x 320 as uploaded, cropped to its centre square and kept as a
     * JPEG of at most 800 x 800.
     */
    static final ImageShape PHOTO = new ImageShape("a photo", 320, 320, 800, 800, true, ImageFormat.JPEG);

    private static final String CHANGED = "catalogue.service_photo_changed";
    private static final String REMOVED = "catalogue.service_photo_removed";

    private final CatalogueScope scope;
    private final SalonServiceRepository services;
    private final ServicePhotoRepository photos;
    private final ServiceViews views;
    private final CatalogueAudit audit;
    private final Clock clock;
    private final CatalogueSettings.Images limits;
    private final DecodePermits decoding;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    ServicePhotosImpl(
            CatalogueScope scope,
            SalonServiceRepository services,
            ServicePhotoRepository photos,
            ServiceViews views,
            CatalogueAudit audit,
            Clock clock,
            CatalogueSettings settings,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.services = services;
        this.photos = photos;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.limits = settings.images();
        this.decoding = new DecodePermits(limits.concurrentDecodes(), Duration.ofSeconds(2));
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public ServiceView upload(UUID serviceId, ImageUpload upload) throws IOException {
        Scope at = scope.of(CatalogueScope.CHANGE);
        // An unknown service is not found before anything is read.
        reading.execute(status -> live(serviceId, at));
        long maxBytes = limits.maxBytes().toBytes();
        if (upload.declaresOver(maxBytes)) {
            throw refused(UploadedImages.tooLarge(maxBytes));
        }
        byte[] content = upload.read();
        ImageCheck.Accepted accepted =
                switch (decoding.run(() -> UploadedImages.check(content, PHOTO, maxBytes, limits.maxPixels()))) {
                    case ImageCheck.Accepted image -> image;
                    case ImageCheck.Refused refused -> throw refused(refused);
                };
        return CategoryServiceImpl.changing(() -> writing.execute(status -> {
            SalonService service = live(serviceId, at);
            UUID previous = service.getPhotoId();
            Instant now = clock.instant();
            ServicePhoto photo = photos.persist(ServicePhoto.uploaded(
                    at.branch(), accepted.mediaType(), accepted.width(), accepted.height(), accepted.bytes(), now));
            service.changePhoto(photo.getId(), now);
            services.flush();
            if (previous != null) {
                photos.remove(previous);
            }
            audit.record(
                    at.actor(),
                    CHANGED,
                    CatalogueAudit.SERVICE,
                    serviceId,
                    photoValue(previous),
                    photoValue(photo.getId()),
                    now);
            return views.of(service);
        }));
    }

    @Override
    public ServiceView remove(UUID serviceId) {
        Scope at = scope.of(CatalogueScope.CHANGE);
        return CategoryServiceImpl.changing(() -> writing.execute(status -> {
            SalonService service = live(serviceId, at);
            UUID previous = service.getPhotoId();
            if (previous == null) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            service.changePhoto(null, now);
            services.flush();
            photos.remove(previous);
            audit.record(
                    at.actor(),
                    REMOVED,
                    CatalogueAudit.SERVICE,
                    serviceId,
                    photoValue(previous),
                    photoValue(null),
                    now);
            return views.of(service);
        }));
    }

    @Override
    public Stored read(UUID photoId) {
        TenantContext.require();
        Stored stored = reading.execute(status -> photos.findPhoto(photoId)
                .map(photo -> new Stored(photo.getBytes(), photo.getMediaType()))
                .orElse(null));
        if (stored == null) {
            throw new NotFoundException();
        }
        return stored;
    }

    private static ApplicationProblemException refused(ImageCheck.Refused refused) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", refused.detail())));
    }

    private SalonService live(UUID id, Scope at) {
        return services.findLive(id, at.branch()).orElseThrow(NotFoundException::new);
    }

    /** The photo's id in the audit, or null for none: never its bytes. */
    private static Map<String, Object> photoValue(UUID photoId) {
        Map<String, Object> value = new HashMap<>();
        value.put("photoId", photoId == null ? null : photoId.toString());
        return value;
    }
}
