package com.aiminilab.aitoolmarket.auth.security;

import java.time.Duration;

public interface TokenDenylistService {

    void deny(String tokenId, Duration ttl);

    boolean isDenied(String tokenId);
}
