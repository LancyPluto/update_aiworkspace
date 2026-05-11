package com.aiminilab.aitoolmarket.credit.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("credit_accounts")
public class CreditAccount {
    @TableId
    private Long id;
    private Long userId;
    private Integer balance;
    private Integer frozen;
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

    public Integer getFrozen() {
        return frozen;
    }

    public void setFrozen(Integer frozen) {
        this.frozen = frozen;
    }

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
