package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.dto.GiftCardPackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.GiftCardResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;

import java.util.List;

public interface GiftCardService {

    List<GiftCardPackageResponse> packages();

    List<GiftCardResponse> myGiftCards(Long userId, String status);

    GiftCardResponse redeem(Long userId, Long giftCardId);

    GiftCardResponse redeemByCode(Long userId, String cardCode);

    GiftCardResponse transfer(Long userId, Long giftCardId, String recipientAccount);

    GiftCardResponse issueAdminGiftCard(Long userId,
                                        Long giftCardPackageId,
                                        int credits,
                                        String operationId,
                                        Long operatorId,
                                        String reason);

    void createGiftCardFromOrder(Long userId, Long orderId, Long giftCardPackageId, int credits);

    void createGiftCardsFromOrderItems(Long userId, Long orderId, List<CreditRechargeOrderItem> items);
}
