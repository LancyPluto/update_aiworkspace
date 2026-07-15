package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("credit_accounts")
public class CreditAccount {
    @TableId
    private Long id;
    private Long userId;
    private Integer balance;
    private Integer permanentBalance;
    private Integer membershipBalance;
    private Integer giftBalance;
    private Integer frozen;
    private Integer permanentFrozen;
    private Integer membershipFrozen;
    private Integer giftFrozen;
    private Integer expiredMembershipFrozen;
    private Integer totalExpired;
    private Integer bucketSchemaVersion;
    private Integer totalGranted;
    private Integer totalConsumed;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(Integer balance) {
        this.balance = balance;
    }

    public Integer getPermanentBalance() { return permanentBalance; }
    public void setPermanentBalance(Integer permanentBalance) { this.permanentBalance = permanentBalance; }

    public Integer getMembershipBalance() {
        return membershipBalance;
    }

    public void setMembershipBalance(Integer membershipBalance) {
        this.membershipBalance = membershipBalance;
    }

    public Integer getGiftBalance() {
        return giftBalance;
    }

    public void setGiftBalance(Integer giftBalance) {
        this.giftBalance = giftBalance;
    }

    public Integer getFrozen() {
        return frozen;
    }

    public void setFrozen(Integer frozen) {
        this.frozen = frozen;
    }

    public Integer getPermanentFrozen() { return permanentFrozen; }
    public void setPermanentFrozen(Integer permanentFrozen) { this.permanentFrozen = permanentFrozen; }
    public Integer getMembershipFrozen() { return membershipFrozen; }
    public void setMembershipFrozen(Integer membershipFrozen) { this.membershipFrozen = membershipFrozen; }
    public Integer getGiftFrozen() { return giftFrozen; }
    public void setGiftFrozen(Integer giftFrozen) { this.giftFrozen = giftFrozen; }
    public Integer getExpiredMembershipFrozen() { return expiredMembershipFrozen; }
    public void setExpiredMembershipFrozen(Integer expiredMembershipFrozen) { this.expiredMembershipFrozen = expiredMembershipFrozen; }
    public Integer getTotalExpired() { return totalExpired; }
    public void setTotalExpired(Integer totalExpired) { this.totalExpired = totalExpired; }
    public Integer getBucketSchemaVersion() { return bucketSchemaVersion; }
    public void setBucketSchemaVersion(Integer bucketSchemaVersion) { this.bucketSchemaVersion = bucketSchemaVersion; }

    public Integer getTotalGranted() {
        return totalGranted;
    }

    public void setTotalGranted(Integer totalGranted) {
        this.totalGranted = totalGranted;
    }

    public Integer getTotalConsumed() {
        return totalConsumed;
    }

    public void setTotalConsumed(Integer totalConsumed) {
        this.totalConsumed = totalConsumed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
