/**
 * The API's local test hooks (decision 029): a test clock QA can set, and a probe that writes an
 * outbox message and an audit entry and has a fake provider that can be told to fail, wait or
 * abort. Every class here loads in the {@code local} profile only, is served under {@code /local/}
 * outside the API contract, and is used by nothing outside this package; the architecture tests
 * hold both.
 */
package com.salonplatform.shared.local;
