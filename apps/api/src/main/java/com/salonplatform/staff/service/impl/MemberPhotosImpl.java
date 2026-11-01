package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
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
import com.salonplatform.staff.config.StaffSettings;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.entity.MemberPhoto;
import com.salonplatform.staff.repository.MemberPhotoRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.MemberPhotos;
import com.salonplatform.staff.service.TeamMemberService.MemberView;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import com.salonplatform.staff.service.impl.TeamViews.Row;
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
 * The upload is checked, decoded and remade before the writing transaction opens, so a slow decode holds no database
 * connection; only the finished photo is written. A refused file changes nothing, so the member keeps their previous
 * photo. A replaced or removed photo is deleted in the same transaction. A photo takes no seat and changes no link, so
 * this takes no identity lock; it holds a member's row only, and never waits for that lock while holding it.
 */
@Service
class MemberPhotosImpl implements MemberPhotos {

    /**
     * A member's photo (AC-9, F05-S01's AC-10): at least 320 x 320 as uploaded, cropped to its centre square and kept
     * as a JPEG of at most 800 x 800.
     */
    static final ImageShape PHOTO = new ImageShape("a photo", 320, 320, 800, 800, true, ImageFormat.JPEG);

    private final StaffScope scope;
    private final MemberRepository members;
    private final MemberPhotoRepository photos;
    private final TeamRows rows;
    private final TeamViews views;
    private final TeamAudit audit;
    private final CatalogueListings catalogue;
    private final Clock clock;
    private final StaffSettings.Images limits;
    private final DecodePermits decoding;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    MemberPhotosImpl(
            StaffScope scope,
            MemberRepository members,
            MemberPhotoRepository photos,
            TeamRows rows,
            TeamViews views,
            TeamAudit audit,
            CatalogueListings catalogue,
            Clock clock,
            StaffSettings settings,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.photos = photos;
        this.rows = rows;
        this.views = views;
        this.audit = audit;
        this.catalogue = catalogue;
        this.clock = clock;
        this.limits = settings.images();
        this.decoding = new DecodePermits(limits.concurrentDecodes(), Duration.ofSeconds(2));
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public MemberView upload(UUID memberId, ImageUpload upload) throws IOException {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        // An unknown member is not found before anything is read.
        reading.execute(status -> active(memberId, at));
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
        CatalogueListing listing = catalogue.current();
        Row changed = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            Member member = active(memberId, at);
            UUID previous = member.getPhotoId();
            Instant now = clock.instant();
            MemberPhoto photo = photos.persist(MemberPhoto.uploaded(
                    at.branch(), accepted.mediaType(), accepted.width(), accepted.height(), accepted.bytes(), now));
            member.changePhoto(photo.getId(), now);
            members.flush();
            if (previous != null) {
                photos.remove(previous);
            }
            audit.record(
                    at.actor(),
                    TeamAudit.PHOTO_CHANGED,
                    memberId,
                    photoValue(previous),
                    photoValue(photo.getId()),
                    now);
            return Row.of(member, rows.choices(List.of(memberId), listing).get(memberId));
        }));
        return views.of(changed, listing, at.actor());
    }

    @Override
    public MemberView remove(UUID memberId) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        CatalogueListing listing = catalogue.current();
        Row changed = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            Member member = active(memberId, at);
            UUID previous = member.getPhotoId();
            if (previous == null) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            member.changePhoto(null, now);
            members.flush();
            photos.remove(previous);
            audit.record(at.actor(), TeamAudit.PHOTO_REMOVED, memberId, photoValue(previous), photoValue(null), now);
            return Row.of(member, rows.choices(List.of(memberId), listing).get(memberId));
        }));
        return views.of(changed, listing, at.actor());
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

    private Member active(UUID id, Scope at) {
        return members.findActive(id, at.branch()).orElseThrow(NotFoundException::new);
    }

    private static ApplicationProblemException refused(ImageCheck.Refused refused) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("file", refused.detail())));
    }

    /** The photo's id in the audit, or null for none: never its bytes. */
    private static Map<String, Object> photoValue(UUID photoId) {
        Map<String, Object> value = new HashMap<>();
        value.put("photoId", photoId == null ? null : photoId.toString());
        return value;
    }
}
