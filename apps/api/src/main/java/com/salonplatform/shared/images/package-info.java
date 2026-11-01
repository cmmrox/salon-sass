/**
 * Uploaded images checked by their content, decoded within fixed limits and re-encoded, so that nothing of the
 * uploaded file but its picture is kept (F04-S02, decision 086; moved here from {@code branding} by decision 093
 * so that {@code branding} and {@code catalogue} share one copy). Technical checks only: each module decides the
 * shape it keeps, its own limits under its settings prefix, and its own image table. A named interface of
 * {@code shared}.
 */
@NamedInterface("images")
package com.salonplatform.shared.images;

import org.springframework.modulith.NamedInterface;
