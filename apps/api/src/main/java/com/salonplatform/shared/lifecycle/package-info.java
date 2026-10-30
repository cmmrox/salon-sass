/**
 * Each module's tenant tables, registered so that a closed salon's data can be exported and erased
 * without another module reading them. A named interface of {@code shared}.
 */
@NamedInterface("lifecycle")
package com.salonplatform.shared.lifecycle;

import org.springframework.modulith.NamedInterface;
