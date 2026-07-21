package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("gift_card_packages")
public class GiftCardPackage {
    @TableId
    private Long id;
    private String packageCode;
    private String packageName;
    private Integer credits;
    private BigDecimal priceAmount;
    private String currency;
    private String cardTheme;
    private String cardType;
    private String requiredMemberTier;
    private String status;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPackageCode() { return packageCode; }
    public void setPackageCode(String packageCode) { this.packageCode = packageCode; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public Integer getCredits() { return credits; }
    public void setCredits(Integer credits) { this.credits = credits; }
    public BigDecimal getPriceAmount() { return priceAmount; }
    public void setPriceAmount(BigDecimal priceAmount) { this.priceAmount = priceAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getCardTheme() { return cardTheme; }
    public void setCardTheme(String cardTheme) { this.cardTheme = cardTheme; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public String getRequiredMemberTier() { return requiredMemberTier; }
    public void setRequiredMemberTier(String requiredMemberTier) { this.requiredMemberTier = requiredMemberTier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
