package com.iris.lite.java.api.web;

/**
 * 统一错误响应体。
 *
 * <p>REST 错误与鉴权失败（IRIS-4010）共用这一结构，
 * 让调用方只需写一套解析逻辑。
 *
 * @param code    稳定错误码（如 IRIS-1001）
 * @param message 人类可读消息（中文，面向排障的人）
 */
public record ErrorResponse(String code, String message) {
}
