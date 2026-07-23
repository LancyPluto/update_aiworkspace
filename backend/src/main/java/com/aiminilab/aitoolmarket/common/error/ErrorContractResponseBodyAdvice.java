package com.aiminilab.aitoolmarket.common.error;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class ErrorContractResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ErrorContractResponseFactory responseFactory;

    public ErrorContractResponseBodyAdvice(ErrorContractResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!responseFactory.isV2() || !(body instanceof ApiResponse<?> legacyResponse)) {
            return body;
        }
        if (ErrorCode.SUCCESS.name().equals(legacyResponse.code())) {
            return new SuccessResponse<>(
                    legacyResponse.code(),
                    legacyResponse.data(),
                    legacyResponse.traceId() == null ? responseFactory.traceId() : legacyResponse.traceId()
            );
        }

        ErrorCode legacyErrorCode = parseLegacyErrorCode(legacyResponse.code());
        ErrorDefinition definition = LegacyErrorCodeMapper.fromLegacy(legacyErrorCode);
        response.setStatusCode(definition.httpStatus());
        return responseFactory.v2ErrorBody(
                servletRequest(request),
                definition,
                definition.defaultUserMessage(),
                legacyResponse.message()
        );
    }

    private ErrorCode parseLegacyErrorCode(String code) {
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ErrorCode.SYSTEM_ERROR;
        }
    }

    private HttpServletRequest servletRequest(ServerHttpRequest request) {
        return request instanceof ServletServerHttpRequest servletRequest
                ? servletRequest.getServletRequest()
                : null;
    }
}
