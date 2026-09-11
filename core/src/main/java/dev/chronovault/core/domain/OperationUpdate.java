package dev.chronovault.core.domain;

import java.time.Instant;

public record OperationUpdate(
    OperationId operationId,
    OperationStage stage,
    int progress,
    String message,
    Instant timestamp
) {}