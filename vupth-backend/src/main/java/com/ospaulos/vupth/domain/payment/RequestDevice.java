package com.ospaulos.vupth.domain.payment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** O aparelho que fez o pedido atual: o {@code X-Device-Id}, se o app o mandar, senao o User-Agent (ate 200 caracteres). */
public final class RequestDevice {

    private RequestDevice() {}

    public static String current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        HttpServletRequest request = servlet.getRequest();
        String device = request.getHeader("X-Device-Id");
        if (device == null || device.isBlank()) {
            device = request.getHeader("User-Agent");
        }
        if (device == null || device.isBlank()) {
            return null;
        }
        String oneLine = device.replaceAll("[\\r\\n]+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }
}
