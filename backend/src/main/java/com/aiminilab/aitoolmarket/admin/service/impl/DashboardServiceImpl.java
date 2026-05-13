package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.DashboardOverviewResponse;
import com.aiminilab.aitoolmarket.admin.mapper.DashboardMapper;
import com.aiminilab.aitoolmarket.admin.service.DashboardService;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;

    public DashboardServiceImpl(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    @Override
    public DashboardOverviewResponse overview() {
        return new DashboardOverviewResponse(
                dashboardMapper.selectTaskTrend(),
                dashboardMapper.selectPopularTools(),
                dashboardMapper.sumConsumedCredits()
        );
    }
}
