package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A team member (STF-01, decision 100): a person who performs services. Not a staff account, which is
 * {@code identity}'s; a member holds at most one account's id. Removing a member keeps a tombstone, so past bookings
 * keep the name, with no link, no photo and no online visibility.
 */
@Entity
@Table(name = "staff_team_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "title")
    private String title;

    @Column(name = "highlights")
    private String highlights;

    @Column(name = "bio")
    private String bio;

    @Column(name = "shown_online", nullable = false)
    private boolean shownOnline;

    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "sign_in_account_id")
    private UUID signInAccountId;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** What a person's profile says, already checked. */
    public record Profile(String name, String title, String highlights, String bio, boolean shownOnline) {}

    /** A new member of the current salon's branch, which Hibernate stamps. */
    public static Member added(UUID branchId, Profile profile, UUID signInAccountId, Instant at) {
        Member member = new Member();
        member.id = UUID.randomUUID();
        member.branchId = branchId;
        member.apply(profile);
        member.signInAccountId = signInAccountId;
        member.createdAt = at;
        member.updatedAt = at;
        return member;
    }

    /** Changes the profile and the link (null for no sign-in). */
    public void change(Profile profile, UUID newSignInAccountId, Instant at) {
        apply(profile);
        signInAccountId = newSignInAccountId;
        updatedAt = at;
    }

    /** The account was removed: the person stays on the team with no sign-in. */
    public void clearLink(Instant at) {
        signInAccountId = null;
        updatedAt = at;
    }

    /** Sets or removes (null) the photo. */
    public void changePhoto(UUID newPhotoId, Instant at) {
        photoId = newPhotoId;
        updatedAt = at;
    }

    /** Leaves the team: the id and name stay for past bookings, and the rest of what they held goes. */
    public void remove(Instant at) {
        signInAccountId = null;
        photoId = null;
        shownOnline = false;
        removedAt = at;
        updatedAt = at;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    private void apply(Profile profile) {
        name = profile.name();
        title = profile.title();
        highlights = profile.highlights();
        bio = profile.bio();
        shownOnline = profile.shownOnline();
    }
}
