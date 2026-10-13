package com.salonplatform.architecture.fixture.clean.shared.local;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalHook {

    LocalHookState state;
}
