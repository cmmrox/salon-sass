package com.salonplatform.staff.service;

import com.salonplatform.shared.images.ImageUpload;
import com.salonplatform.staff.service.TeamMemberService.MemberView;
import java.io.IOException;
import java.util.UUID;

/**
 * A team member's one photo (F06-S01 AC-9, under F05-S01's rules): uploaded and removed with {@code team.manage}, served
 * to anyone by its id on the salon's hostname.
 */
public interface MemberPhotos {

    /** A photo's bytes, as kept, with their media type. */
    record Stored(byte[] bytes, String mediaType) {}

    /**
     * Checks an uploaded file by its content, remakes it and makes it the member's photo, replacing any earlier one.
     * The caller's permission, the member and the declared size are checked before the file is read. 400 on
     * {@code file} with the reason, the member keeping their previous photo.
     *
     * @throws IOException when the file cannot be read
     */
    MemberView upload(UUID memberId, ImageUpload upload) throws IOException;

    /** Removes the member's photo. 409 {@code invalid_state} when they have none. */
    MemberView remove(UUID memberId);

    /** A photo of the request's salon. 404 for any other id. */
    Stored read(UUID photoId);
}
