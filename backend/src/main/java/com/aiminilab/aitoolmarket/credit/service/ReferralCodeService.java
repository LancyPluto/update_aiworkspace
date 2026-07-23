package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ReferralCodeService {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Pattern CODE_PATTERN = Pattern.compile("^(?=.*[2-9])(?=.*[A-HJ-NP-Z])[2-9A-HJ-NP-Z]{6}$");
    private static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 100;

    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    public ReferralCodeService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void assignReferralCode(User user) {
        if (normalize(user.getReferralCode()) != null) {
            return;
        }
        user.setReferralCode(nextAvailableCode());
    }

    public void backfillMissingCodes() {
        for (User user : userMapper.findUsersMissingReferralCode()) {
            boolean assigned = false;
            for (int attempt = 0; attempt < MAX_ATTEMPTS && !assigned; attempt++) {
                String candidate = randomCode();
                if (userMapper.findAnyByReferralCode(candidate).isPresent()) {
                    continue;
                }
                try {
                    assigned = userMapper.updateReferralCodeIfMissing(user.getId(), candidate) == 1;
                } catch (DuplicateKeyException ignored) {
                    // Another registration or startup instance claimed this code first.
                }
            }
            if (!assigned && userMapper.findAnyById(user.getId()).map(User::getReferralCode).orElse(null) == null) {
                throw new IllegalStateException("Unable to allocate a unique referral code");
            }
        }
    }

    public Optional<User> findInviter(String code) {
        String normalized = normalize(code);
        return normalized == null ? Optional.empty() : userMapper.findByReferralCode(normalized);
    }

    private String nextAvailableCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (userMapper.findAnyByReferralCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique referral code");
    }

    private String randomCode() {
        String candidate;
        do {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            candidate = code.toString();
        } while (!CODE_PATTERN.matcher(candidate).matches());
        return candidate;
    }

    private String normalize(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return CODE_PATTERN.matcher(normalized).matches() ? normalized : null;
    }
}
