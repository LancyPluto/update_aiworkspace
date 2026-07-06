package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("referral_rewards")
public class ReferralReward {
    @TableId
    private Long id;
    private Long referralId;
    private Long inviterUserId;
    private Long inviteeUserId;
    private Long rechargeOrderId;
    private Integer rewardCredits;
    private BigDecimal rewardRate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReferralId() { return referralId; }
    public void setReferralId(Long referralId) { this.referralId = referralId; }
    public Long getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(Long inviterUserId) { this.inviterUserId = inviterUserId; }
    public Long getInviteeUserId() { return inviteeUserId; }
    public void setInviteeUserId(Long inviteeUserId) { this.inviteeUserId = inviteeUserId; }
    public Long getRechargeOrderId() { return rechargeOrderId; }
    public void setRechargeOrderId(Long rechargeOrderId) { this.rechargeOrderId = rechargeOrderId; }
    public Integer getRewardCredits() { return rewardCredits; }
    public void setRewardCredits(Integer rewardCredits) { this.rewardCredits = rewardCredits; }
    public BigDecimal getRewardRate() { return rewardRate; }
    public void setRewardRate(BigDecimal rewardRate) { this.rewardRate = rewardRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
