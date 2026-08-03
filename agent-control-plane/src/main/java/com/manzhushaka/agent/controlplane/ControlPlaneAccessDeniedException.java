package com.manzhushaka.agent.controlplane;

public final class ControlPlaneAccessDeniedException extends RuntimeException {
    public ControlPlaneAccessDeniedException() {
        super("当前管理员没有执行此操作的权限。");
    }
}
