package com.salonplatform.architecture.fixture.declaredforanother.catalogue.service.impl;

import com.salonplatform.architecture.fixture.declaredforanother.staff.BookingsOnDate;
import org.springframework.stereotype.Service;

/** A contract staff declared for booking, implemented by another module instead: still refused. */
@Service
class CatalogueClosuresImpl implements BookingsOnDate {}
