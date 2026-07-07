package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardPackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.aiminilab.aitoolmarket.credit.entity.GiftCard;
import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;
import com.aiminilab.aitoolmarket.credit.mapper.GiftCardMapper;
import com.aiminilab.aitoolmarket.credit.mapper.GiftCardPackageMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.GiftCardService;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GiftCardServiceImpl implements GiftCardService {

    private final GiftCardPackageMapper packageMapper;
    private final GiftCardMapper giftCardMapper;
    private final CreditService creditService;
    private final UserMapper userMapper;

    public GiftCardServiceImpl(GiftCardPackageMapper packageMapper,
                               GiftCardMapper giftCardMapper,
                               CreditService creditService,
                               UserMapper userMapper) {
        this.packageMapper = packageMapper;
        this.giftCardMapper = giftCardMapper;
        this.creditService = creditService;
        this.userMapper = userMapper;
    }

    @Override
    public List<GiftCardPackageResponse> packages() {
        return packageMapper.findActive().stream()
                .map(GiftCardPackageResponse::from)
                .toList();
    }

    @Override
    public List<GiftCardResponse> myGiftCards(Long userId, String status) {
        List<GiftCard> cards = giftCardMapper.findByOwnerAndStatus(userId, status);
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<Long, GiftCardPackage> packageMap = loadPackageMap(cards);
        return cards.stream()
                .map(card -> {
                    GiftCardPackage pkg = packageMap.get(card.getPackageId());
                    return GiftCardResponse.from(card,
                            pkg != null ? pkg.getPackageName() : null,
                            pkg != null ? pkg.getCardTheme() : null);
                })
                .toList();
    }

    @Override
    @Transactional
    public GiftCardResponse redeem(Long userId, Long giftCardId) {
        GiftCard card = giftCardMapper.findByIdAndOwner(giftCardId, userId);
        if (card == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "礼品卡不存在");
        }
        if (!"UNUSED".equals(card.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡已使用");
        }

        creditService.giftRedeemAdd(userId, card.getId(), card.getCredits(), "礼品卡兑换 " + card.getCardCode());

        int updated = giftCardMapper.markRedeemed(card.getId(), userId, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡状态已变更，请刷新后重试");
        }

        GiftCard refreshed = giftCardMapper.findByIdAndOwner(giftCardId, userId);
        GiftCardPackage pkg = packageMapper.selectById(refreshed.getPackageId());
        return GiftCardResponse.from(refreshed,
                pkg != null ? pkg.getPackageName() : null,
                pkg != null ? pkg.getCardTheme() : null);
    }

    @Override
    @Transactional
    public GiftCardResponse redeemByCode(Long userId, String rawCardCode) {
        String cardCode = normalizeCardCode(rawCardCode);
        GiftCard card = giftCardMapper.findByCardCode(cardCode);
        if (card == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "兑换码无效");
        }
        if (!"UNUSED".equals(card.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡已使用");
        }

        creditService.giftRedeemAdd(userId, card.getId(), card.getCredits(), "礼品卡兑换 " + cardCode);

        int updated = giftCardMapper.markRedeemedByCode(card.getId(), userId, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡状态已变更，请刷新后重试");
        }

        GiftCard refreshed = giftCardMapper.selectById(card.getId());
        GiftCardPackage pkg = packageMapper.selectById(refreshed.getPackageId());
        return GiftCardResponse.from(refreshed,
                pkg != null ? pkg.getPackageName() : null,
                pkg != null ? pkg.getCardTheme() : null);
    }

    @Override
    @Transactional
    public GiftCardResponse transfer(Long userId, Long giftCardId, String recipientAccount) {
        GiftCard card = giftCardMapper.findByIdAndOwner(giftCardId, userId);
        if (card == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "礼品卡不存在");
        }
        if (!"UNUSED".equals(card.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡已使用，无法赠送");
        }

        User recipient = userMapper.findByUsernameOrPhone(recipientAccount.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "接收方用户不存在"));
        if (recipient.getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能赠送给自己");
        }

        int updated = giftCardMapper.transferOwnership(card.getId(), recipient.getId(), userId, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡状态已变更，请刷新后重试");
        }

        GiftCard refreshed = giftCardMapper.findByIdAndOwner(giftCardId, recipient.getId());
        if (refreshed == null) {
            refreshed = giftCardMapper.selectById(giftCardId);
        }
        GiftCardPackage pkg = packageMapper.selectById(refreshed.getPackageId());
        return GiftCardResponse.from(refreshed,
                pkg != null ? pkg.getPackageName() : null,
                pkg != null ? pkg.getCardTheme() : null);
    }

    @Override
    @Transactional
    public void createGiftCardFromOrder(Long userId, Long orderId, Long giftCardPackageId, int credits) {
        if (giftCardMapper.countByRechargeOrderId(orderId) > 0) {
            return;
        }
        GiftCardPackage pkg = packageMapper.selectById(giftCardPackageId);
        if (pkg == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡套餐不存在");
        }

        createGiftCard(userId, orderId, giftCardPackageId, credits);
    }

    @Override
    @Transactional
    public void createGiftCardsFromOrderItems(Long userId, Long orderId, List<CreditRechargeOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (giftCardMapper.countByRechargeOrderId(orderId) > 0) {
            return;
        }
        for (CreditRechargeOrderItem item : items) {
            GiftCardPackage pkg = packageMapper.selectById(item.getGiftCardPackageId());
            if (pkg == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "礼品卡套餐不存在");
            }
            int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
            if (quantity <= 0) {
                continue;
            }
            for (int i = 0; i < quantity; i++) {
                createGiftCard(userId, orderId, item.getGiftCardPackageId(), item.getCredits());
            }
        }
    }

    private void createGiftCard(Long userId, Long orderId, Long giftCardPackageId, int credits) {
        LocalDateTime now = LocalDateTime.now();
        GiftCard card = new GiftCard();
        card.setCardCode(generateCardCode());
        card.setPackageId(giftCardPackageId);
        card.setOwnerUserId(userId);
        card.setOriginalUserId(userId);
        card.setCredits(credits);
        card.setStatus("UNUSED");
        card.setRechargeOrderId(orderId);
        card.setRedeemedAt(null);
        card.setGiftedFromUserId(null);
        card.setGiftedAt(null);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);
        giftCardMapper.insert(card);
    }

    private Map<Long, GiftCardPackage> loadPackageMap(List<GiftCard> cards) {
        List<Long> packageIds = cards.stream()
                .map(GiftCard::getPackageId)
                .distinct()
                .toList();
        Map<Long, GiftCardPackage> map = new HashMap<>();
        for (Long pid : packageIds) {
            GiftCardPackage pkg = packageMapper.selectById(pid);
            if (pkg != null) {
                map.put(pid, pkg);
            }
        }
        return map;
    }

    private String generateCardCode() {
        return "GC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private String normalizeCardCode(String rawCardCode) {
        if (rawCardCode == null || rawCardCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "兑换码不能为空");
        }
        return rawCardCode.trim().toUpperCase(Locale.ROOT);
    }
}
