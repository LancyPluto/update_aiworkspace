package com.aiminilab.aitoolmarket.agent.connectivity;

import com.aiminilab.aitoolmarket.agent.support.OpenAiCompatibleModelsEndpoint;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ModelsEndpointResolver {

    private static final Set<String> SUPPORTED_VENDORS = Set.of("agnes");

    public boolean supports(String vendorCode) {
        return SUPPORTED_VENDORS.contains(normalize(vendorCode));
    }

    public Optional<String> resolve(String vendorCode, String baseUrl) {
        if (!supports(vendorCode) || baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            String endpoint = OpenAiCompatibleModelsEndpoint.resolve(baseUrl);
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return Optional.empty();
            }
            return Optional.of(endpoint);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
