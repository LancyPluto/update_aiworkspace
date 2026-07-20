package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("referral_registration_rewards")
public class ReferralRegistrationReward {
    @TableId
    private Long id;
    private Long referralId;
    private Long beneficiaryUserId;
    private String beneficiaryRole;
    private Integer rewardCredits;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReferralId() { return referralId; }
    public void setReferralId(Long referralId) { this.referralId = referralId; }
    public Long getBeneficiaryUserId() { return beneficiaryUserId; }
    public void setBeneficiaryUserId(Long beneficiaryUserId) { this.beneficiaryUserId = beneficiaryUserId; }
    public String getBeneficiaryRole() { return beneficiaryRole; }
    public void setBeneficiaryRole(String beneficiaryRole) { this.beneficiaryRole = beneficiaryRole; }
    public Integer getRewardCredits() { return rewardCredits; }
    public void setRewardCredits(Integer rewardCredits) { this.rewardCredits = rewardCredits; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
