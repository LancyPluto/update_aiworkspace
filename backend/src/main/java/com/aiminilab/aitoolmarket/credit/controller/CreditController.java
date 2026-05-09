package com.aiminilab.aitoolmarket.credit.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreditLogResponse;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credits")
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    @GetMapping("/account")
    public ApiResponse<CreditAccountResponse> account() {
        return ApiResponse.success(creditService.account(AuthContext.get().userId()));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResponse<CreditLogResponse>> logs(@RequestParam(required = false) String logType,
                                                             @RequestParam(required = false) Integer pageNo,
                                                             @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(creditService.logs(AuthContext.get().userId(), logType, pageNo, pageSize));
    }
}
