package com.salonplatform.architecture.fixture.localhookalways.shared.local;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** A hook that also loads in another profile. */
@RestController
@Profile({ "local", "staging" })
public class AlsoElsewhere {
}
