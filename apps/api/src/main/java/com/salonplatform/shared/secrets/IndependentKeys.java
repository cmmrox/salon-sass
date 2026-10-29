package com.salonplatform.shared.secrets;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application when two secret keys are the same, comparing every key of every
 * {@link SecretKeys} bean with every other in constant time. The message names the two purposes, never a
 * key.
 */
@Component
class IndependentKeys {

    IndependentKeys(List<SecretKeys> holders) {
        List<Map.Entry<String, byte[]>> keys = new ArrayList<>();
        holders.forEach(holder -> keys.addAll(holder.byPurpose().entrySet()));
        for (int first = 0; first < keys.size(); first++) {
            for (int second = first + 1; second < keys.size(); second++) {
                if (MessageDigest.isEqual(
                        keys.get(first).getValue(), keys.get(second).getValue())) {
                    throw new IllegalStateException("The " + keys.get(first).getKey() + " and "
                            + keys.get(second).getKey()
                            + " keys are the same: every secret key must be independent of every other");
                }
            }
        }
    }
}
