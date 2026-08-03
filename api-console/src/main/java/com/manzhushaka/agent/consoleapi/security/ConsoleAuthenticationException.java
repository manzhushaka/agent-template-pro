package com.manzhushaka.agent.consoleapi.security;

public final class ConsoleAuthenticationException extends RuntimeException {
    private final Reason reason;

    public ConsoleAuthenticationException(Reason reason) {
        super(reason.message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_CAPTCHA("CONSOLE_CAPTCHA_INVALID", "图片验证码错误或已过期，请重新输入。"),
        INVALID_CREDENTIALS("CONSOLE_CREDENTIALS_INVALID", "用户名或密码错误。"),
        SESSION_INVALID("CONSOLE_SESSION_INVALID", "登录会话已失效，请重新登录。"),
        LOGIN_LOCKED("CONSOLE_LOGIN_LOCKED", "登录失败次数过多，请稍后再试。");

        private final String code;
        private final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }
}
