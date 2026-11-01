-- F04-S03, owned by platform (decisions 078, 087 and 088): the Sri Lankan public, bank, mercantile and Poya
-- holidays, kept per year as platform reference data with no tenant_id. An Admin keeps a year's draft (a CSV
-- import or single days) and publishes it; each publication is a revision. The published days are a copy of
-- the draft taken at publication, each carrying the revision that last added or changed it, so a salon's
-- choice made against a day stays valid until a revision changes that day.
--
-- The tenant runtime role gets no grant on these tables (docs/architecture/multi-tenancy.md, "Platform data
-- and operator access"). It reads only the published days, through holiday_calendar_published(), a narrow
-- SECURITY DEFINER function like tenant_by_slug(). The platform runtime role keeps the draft and publishes.

CREATE TABLE platform_holiday_year (
    year         integer     NOT NULL,
    version      bigint      NOT NULL,
    revision     integer     NOT NULL,
    published_at timestamptz,
    published_by uuid,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT platform_holiday_year_pkey PRIMARY KEY (year),
    CONSTRAINT platform_holiday_year_publisher_fkey FOREIGN KEY (published_by) REFERENCES operator_user (id),
    CONSTRAINT platform_holiday_year_in_range CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT platform_holiday_year_version_counted CHECK (version >= 0 AND revision >= 0),
    -- Revision 0 is a year never published; every published year names when and by whom.
    CONSTRAINT platform_holiday_year_publication CHECK (
        (revision = 0) = (published_at IS NULL) AND (published_at IS NULL) = (published_by IS NULL))
);

-- The draft the Admin edits. Each date once per year, inside that year; a name of 1 to 120 characters with no
-- surrounding space; one of the four kinds the gazette uses.
CREATE TABLE platform_holiday_draft_day (
    year         integer     NOT NULL,
    holiday_date date        NOT NULL,
    name         text        NOT NULL,
    kind         text        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT platform_holiday_draft_day_pkey PRIMARY KEY (year, holiday_date),
    CONSTRAINT platform_holiday_draft_day_year_fkey FOREIGN KEY (year) REFERENCES platform_holiday_year (year),
    CONSTRAINT platform_holiday_draft_day_in_year CHECK (EXTRACT(YEAR FROM holiday_date) = year),
    CONSTRAINT platform_holiday_draft_day_name CHECK (
        name = btrim(name) AND name <> '' AND char_length(name) <= 120),
    CONSTRAINT platform_holiday_draft_day_kind CHECK (kind IN ('poya', 'public', 'bank', 'mercantile'))
);

-- What salons see: the draft as it was at the year's latest publication.
CREATE TABLE platform_holiday_published_day (
    year         integer     NOT NULL,
    holiday_date date        NOT NULL,
    name         text        NOT NULL,
    kind         text        NOT NULL,
    revision     integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT platform_holiday_published_day_pkey PRIMARY KEY (year, holiday_date),
    CONSTRAINT platform_holiday_published_day_year_fkey FOREIGN KEY (year) REFERENCES platform_holiday_year (year),
    CONSTRAINT platform_holiday_published_day_in_year CHECK (EXTRACT(YEAR FROM holiday_date) = year),
    CONSTRAINT platform_holiday_published_day_name CHECK (
        name = btrim(name) AND name <> '' AND char_length(name) <= 120),
    CONSTRAINT platform_holiday_published_day_kind CHECK (kind IN ('poya', 'public', 'bank', 'mercantile')),
    CONSTRAINT platform_holiday_published_day_revision CHECK (revision >= 1)
);

REVOKE ALL ON TABLE platform_holiday_year FROM PUBLIC;
REVOKE ALL ON TABLE platform_holiday_draft_day FROM PUBLIC;
REVOKE ALL ON TABLE platform_holiday_published_day FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE platform_holiday_year TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE platform_holiday_draft_day TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE platform_holiday_published_day TO "${platform_role}";

-- The published days of the published years from first_year to last_year, for platform's HolidayCalendar on
-- the tenant role. Nothing of a draft, and nothing of who published. A published year with no day left (a
-- revision withdrew them all) answers one row with no date, so the year itself is still seen.
CREATE FUNCTION holiday_calendar_published(first_year integer, last_year integer)
    RETURNS TABLE (year integer, year_revision integer, published_at timestamptz, holiday_date date, name text,
                   kind text, day_revision integer)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT y.year, y.revision, y.published_at, d.holiday_date, d.name, d.kind, d.revision
      FROM public.platform_holiday_year y
      LEFT JOIN public.platform_holiday_published_day d ON d.year = y.year
     WHERE y.revision > 0 AND y.year BETWEEN first_year AND last_year
     ORDER BY y.year, d.holiday_date
$$;

REVOKE ALL ON FUNCTION holiday_calendar_published(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION holiday_calendar_published(integer, integer) TO "${application_role}";
