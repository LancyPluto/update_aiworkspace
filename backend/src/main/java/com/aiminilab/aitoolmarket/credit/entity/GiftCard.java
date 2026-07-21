package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("gift_cards")
public class GiftCard {
    @TableId
    private Long id;
    private String cardCode;
    private Long packageId;
    private Long ownerUserId;
    private Long originalUserId;
    private Integer credits;
    private String cardType;
    private String requiredMemberTier;
    private String status;
    private Long rechargeOrderId;
    private String issuanceKey;
    private LocalDateTime redeemedAt;
    private Long giftedFromUserId;
    private LocalDateTime giftedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCardCode() { return cardCode; }
    public void setCardCode(String cardCode) { this.cardCode = cardCode; }
    public Long getPackageId() { return packageId; }
    public void setPackageId(Long packageId) { this.packageId = packageId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getOriginalUserId() { return originalUserId; }
    public void setOriginalUserId(Long originalUserId) { this.originalUserId = originalUserId; }
    public Integer getCredits() { return credits; }
    public void setCredits(Integer credits) { this.credits = credits; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public String getRequiredMemberTier() { return requiredMemberTier; }
    public void setRequiredMemberTier(String requiredMemberTier) { this.requiredMemberTier = requiredMemberTier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRechargeOrderId() { return rechargeOrderId; }
    public void setRechargeOrderId(Long rechargeOrderId) { this.rechargeOrderId = rechargeOrderId; }
    public String getIssuanceKey() { return issuanceKey; }
    public void setIssuanceKey(String issuanceKey) { this.issuanceKey = issuanceKey; }
    public LocalDateTime getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(LocalDateTime redeemedAt) { this.redeemedAt = redeemedAt; }
    public Long getGiftedFromUserId() { return giftedFromUserId; }
    public void setGiftedFromUserId(Long giftedFromUserId) { this.giftedFromUserId = giftedFromUserId; }
    public LocalDateTime getGiftedAt() { return giftedAt; }
    public void setGiftedAt(LocalDateTime giftedAt) { this.giftedAt = giftedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
