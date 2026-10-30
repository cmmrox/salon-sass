package com.salonplatform.architecture.fixture.sharedservice.shared.time;

import org.springframework.stereotype.Service;

/** A shared service in its capability package, beside its interface, instead of in the capability's impl. */
@Service
class SystemClockService implements SharedClock {}
