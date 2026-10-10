package com.example.order.common;

/** RFC 9457 Problem Details 본문. */
public record Problem(String type, String title, int status, String detail, String code) {
}
