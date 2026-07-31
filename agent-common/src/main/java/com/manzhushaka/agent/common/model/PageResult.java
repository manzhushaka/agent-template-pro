package com.manzhushaka.agent.common.model;

import java.util.List;
public record PageResult<T>(List<T> items, long total) { }
